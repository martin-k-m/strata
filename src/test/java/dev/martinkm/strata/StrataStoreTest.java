package dev.martinkm.strata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Random;
import java.util.TreeMap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrataStoreTest {

    private static byte[] k(String s) {
        return s.getBytes(UTF_8);
    }

    @Test
    void putGetDeleteRoundTrip(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir)) {
            store.put(k("alpha"), k("one"));
            store.put(k("beta"), k("two"));

            assertArrayEquals(k("one"), store.get(k("alpha")).orElseThrow());
            assertArrayEquals(k("two"), store.get(k("beta")).orElseThrow());
            assertTrue(store.get(k("missing")).isEmpty());

            store.put(k("alpha"), k("one-prime")); // overwrite wins
            assertArrayEquals(k("one-prime"), store.get(k("alpha")).orElseThrow());

            store.delete(k("beta"));
            assertTrue(store.get(k("beta")).isEmpty());
            assertEquals(1, store.size());
        }
    }

    @Test
    void getReturnsACopyThatCannotMutateTheStore(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir)) {
            store.put(k("key"), k("value"));
            byte[] leaked = store.get(k("key")).orElseThrow();
            leaked[0] = '!';
            assertArrayEquals(k("value"), store.get(k("key")).orElseThrow());
        }
    }

    @Test
    void matchesAnInMemoryModelUnderRandomOps(@TempDir Path dir) {
        Random rng = new Random(42);
        TreeMap<String, byte[]> oracle = new TreeMap<>();
        int keyspace = 200;

        try (StrataStore store = StrataStore.open(dir)) {
            for (int i = 0; i < 5_000; i++) {
                String key = "key-" + rng.nextInt(keyspace);
                if (rng.nextInt(10) < 7) {
                    byte[] value = randomBytes(rng, 1 + rng.nextInt(40));
                    store.put(k(key), value);
                    oracle.put(key, value);
                } else {
                    store.delete(k(key));
                    oracle.remove(key);
                }
            }

            for (int n = 0; n < keyspace; n++) {
                String key = "key-" + n;
                var got = store.get(k(key));
                if (oracle.containsKey(key)) {
                    assertArrayEquals(oracle.get(key), got.orElseThrow(), key);
                } else {
                    assertTrue(got.isEmpty(), key + " should be absent");
                }
            }
            assertEquals(oracle.size(), store.size());
        }
    }

    @Test
    void survivesReopen(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir)) {
            store.put(k("a"), k("1"));
            store.put(k("b"), k("2"));
            store.delete(k("a"));
        }
        // A fresh process would call open() again; the log replays the same three
        // mutations in order, so the reopened state is exactly the closed one.
        try (StrataStore store = StrataStore.open(dir)) {
            assertTrue(store.get(k("a")).isEmpty());
            assertArrayEquals(k("2"), store.get(k("b")).orElseThrow());
            assertEquals(1, store.size());
        }
    }

    @Test
    void recoversFromATornTrailingRecord(@TempDir Path dir) throws IOException {
        try (StrataStore store = StrataStore.open(dir)) {
            store.put(k("x"), k("10"));
            store.put(k("y"), k("20"));
        }

        // Simulate a crash mid-append: a header claiming a 50-byte payload with
        // only two bytes actually written after it.
        Path walPath = dir.resolve("wal.log");
        try (FileChannel ch = FileChannel.open(walPath, WRITE, APPEND)) {
            ch.write(ByteBuffer.wrap(new byte[] {0, 0, 0, 50, 1, 2, 3, 4, 9, 9}));
        }

        try (StrataStore store = StrataStore.open(dir)) {
            assertArrayEquals(k("10"), store.get(k("x")).orElseThrow());
            assertArrayEquals(k("20"), store.get(k("y")).orElseThrow());
            assertEquals(2, store.size());
            store.put(k("z"), k("30")); // and it is writable again after truncation
        }
        try (StrataStore store = StrataStore.open(dir)) {
            assertEquals(3, store.size());
            assertArrayEquals(k("30"), store.get(k("z")).orElseThrow());
        }
    }

    @Test
    void matchesTheModelWhileFlushingMidRun(@TempDir Path dir) {
        // A tiny flush threshold forces many spills to SSTables during the run, so
        // reads exercise the memtable, the bloom filter, the sparse index and
        // several stacked tables rather than the memtable alone.
        Random rng = new Random(7);
        TreeMap<String, byte[]> oracle = new TreeMap<>();
        int keyspace = 300;

        try (StrataStore store = StrataStore.open(dir, 32)) {
            for (int i = 0; i < 8_000; i++) {
                String key = "key-" + rng.nextInt(keyspace);
                if (rng.nextInt(10) < 7) {
                    byte[] value = randomBytes(rng, 1 + rng.nextInt(40));
                    store.put(k(key), value);
                    oracle.put(key, value);
                } else {
                    store.delete(k(key));
                    oracle.remove(key);
                }
            }

            for (int n = 0; n < keyspace; n++) {
                String key = "key-" + n;
                var got = store.get(k(key));
                if (oracle.containsKey(key)) {
                    assertArrayEquals(oracle.get(key), got.orElseThrow(), key);
                } else {
                    assertTrue(got.isEmpty(), key + " should be absent");
                }
            }
            assertEquals(oracle.size(), store.size());
        }
    }

    @Test
    void tombstoneInMemtableShadowsAnOlderSSTableValue(@TempDir Path dir) {
        // A threshold of one flushes after every write, so each mutation lands in
        // its own SSTable and the delete below is a tombstone that must beat the
        // value already sitting in an older table for the same key.
        try (StrataStore store = StrataStore.open(dir, 1)) {
            store.put(k("gone"), k("value")); // flushes to an SSTable
            store.put(k("stays"), k("keep")); // flushes to a newer SSTable
            store.delete(k("gone"));          // tombstone, in a newer table still

            assertTrue(store.get(k("gone")).isEmpty(), "delete must shadow the older on-disk value");
            assertArrayEquals(k("keep"), store.get(k("stays")).orElseThrow());
            assertEquals(1, store.size());
        }
        // And the shadowing survives a reopen that rebuilds purely from disk.
        try (StrataStore store = StrataStore.open(dir, 1)) {
            assertTrue(store.get(k("gone")).isEmpty());
            assertArrayEquals(k("keep"), store.get(k("stays")).orElseThrow());
            assertEquals(1, store.size());
        }
    }

    @Test
    void recoversFromSSTablesPlusWalTogether(@TempDir Path dir) {
        // Force some data down to SSTables, then leave a tail of writes only in the
        // memtable and log. A reopen must reconstruct both layers and merge them.
        try (StrataStore store = StrataStore.open(dir, 4)) {
            store.put(k("a"), k("1"));
            store.put(k("b"), k("2"));
            store.put(k("c"), k("3"));
            store.put(k("d"), k("4")); // fourth write trips the flush, draining to an SSTable
            // These stay in the fresh memtable and the rolled log, not yet flushed.
            store.put(k("e"), k("5"));
            store.delete(k("b"));
        }
        try (StrataStore store = StrataStore.open(dir, 4)) {
            assertArrayEquals(k("1"), store.get(k("a")).orElseThrow()); // from the SSTable
            assertTrue(store.get(k("b")).isEmpty());                    // tombstone replayed from the log
            assertArrayEquals(k("3"), store.get(k("c")).orElseThrow());
            assertArrayEquals(k("4"), store.get(k("d")).orElseThrow());
            assertArrayEquals(k("5"), store.get(k("e")).orElseThrow()); // from the log
            assertEquals(4, store.size());
        }
    }

    @Test
    void compactionMergesTablesAndDropsDeletedKeys(@TempDir Path dir) {
        // With a threshold of one and the compaction trigger at four tables, enough
        // writes here force at least one compaction. The observable state must not
        // change: overwrites keep their newest value, deletes stay gone.
        try (StrataStore store = StrataStore.open(dir, 1)) {
            for (int i = 0; i < 10; i++) {
                store.put(k("k" + i), k("v" + i));
            }
            store.put(k("k3"), k("v3-prime")); // overwrite
            store.delete(k("k7"));             // delete
            store.compact();                   // fold everything into one table

            assertArrayEquals(k("v0"), store.get(k("k0")).orElseThrow());
            assertArrayEquals(k("v3-prime"), store.get(k("k3")).orElseThrow());
            assertTrue(store.get(k("k7")).isEmpty());
            assertEquals(9, store.size());
        }
        // The merged result is a normal table set, so a reopen sees the same thing.
        try (StrataStore store = StrataStore.open(dir, 1)) {
            assertArrayEquals(k("v3-prime"), store.get(k("k3")).orElseThrow());
            assertTrue(store.get(k("k7")).isEmpty());
            assertEquals(9, store.size());
        }
    }

    private static byte[] randomBytes(Random rng, int len) {
        byte[] b = new byte[len];
        rng.nextBytes(b);
        return b;
    }
}

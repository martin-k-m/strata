package dev.martinkm.strata;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void scanMatchesATreeMapOracleAcrossLayers(@TempDir Path dir) {
        // A tiny flush threshold spreads the live keys over the memtable and several
        // stacked SSTables, so the scan has to k-way merge real layers, not one map.
        Random rng = new Random(11);
        TreeMap<String, byte[]> oracle = new TreeMap<>();
        int keyspace = 250;

        try (StrataStore store = StrataStore.open(dir, 24)) {
            for (int i = 0; i < 6_000; i++) {
                String key = "key-" + String.format("%03d", rng.nextInt(keyspace));
                if (rng.nextInt(10) < 7) {
                    byte[] value = randomBytes(rng, 1 + rng.nextInt(40));
                    store.put(k(key), value);
                    oracle.put(key, value);
                } else {
                    store.delete(k(key));
                    oracle.remove(key);
                }
            }

            assertScanMatches(store, oracle, null, null);
            assertScanMatches(store, oracle, "key-050", "key-150");
            assertScanMatches(store, oracle, null, "key-100");
            assertScanMatches(store, oracle, "key-200", null);
            assertScanMatches(store, oracle, "key-000", "key-249");
            assertScanMatches(store, oracle, "key-123", "key-124"); // one key wide
        }
    }

    @Test
    void scanExcludesTombstonedKeys(@TempDir Path dir) {
        // Threshold of one puts every write in its own table, so the deletes below
        // are tombstones in newer tables shadowing values in older ones. The scan
        // must skip them rather than surface the stale value.
        try (StrataStore store = StrataStore.open(dir, 1)) {
            for (int i = 0; i < 10; i++) {
                store.put(k("k" + i), k("v" + i));
            }
            store.delete(k("k3"));
            store.delete(k("k7"));

            List<String> keys = scanKeys(store, null, null);
            assertEquals(List.of("k0", "k1", "k2", "k4", "k5", "k6", "k8", "k9"), keys);
            assertFalse(keys.contains("k3"));
            assertFalse(keys.contains("k7"));

            // A tombstone inside a requested range is dropped, not emitted.
            assertEquals(List.of("k4", "k5", "k6"), scanKeys(store, "k3", "k7"));
        }
    }

    @Test
    void scanHandlesEmptyReversedAndOpenEndedRanges(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir, 4)) {
            for (int i = 0; i < 8; i++) {
                store.put(k("k" + i), k("v" + i));
            }

            // Empty range: from equal to to excludes everything.
            assertEquals(List.of(), scanKeys(store, "k3", "k3"));
            // Reversed range: from after to yields nothing rather than throwing.
            assertEquals(List.of(), scanKeys(store, "k5", "k2"));
            // A range below every key, and one above every key.
            assertEquals(List.of(), scanKeys(store, "a", "b"));
            assertEquals(List.of(), scanKeys(store, "z", null));
            // Open on both ends is the whole store, in order.
            assertEquals(List.of("k0", "k1", "k2", "k3", "k4", "k5", "k6", "k7"),
                    scanKeys(store, null, null));
        }
    }

    @Test
    @Tag("slow") // the single most expensive test in the project
    void formsMultipleLevelsAndMatchesTheModel(@TempDir Path dir) {
        // A small flush threshold and a wide keyspace force many flushes, so level 0
        // fills and merges down repeatedly until several levels exist. Correctness is
        // checked against a TreeMap oracle once the levels have formed.
        Random rng = new Random(99);
        TreeMap<String, byte[]> oracle = new TreeMap<>();
        int keyspace = 500;

        try (StrataStore store = StrataStore.open(dir, 16)) {
            for (int i = 0; i < 12_000; i++) {
                String key = "key-" + String.format("%03d", rng.nextInt(keyspace));
                if (rng.nextInt(10) < 8) {
                    byte[] value = randomBytes(rng, 1 + rng.nextInt(40));
                    store.put(k(key), value);
                    oracle.put(key, value);
                } else {
                    store.delete(k(key));
                    oracle.remove(key);
                }
            }

            // Several levels below level 0 must have formed, not one flat pile.
            assertTrue(store.deepestLevel() >= 3,
                    "expected several levels, deepest was " + store.deepestLevel());

            for (int n = 0; n < keyspace; n++) {
                String key = "key-" + String.format("%03d", n);
                var got = store.get(k(key));
                if (oracle.containsKey(key)) {
                    assertArrayEquals(oracle.get(key), got.orElseThrow(), key);
                } else {
                    assertTrue(got.isEmpty(), key + " should be absent");
                }
            }
            assertEquals(oracle.size(), store.size());

            // And a full scan across every level still equals the oracle in order.
            List<String> want = new ArrayList<>(oracle.keySet());
            assertEquals(want, scanKeys(store, null, null));
            // A bounded scan crosses several levels too.
            assertScanMatches(store, oracle, "key-100", "key-400");
        }
    }

    @Test
    void tombstonesAreHonoredAcrossLevels(@TempDir Path dir) {
        // Write a key, push it down through the levels with unrelated traffic, then
        // delete it and push the tombstone down as well. The delete must win over the
        // value sitting in a deeper level, and must keep winning after a reopen.
        try (StrataStore store = StrataStore.open(dir, 8)) {
            store.put(k("target"), k("original"));
            for (int i = 0; i < 400; i++) {
                store.put(k("fill-" + String.format("%03d", i)), k("v" + i));
            }
            assertArrayEquals(k("original"), store.get(k("target")).orElseThrow());

            store.delete(k("target"));
            for (int i = 0; i < 400; i++) {
                store.put(k("more-" + String.format("%03d", i)), k("w" + i));
            }
            store.compact(); // force the tombstone all the way down

            assertTrue(store.get(k("target")).isEmpty(), "delete must shadow the deeper value");
            assertTrue(scanKeys(store, null, null).stream().noneMatch(s -> s.equals("target")));
        }
        try (StrataStore store = StrataStore.open(dir, 8)) {
            assertTrue(store.get(k("target")).isEmpty(), "the delete must survive a reopen");
        }
    }

    @Test
    void levelStructureSurvivesAReopen(@TempDir Path dir) {
        TreeMap<String, byte[]> oracle = new TreeMap<>();
        int[] counts;
        int deepest;

        try (StrataStore store = StrataStore.open(dir, 16)) {
            Random rng = new Random(5);
            for (int i = 0; i < 6_000; i++) {
                String key = "key-" + String.format("%03d", rng.nextInt(400));
                byte[] value = randomBytes(rng, 1 + rng.nextInt(20));
                store.put(k(key), value);
                oracle.put(key, value);
            }
            store.flush(); // drain the memtable so every live key sits in a table on disk

            deepest = store.deepestLevel();
            assertTrue(deepest >= 2, "expected several levels, deepest was " + deepest);
            counts = new int[deepest + 1];
            for (int l = 0; l <= deepest; l++) counts[l] = store.tableCount(l);
        }

        // A fresh open must rebuild the same levels from the file names alone.
        try (StrataStore store = StrataStore.open(dir, 16)) {
            assertEquals(deepest, store.deepestLevel(), "deepest level after reopen");
            for (int l = 0; l <= deepest; l++) {
                assertEquals(counts[l], store.tableCount(l), "table count at level " + l);
            }
            for (Map.Entry<String, byte[]> e : oracle.entrySet()) {
                assertArrayEquals(e.getValue(), store.get(k(e.getKey())).orElseThrow(), e.getKey());
            }
            assertEquals(oracle.size(), store.size());
        }
    }

    @Test
    void aCorruptedDataBlockIsCaughtByItsChecksum(@TempDir Path dir) throws IOException {
        // A hundred keys in one table span several blocks of sixteen keys each. Flush
        // them to disk, then flip a byte inside the first block. A read of a key in that
        // block must raise the checksum exception naming the table; a key in a later,
        // intact block must still read back its value.
        try (StrataStore store = StrataStore.open(dir, 1_000)) {
            for (int i = 0; i < 100; i++) {
                store.put(k("key-" + String.format("%03d", i)), k("val-" + i));
            }
            store.flush(); // every live key now sits in a single on-disk table
        }

        Path table = theSSTable(dir);
        // Offset 12 is inside the first block's payload (past its 8-byte header), so
        // flipping it changes an entry byte without touching the length prefix.
        flipByte(table, 12);

        // A fresh open starts with a cold cache, so the read genuinely goes to disk.
        try (StrataStore store = StrataStore.open(dir, 1_000)) {
            ChecksumException ex = assertThrows(ChecksumException.class,
                    () -> store.get(k("key-000")));
            assertTrue(ex.getMessage().contains(table.getFileName().toString()),
                    "the exception should name the table: " + ex.getMessage());

            // A key in a later block is untouched, so it reads back cleanly.
            assertArrayEquals(k("val-99"), store.get(k("key-099")).orElseThrow());
        }
    }

    @Test
    void aRepeatedHotReadIsServedFromTheBlockCache(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir, 1_000)) {
            for (int i = 0; i < 100; i++) {
                store.put(k("key-" + String.format("%03d", i)), k("val-" + i));
            }
            store.flush(); // push the keys to disk so a get reads a block

            long misses0 = store.blockCacheMisses();
            long hits0 = store.blockCacheHits();

            assertArrayEquals(k("val-42"), store.get(k("key-042")).orElseThrow());
            // First read of the key's block is a miss that loads it from disk.
            assertEquals(misses0 + 1, store.blockCacheMisses());
            assertEquals(hits0, store.blockCacheHits());

            assertArrayEquals(k("val-42"), store.get(k("key-042")).orElseThrow());
            // Second read of the same block is served from memory: a hit, no new miss.
            assertEquals(misses0 + 1, store.blockCacheMisses());
            assertEquals(hits0 + 1, store.blockCacheHits());
        }
    }

    @Test
    void matchesTheModelWithATinyBlockCacheThatEvicts(@TempDir Path dir) {
        // A cache of two blocks against a store spread over many tables forces constant
        // eviction. Correctness must not depend on the cache, so the oracle still holds.
        Random rng = new Random(23);
        TreeMap<String, byte[]> oracle = new TreeMap<>();
        int keyspace = 300;

        try (StrataStore store = StrataStore.open(dir, 32, 2)) {
            for (int i = 0; i < 8_000; i++) {
                String key = "key-" + String.format("%03d", rng.nextInt(keyspace));
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
                String key = "key-" + String.format("%03d", n);
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

    /** The single SSTable file in {@code dir}, for tests that poke at the bytes on disk. */
    private static Path theSSTable(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> tables = files.filter(p -> p.getFileName().toString().endsWith(".sst")).toList();
            assertEquals(1, tables.size(), "expected exactly one sstable");
            return tables.get(0);
        }
    }

    /** Flips every bit of the byte at {@code offset} in {@code file}, corrupting it. */
    private static void flipByte(Path file, long offset) throws IOException {
        try (FileChannel ch = FileChannel.open(file, READ, WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            ch.read(one, offset);
            one.flip();
            byte corrupted = (byte) ~one.get();
            ch.write(ByteBuffer.wrap(new byte[] {corrupted}), offset);
        }
    }

    /** Asserts a scan over {@code [from, to)} equals the oracle's own sub-range. */
    private static void assertScanMatches(StrataStore store, TreeMap<String, byte[]> oracle,
                                          String from, String to) {
        NavigableMap<String, byte[]> expected;
        if (from == null && to == null) {
            expected = oracle;
        } else if (from == null) {
            expected = oracle.headMap(to, false);
        } else if (to == null) {
            expected = oracle.tailMap(from, true);
        } else {
            expected = oracle.subMap(from, true, to, false);
        }

        List<Map.Entry<byte[], byte[]>> got = collect(store, from, to);
        assertEquals(expected.size(), got.size(), "count for [" + from + "," + to + ")");
        List<Map.Entry<String, byte[]>> want = new ArrayList<>(expected.entrySet());
        for (int i = 0; i < want.size(); i++) {
            assertEquals(want.get(i).getKey(), new String(got.get(i).getKey(), UTF_8), "key at " + i);
            assertArrayEquals(want.get(i).getValue(), got.get(i).getValue(), "value at " + i);
        }
    }

    private static List<Map.Entry<byte[], byte[]>> collect(StrataStore store, String from, String to) {
        try (Stream<Map.Entry<byte[], byte[]>> s =
                     store.scan(from == null ? null : k(from), to == null ? null : k(to))) {
            return s.toList();
        }
    }

    private static List<String> scanKeys(StrataStore store, String from, String to) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<byte[], byte[]> e : collect(store, from, to)) {
            keys.add(new String(e.getKey(), UTF_8));
        }
        return keys;
    }

    private static byte[] randomBytes(Random rng, int len) {
        byte[] b = new byte[len];
        rng.nextBytes(b);
        return b;
    }
}

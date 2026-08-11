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

    private static byte[] randomBytes(Random rng, int len) {
        byte[] b = new byte[len];
        rng.nextBytes(b);
        return b;
    }
}

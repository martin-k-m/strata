package dev.martinkm.strata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link WriteBatch} is one unit of durability: one log record, one fsync,
 * all of it or none of it after a crash. These tests pin the all-or-nothing
 * half by tearing and corrupting the log inside the batch's own bytes, which is
 * where a per-record log would have recovered a prefix of the batch.
 */
class StrataWriteBatchTest {

    private static byte[] k(String s) {
        return s.getBytes(UTF_8);
    }

    @Test
    void appliesEveryOperationInOrder(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir)) {
            store.put(k("gone"), k("old"));
            WriteBatch batch = new WriteBatch()
                    .put(k("a"), k("1"))
                    .put(k("b"), k("2"))
                    .delete(k("gone"))
                    .put(k("a"), k("1-again")); // a later write of the same key wins
            assertEquals(4, batch.size());
            store.write(batch);

            assertArrayEquals(k("1-again"), store.get(k("a")).orElseThrow());
            assertArrayEquals(k("2"), store.get(k("b")).orElseThrow());
            assertTrue(store.get(k("gone")).isEmpty());
            assertEquals(2, store.size());
        }
    }

    @Test
    void batchCopiesItsInputsAndCanBeReused(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir)) {
            byte[] key = k("k");
            byte[] value = k("v");
            WriteBatch batch = new WriteBatch().put(key, value);
            value[0] = '!';
            store.write(batch);
            assertArrayEquals(k("v"), store.get(k("k")).orElseThrow());

            batch.clear();
            assertTrue(batch.isEmpty());
            store.write(batch); // empty: nothing happens
            batch.put(k("k2"), k("v2"));
            store.write(batch);
            assertArrayEquals(k("v2"), store.get(k("k2")).orElseThrow());
        }
    }

    @Test
    void emptyBatchWritesNothingToTheLog(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir)) {
            long before = store.ioStats().wal();
            store.write(new WriteBatch());
            assertEquals(before, store.ioStats().wal());
        }
    }

    @Test
    void batchSurvivesAReopen(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir)) {
            store.put(k("x"), k("doomed"));
            store.write(new WriteBatch().put(k("a"), k("1")).delete(k("x")).put(k("b"), k("2")));
        }
        try (StrataStore store = StrataStore.open(dir)) {
            assertArrayEquals(k("1"), store.get(k("a")).orElseThrow());
            assertArrayEquals(k("2"), store.get(k("b")).orElseThrow());
            assertTrue(store.get(k("x")).isEmpty());
            assertEquals(2, store.size());
        }
    }

    @Test
    void batchCrossingTheFlushThresholdLandsOnDiskWhole(@TempDir Path dir) throws IOException {
        try (StrataStore store = StrataStore.open(dir, 8)) {
            WriteBatch batch = new WriteBatch();
            for (int i = 0; i < 20; i++) batch.put(k("key-" + i), k("value-" + i));
            store.write(batch);
            assertTrue(Files.list(dir).anyMatch(p -> p.getFileName().toString().endsWith(".sst")),
                    "a batch larger than the flush threshold should have been flushed");
            for (int i = 0; i < 20; i++) {
                assertArrayEquals(k("value-" + i), store.get(k("key-" + i)).orElseThrow(), "key-" + i);
            }
        }
        try (StrataStore store = StrataStore.open(dir, 8)) {
            assertEquals(20, store.size());
        }
    }

    @Test
    void batchIsAppliedByTheMemoryBudget(@TempDir Path dir) {
        try (StrataStore store = StrataStore.openWithMemoryBudget(dir, 1_000_000, 200)) {
            store.write(new WriteBatch().put(k("a"), new byte[150]).put(k("b"), new byte[150]));
            // Two entries of 150 bytes plus overhead pass 200, so the batch flushed
            // and the memtable is empty again.
            assertEquals(0, store.memtableBytes());
            assertEquals(2, store.size());
        }
    }

    /**
     * The crash test. A batch that is torn at any byte inside its own log record
     * recovers as if it had never been written: the puts before it are intact and
     * none of its operations is visible. A per-record log would recover the first
     * few operations of the batch here, which is exactly what a batch promises not
     * to do.
     */
    @Test
    void aBatchTornAtAnyByteRecoversAllOrNothing(@TempDir Path dir) throws IOException {
        Path wal = dir.resolve("wal.log");
        try (StrataStore store = StrataStore.open(dir)) {
            store.put(k("before"), k("kept"));
            store.put(k("victim"), k("old"));
        }
        long beforeBatch = Files.size(wal);
        try (StrataStore store = StrataStore.open(dir)) {
            store.write(new WriteBatch()
                    .put(k("b1"), k("v1"))
                    .delete(k("victim"))
                    .put(k("b2"), k("v2"))
                    .put(k("b3"), k("v3")));
        }
        byte[] full = Files.readAllBytes(wal);
        assertTrue(full.length > beforeBatch, "the batch should have appended one record");

        for (long len = beforeBatch; len < full.length; len++) {
            Files.write(wal, Arrays.copyOf(full, (int) len));
            try (StrataStore store = StrataStore.open(dir)) {
                String at = "torn at byte " + len + " of " + full.length;
                assertArrayEquals(k("kept"), store.get(k("before")).orElseThrow(), at);
                assertArrayEquals(k("old"), store.get(k("victim")).orElseThrow(), at + ": the delete leaked");
                assertTrue(store.get(k("b1")).isEmpty(), at + ": b1 leaked");
                assertTrue(store.get(k("b2")).isEmpty(), at + ": b2 leaked");
                assertTrue(store.get(k("b3")).isEmpty(), at + ": b3 leaked");
                assertEquals(2, store.size(), at);
            }
        }

        Files.write(wal, full);
        try (StrataStore store = StrataStore.open(dir)) {
            assertEquals(List.of("b1", "b2", "b3", "before"), keys(store));
            assertTrue(store.get(k("victim")).isEmpty());
        }
    }

    /** A flipped byte anywhere inside the batch's record drops the whole batch. */
    @Test
    void aCorruptedByteInsideABatchDropsTheWholeBatch(@TempDir Path dir) throws IOException {
        Path wal = dir.resolve("wal.log");
        try (StrataStore store = StrataStore.open(dir)) {
            store.put(k("before"), k("kept"));
        }
        long beforeBatch = Files.size(wal);
        try (StrataStore store = StrataStore.open(dir)) {
            store.write(new WriteBatch().put(k("b1"), k("v1")).put(k("b2"), k("v2")));
        }
        byte[] full = Files.readAllBytes(wal);

        for (long i = beforeBatch; i < full.length; i++) {
            byte[] corrupt = full.clone();
            corrupt[(int) i] = (byte) ~corrupt[(int) i];
            Files.write(wal, corrupt);
            try (StrataStore store = StrataStore.open(dir)) {
                String at = "flipped byte " + i;
                assertArrayEquals(k("kept"), store.get(k("before")).orElseThrow(), at);
                assertEquals(List.of("before"), keys(store), at);
            }
        }
    }

    private static List<String> keys(StrataStore store) {
        try (Stream<Map.Entry<byte[], byte[]>> s = store.scan(null, null)) {
            return s.map(e -> new String(e.getKey(), UTF_8)).toList();
        }
    }
}

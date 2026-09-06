package dev.martinkm.strata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The byte-budgeted memtable.
 *
 * README.md listed this under "Not done yet": "The flush threshold is an entry
 * count, so the store does not actually know how much memory the memtable is
 * using." A thousand two-byte values and a thousand one-megabyte values are the
 * same number of entries and are not the same amount of memory, so an entry
 * count bounds the wrong thing for any workload whose values vary in size.
 */
class StrataMemoryBudgetTest {

    private static byte[] k(String s) {
        return s.getBytes(UTF_8);
    }

    private static byte[] value(int bytes) {
        byte[] v = new byte[bytes];
        java.util.Arrays.fill(v, (byte) 'x');
        return v;
    }

    private static long tableCount(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sst")).count();
        }
    }

    @Test
    void largeValuesFlushOnBytesRatherThanWaitingForTheEntryCount(@TempDir Path dir) throws Exception {
        // An entry threshold high enough that it can never be the thing that
        // fires, so anything that flushes here flushed on the byte budget.
        try (StrataStore store = StrataStore.openWithMemoryBudget(dir, 1_000_000, 64 * 1024)) {
            for (int i = 0; i < 8; i++) {
                store.put(k("big" + i), value(16 * 1024));
            }
            assertTrue(tableCount(dir) > 0,
                    "128 KiB of values under a 64 KiB budget never reached disk: the budget did not fire");
            assertTrue(store.memtableBytes() < 64 * 1024,
                    "the memtable is still over budget after flushing: " + store.memtableBytes());
            // The data is still all there, which is the part a flush must not break.
            for (int i = 0; i < 8; i++) {
                assertTrue(store.get(k("big" + i)).isPresent(), "big" + i + " was lost across the flush");
            }
        }
    }

    @Test
    void theSameEntryCountOfSmallValuesDoesNotFlush(@TempDir Path dir) throws Exception {
        // The control for the test above. Same number of entries, tiny values,
        // same budget: nothing should reach disk, which is what shows the budget
        // is counting bytes rather than entries.
        try (StrataStore store = StrataStore.openWithMemoryBudget(dir, 1_000_000, 64 * 1024)) {
            for (int i = 0; i < 8; i++) {
                store.put(k("small" + i), value(4));
            }
            assertEquals(0, tableCount(dir),
                    "eight tiny values flushed under a 64 KiB budget: the budget is counting the wrong thing");
        }
    }

    @Test
    void overwritingOneKeyDoesNotGrowTheEstimate(@TempDir Path dir) {
        // The reason the count is kept by delta rather than by addition. A
        // workload that rewrites one key holds one entry however many times it
        // writes, and a counter that added would flush forever.
        try (StrataStore store = StrataStore.openWithMemoryBudget(dir, 1_000_000, 1024 * 1024)) {
            store.put(k("hot"), value(100));
            long afterFirst = store.memtableBytes();
            for (int i = 0; i < 500; i++) {
                store.put(k("hot"), value(100));
            }
            assertEquals(afterFirst, store.memtableBytes(),
                    "500 overwrites of one key changed the estimate, so it is counting traffic not contents");
            assertEquals(1, storeEntryCount(store), "the memtable holds more than the one key written");
        }
    }

    @Test
    void aLargerValueForAnExistingKeyIsChargedByTheDifference(@TempDir Path dir) {
        try (StrataStore store = StrataStore.openWithMemoryBudget(dir, 1_000_000, 1024 * 1024)) {
            store.put(k("k"), value(10));
            long small = store.memtableBytes();
            store.put(k("k"), value(1010));
            assertEquals(small + 1000, store.memtableBytes(),
                    "growing a value by 1000 bytes did not move the estimate by 1000");
            store.put(k("k"), value(10));
            assertEquals(small, store.memtableBytes(), "shrinking it back did not return the estimate");
        }
    }

    @Test
    void aReplayedMemtableIsCharged(@TempDir Path dir) throws Exception {
        // Without this the estimate reads zero on a store that has just recovered
        // a full memtable, and the budget would not fire until enough new writes
        // had reached it on their own.
        long budget = 1024 * 1024;
        long beforeCrash;
        try (StrataStore store = StrataStore.openWithMemoryBudget(dir, 1_000_000, budget)) {
            for (int i = 0; i < 20; i++) {
                store.put(k("k" + i), value(256));
            }
            beforeCrash = store.memtableBytes();
            assertTrue(beforeCrash > 0, "nothing was charged before the reopen");
        }
        try (StrataStore reopened = StrataStore.openWithMemoryBudget(dir, 1_000_000, budget)) {
            assertEquals(beforeCrash, reopened.memtableBytes(),
                    "a replayed memtable was not charged, so the budget cannot see it");
        }
    }

    @Test
    void aTombstoneIsChargedLikeAnEntry(@TempDir Path dir) {
        try (StrataStore store = StrataStore.openWithMemoryBudget(dir, 1_000_000, 1024 * 1024)) {
            long empty = store.memtableBytes();
            store.delete(k("never-existed"));
            assertTrue(store.memtableBytes() > empty,
                    "a tombstone cost nothing, but it occupies a memtable entry until a flush");
        }
    }

    @Test
    void theEntryThresholdStillFiresWhenItComesFirst(@TempDir Path dir) throws Exception {
        // Both bounds are live; whichever is reached first flushes. A store given
        // a small entry threshold and a huge byte budget behaves as it always did.
        try (StrataStore store = StrataStore.openWithMemoryBudget(dir, 4, 1024L * 1024 * 1024)) {
            for (int i = 0; i < 8; i++) {
                store.put(k("k" + i), value(4));
            }
            assertTrue(tableCount(dir) > 0, "the entry threshold stopped firing once a byte budget existed");
        }
    }

    @Test
    void aBudgetMustBePositive(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> StrataStore.openWithMemoryBudget(dir, 100, 0));
        assertThrows(IllegalArgumentException.class,
                () -> StrataStore.openWithMemoryBudget(dir, 100, -1));
    }

    /** How many keys the store currently answers for, via a full scan. */
    private static long storeEntryCount(StrataStore store) {
        try (Stream<java.util.Map.Entry<byte[], byte[]>> s = store.scan(null, null)) {
            return s.count();
        }
    }
}

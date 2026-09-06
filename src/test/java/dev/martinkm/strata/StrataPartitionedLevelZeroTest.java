package dev.martinkm.strata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Partitioned level-0 tables.
 *
 * README.md listed this under "Not done yet": "Level-0 tables tend to span the
 * whole key range, so an L0-into-L1 merge still rewrites much of level 1. A real
 * engine limits that with partitioned level-0 tables or a sub-compaction split."
 *
 * A flush now writes several tables split on key instead of one, and the
 * compactor takes the group of level-0 tables whose ranges connect rather than
 * all of them. The write-amplification claim is the point of the change, but the
 * correctness claim is the one that would hurt if it were wrong, so both are
 * here and the correctness ones come first.
 */
class StrataPartitionedLevelZeroTest {

    private static byte[] k(String s) {
        return s.getBytes(UTF_8);
    }

    private static long tableCount(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sst")).count();
        }
    }

    /**
     * The property a partitioned flush could break: a key written twice must read
     * back as the newer value, whichever tables the two versions landed in.
     *
     * <p>This is the failure the overlap closure exists to prevent. A table is
     * consumed whole, so if a compaction took an older version down into level 1
     * while a newer one stayed in level 0, a read would meet the older one first,
     * at the shallower level, and answer with it.
     */
    @Test
    void aRewrittenKeyReadsBackAsTheNewerValueAcrossFlushes(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir, 8)) {
            // Two passes over the same keys, so every key has an older version in
            // an earlier flush and a newer one in a later flush, and the flushes
            // are partitioned.
            for (int i = 0; i < 200; i++) {
                store.put(k(String.format("k%04d", i)), k("old"));
            }
            for (int i = 0; i < 200; i++) {
                store.put(k(String.format("k%04d", i)), k("new"));
            }
            store.flush();
            store.compact();
            for (int i = 0; i < 200; i++) {
                String key = String.format("k%04d", i);
                assertEquals("new", new String(store.get(k(key)).orElseThrow(), UTF_8),
                        key + " read back as the older value: a version was merged down past a newer one");
            }
        }
    }

    @Test
    void aDeletedKeyStaysDeletedAcrossPartitionedFlushes(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir, 8)) {
            for (int i = 0; i < 120; i++) {
                store.put(k(String.format("k%04d", i)), k("v"));
            }
            for (int i = 0; i < 120; i += 2) {
                store.delete(k(String.format("k%04d", i)));
            }
            store.flush();
            store.compact();
            for (int i = 0; i < 120; i++) {
                String key = String.format("k%04d", i);
                if (i % 2 == 0) {
                    assertTrue(store.get(k(key)).isEmpty(), key + " came back from the dead");
                } else {
                    assertEquals("v", new String(store.get(k(key)).orElseThrow(), UTF_8), key + " was lost");
                }
            }
        }
    }

    @Test
    void aRandomWorkloadStillMatchesAnOracle(@TempDir Path dir) {
        // The blunt instrument, because the closure is the kind of code that is
        // wrong in a case nobody thought to name.
        Random rng = new Random(4242);
        TreeMap<String, String> oracle = new TreeMap<>();
        try (StrataStore store = StrataStore.open(dir, 16)) {
            for (int i = 0; i < 4000; i++) {
                String key = String.format("k%04d", rng.nextInt(400));
                if (rng.nextInt(4) == 0) {
                    store.delete(k(key));
                    oracle.remove(key);
                } else {
                    String v = "v" + i;
                    store.put(k(key), k(v));
                    oracle.put(key, v);
                }
            }
            store.flush();
            store.compact();

            List<String> scanned = new ArrayList<>();
            try (Stream<Map.Entry<byte[], byte[]>> s = store.scan(null, null)) {
                s.forEach(e -> scanned.add(new String(e.getKey(), UTF_8) + "=" + new String(e.getValue(), UTF_8)));
            }
            List<String> expected = new ArrayList<>();
            oracle.forEach((key, v) -> expected.add(key + "=" + v));
            assertEquals(expected, scanned, "the store and the oracle disagree after partitioned compaction");

            for (Map.Entry<String, String> e : oracle.entrySet()) {
                assertArrayEquals(k(e.getValue()), store.get(k(e.getKey())).orElseThrow(),
                        e.getKey() + " does not match the oracle");
            }
        }
    }

    @Test
    void aFlushWritesSeveralTablesWhenTheMemtableExceedsATablesWorth(@TempDir Path dir) throws Exception {
        // The mechanism, asserted directly: one flush, several level-0 tables.
        try (StrataStore store = StrataStore.open(dir, 1_000_000)) {
            for (int i = 0; i < 400; i++) {
                store.put(k(String.format("k%04d", i)), k("v"));
            }
            assertEquals(0, tableCount(dir), "something flushed before the test asked it to");
            store.flush();
            assertTrue(tableCount(dir) > 1,
                    "a 400-entry memtable was written as a single table: level 0 is not partitioned");
        }
    }

    /**
     * The write-amplification claim, measured rather than asserted in prose.
     *
     * <p>A workload that keeps rewriting one narrow slice of the key space should
     * not rewrite the whole of level 1 every time it compacts. Bytes written by
     * compaction is the counter that says whether it does.
     */
    @Test
    void rewritingOneSliceDoesNotRewriteTheWholeOfLevelOne(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir, 64)) {
            // Fill a wide key space and settle it into the deeper levels.
            for (int i = 0; i < 4000; i++) {
                store.put(k(String.format("k%05d", i)), k("base"));
            }
            store.flush();
            store.compact();
            long settled = store.ioStats().compaction();

            // Now churn a narrow slice, repeatedly, and compact.
            for (int round = 0; round < 8; round++) {
                for (int i = 0; i < 200; i++) {
                    store.put(k(String.format("k%05d", i)), k("round" + round));
                }
                store.flush();
            }
            store.compact();
            long afterChurn = store.ioStats().compaction() - settled;

            // The slice is 5% of the key space. Rewriting the whole of level 1
            // eight times would cost several times the settled corpus; touching
            // the slice's own tables costs a fraction of it. The bound is loose on
            // purpose, because the exact figure depends on level geometry, but it
            // is far tighter than "rewrote everything".
            assertTrue(afterChurn < settled,
                    "churning 5% of the keys rewrote more than the initial build: " + afterChurn
                            + " bytes against " + settled);

            for (int i = 0; i < 200; i++) {
                assertEquals("round7", new String(store.get(k(String.format("k%05d", i))).orElseThrow(), UTF_8),
                        "the churned slice lost its newest value");
            }
            assertEquals("base", new String(store.get(k("k03999")).orElseThrow(), UTF_8),
                    "a key outside the churned slice was disturbed");
        }
    }
}

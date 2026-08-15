package dev.martinkm.strata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The concurrency the store advertises, exercised rather than described.
 *
 * <p>{@code StrataStore} is single-writer and lock-free for readers: "Reads run
 * alongside a writer without locking, which is why a flush publishes the new
 * table before it clears the memtable, so a key is never briefly absent from
 * both." The durability suite covers the other half of the story, what a crash
 * leaves behind, and every one of its tests is single-threaded. Nothing until
 * now ran a reader at the same time as the writer, so the claim that reads are
 * safe next to a flush and a compaction was documentation rather than a tested
 * property.
 *
 * <p>The moments that can break it are the two that swap a reader's world out
 * from under it: a flush, which publishes a new table and then replaces the
 * memtable, and a compaction, which replaces the level structure and then
 * retires the tables it consumed. Both are driven hard here by a tiny flush
 * threshold, so a short run covers many of each.
 *
 * <p>Tagged slow: each test runs threads against a wall-clock budget, so the
 * class costs minutes. It runs in the nightly workflow, not on every push.
 */
@Tag("slow")
class StrataConcurrencyPropertyTest {

    /** Long enough to cover many flushes and compactions, short enough to sit in a suite. */
    private static final long DURATION_MILLIS = 4_000;

    private static final int READERS = 4;

    private static byte[] key(int i) {
        return String.format("key-%08d", i).getBytes(StandardCharsets.UTF_8);
    }

    /** Self-describing, so a value from the wrong key is caught rather than merely absent. */
    private static byte[] value(int i) {
        return ("value-for-" + i + "-" + "x".repeat(40)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A key that was written before a read started must be found by that read,
     * with its own value, whatever the writer is doing at the time.
     *
     * <p>The writer publishes how far it has got only after {@code put} returns,
     * so every key a reader picks was acknowledged before the read began. There
     * is no window in which such a key is legitimately missing: a flush is
     * ordered to keep it in the memtable or the new table at every instant, and a
     * compaction only ever rewrites it into another table. Absent means the store
     * lost it, and a wrong value means a reader was served a torn or stale one.
     */
    @Test
    void aKeyWrittenBeforeAReadIsAlwaysFoundWhileFlushingAndCompacting(@TempDir Path dir)
            throws Exception {

        // Small enough that the writer flushes every few hundred keys and level 0
        // reaches its compaction trigger repeatedly.
        try (StrataStore store = StrataStore.open(dir, 64)) {
            AtomicInteger written = new AtomicInteger(-1);
            AtomicBoolean stop = new AtomicBoolean();
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            AtomicLong reads = new AtomicLong();
            CountDownLatch ready = new CountDownLatch(READERS + 1);

            ExecutorService pool = Executors.newFixedThreadPool(READERS + 1);
            try {
                pool.submit(
                        () -> {
                            ready.countDown();
                            ready.await();
                            try {
                                for (int i = 0; !stop.get(); i++) {
                                    store.put(key(i), value(i));
                                    // Only now is key i safe to ask for.
                                    written.set(i);
                                }
                            } catch (Throwable t) {
                                failures.add(t);
                            }
                            return null;
                        });

                for (int r = 0; r < READERS; r++) {
                    pool.submit(
                            () -> {
                                ready.countDown();
                                ready.await();
                                try {
                                    while (!stop.get()) {
                                        int highest = written.get();
                                        if (highest < 0) {
                                            continue;
                                        }
                                        int i = ThreadLocalRandom.current().nextInt(highest + 1);

                                        Optional<byte[]> found = store.get(key(i));

                                        if (found.isEmpty()) {
                                            throw new AssertionError(
                                                    "key " + i + " was written but not found");
                                        }
                                        assertArrayEquals(
                                                value(i), found.get(), "wrong value for key " + i);
                                        reads.incrementAndGet();
                                    }
                                } catch (Throwable t) {
                                    failures.add(t);
                                    stop.set(true);
                                }
                                return null;
                            });
                }

                Thread.sleep(DURATION_MILLIS);
                stop.set(true);
                pool.shutdown();
                assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not stop");
            } finally {
                pool.shutdownNow();
            }

            report(failures);
            // A test that silently did nothing would pass, so say what it covered.
            assertTrue(written.get() > 1_000, "the writer barely ran: " + written.get() + " keys");
            assertTrue(reads.get() > 1_000, "the readers barely ran: " + reads.get() + " reads");
            assertTrue(
                    store.populatedLevelCount() > 1,
                    "no compaction happened, so the interesting window was never open");
        }
    }

    /**
     * The same for the other read path. A scan holds its snapshot of the
     * memtable and the tables for as long as the stream is being consumed, which
     * is far longer than a {@code get} holds one, so it is the wider window on
     * the same race.
     *
     * <p>What a scan owes the caller is one consistent, ordered pass: keys
     * strictly increasing, each value the one belonging to its key. It is not
     * owed the writer's latest keys, since it snapshotted before they arrived.
     */
    @Test
    void aScanRunsToCompletionAlongsideAWriter(@TempDir Path dir) throws Exception {
        try (StrataStore store = StrataStore.open(dir, 64)) {
            // Seed, so the first scans have tables to walk rather than an empty store.
            for (int i = 0; i < 2_000; i++) {
                store.put(key(i), value(i));
            }

            AtomicBoolean stop = new AtomicBoolean();
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            AtomicLong scans = new AtomicLong();
            AtomicLong rows = new AtomicLong();
            CountDownLatch ready = new CountDownLatch(READERS + 1);

            ExecutorService pool = Executors.newFixedThreadPool(READERS + 1);
            try {
                pool.submit(
                        () -> {
                            ready.countDown();
                            ready.await();
                            try {
                                for (int i = 2_000; !stop.get(); i++) {
                                    store.put(key(i), value(i));
                                }
                            } catch (Throwable t) {
                                failures.add(t);
                            }
                            return null;
                        });

                for (int r = 0; r < READERS; r++) {
                    pool.submit(
                            () -> {
                                ready.countDown();
                                ready.await();
                                try {
                                    while (!stop.get()) {
                                        // The keys are fixed-width ASCII, so their
                                        // byte order and their string order agree.
                                        String previous = null;
                                        try (Stream<Map.Entry<byte[], byte[]>> scan =
                                                store.scan(key(0), null)) {
                                            for (Map.Entry<byte[], byte[]> entry :
                                                    (Iterable<Map.Entry<byte[], byte[]>>)
                                                            scan::iterator) {
                                                String current =
                                                        new String(
                                                                entry.getKey(),
                                                                StandardCharsets.UTF_8);
                                                if (previous != null) {
                                                    assertTrue(
                                                            previous.compareTo(current) < 0,
                                                            "scan went backwards or repeated a key: "
                                                                    + previous
                                                                    + " then "
                                                                    + current);
                                                }
                                                previous = current;
                                                assertArrayEquals(
                                                        valueFor(entry.getKey()),
                                                        entry.getValue(),
                                                        "a key was paired with another key's value");
                                                rows.incrementAndGet();
                                            }
                                        }
                                        scans.incrementAndGet();
                                    }
                                } catch (Throwable t) {
                                    failures.add(t);
                                    stop.set(true);
                                }
                                return null;
                            });
                }

                Thread.sleep(DURATION_MILLIS);
                stop.set(true);
                pool.shutdown();
                assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not stop");
            } finally {
                pool.shutdownNow();
            }

            report(failures);
            assertTrue(scans.get() > 0, "no scan completed");
            assertTrue(rows.get() > 10_000, "the scans barely ran: " + rows.get() + " rows");
        }
    }

    /**
     * Deferring a delete must not mean forgetting it.
     *
     * <p>A compacted table's file now outlives the compaction if a reader is
     * still inside it, which is the fix for the race above and also the way to
     * leak disk: a reference that is never given back is a file that is never
     * removed. So this holds a table open across a compaction, checks the store
     * kept the file rather than pulling it out from under the reader, and checks
     * it is gone once the reader lets go.
     */
    @Test
    void aTableHeldOpenAcrossACompactionIsDeletedWhenTheReaderLetsGo(@TempDir Path dir)
            throws Exception {

        try (StrataStore store = StrataStore.open(dir, 64)) {
            for (int i = 0; i < 4_000; i++) {
                store.put(key(i), value(i));
            }
            store.flush();
            assertTrue(sstableCount(dir) > 1, "expected several tables to compact");

            // Open a scan and consume one row, so it is holding its tables but is
            // nowhere near finished with them.
            Stream<Map.Entry<byte[], byte[]>> scan = store.scan(null, null);
            java.util.Iterator<Map.Entry<byte[], byte[]>> rows = scan.iterator();
            assertTrue(rows.hasNext());
            rows.next();

            store.compact();

            // The reader is still inside tables the compaction consumed, so their
            // files must still be there, and still readable.
            int whileHeld = sstableCount(dir);
            long counted = 1;
            while (rows.hasNext()) {
                rows.next();
                counted++;
            }
            assertEquals(4_000, counted, "the held scan did not return the whole store");

            scan.close();

            int afterRelease = sstableCount(dir);
            assertTrue(
                    afterRelease < whileHeld,
                    "no file was reclaimed: " + whileHeld + " before, " + afterRelease + " after");
            assertEquals(
                    liveTableCount(store),
                    afterRelease,
                    "the files on disk no longer match the tables the store is holding");
        }
    }

    private static int sstableCount(Path dir) throws java.io.IOException {
        try (Stream<Path> files = java.nio.file.Files.list(dir)) {
            return (int) files.filter(p -> p.getFileName().toString().endsWith(".sst")).count();
        }
    }

    private static int liveTableCount(StrataStore store) {
        int total = 0;
        for (int level = 0; level <= store.deepestLevel(); level++) {
            total += store.tableCount(level);
        }
        return total;
    }

    /** The value belonging to an encoded key, so a scan can check the pairing. */
    private static byte[] valueFor(byte[] encodedKey) {
        String text = new String(encodedKey, StandardCharsets.UTF_8);
        return value(Integer.parseInt(text.substring("key-".length())));
    }

    /**
     * A reader must see a deleted key as gone and a rewritten one as new, rather
     * than as whatever an older table still holds for it. Compaction is what
     * retires those older copies, so doing this while it runs is the point.
     */
    @Test
    void aRewriteAndADeleteAreVisibleToAConcurrentReader(@TempDir Path dir) throws Exception {
        int keys = 500;
        try (StrataStore store = StrataStore.open(dir, 64)) {
            for (int i = 0; i < keys; i++) {
                store.put(key(i), value(i));
            }

            AtomicBoolean stop = new AtomicBoolean();
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            // Generation n means: every key has been rewritten to n, or deleted if
            // odd-numbered and n says so. Published only after the sweep finishes.
            AtomicInteger generation = new AtomicInteger(0);
            AtomicLong reads = new AtomicLong();
            CountDownLatch ready = new CountDownLatch(READERS + 1);

            ExecutorService pool = Executors.newFixedThreadPool(READERS + 1);
            try {
                pool.submit(
                        () -> {
                            ready.countDown();
                            ready.await();
                            try {
                                for (int g = 1; !stop.get(); g++) {
                                    for (int i = 0; i < keys; i++) {
                                        if (i % 2 == 1) {
                                            store.delete(key(i));
                                        } else {
                                            store.put(key(i), generationValue(i, g));
                                        }
                                    }
                                    generation.set(g);
                                    // Put the odd keys back, so the next sweep has
                                    // something to delete and the reader has a
                                    // generation where they exist again.
                                    for (int i = 1; i < keys; i += 2) {
                                        store.put(key(i), generationValue(i, g));
                                    }
                                }
                            } catch (Throwable t) {
                                failures.add(t);
                            }
                            return null;
                        });

                for (int r = 0; r < READERS; r++) {
                    pool.submit(
                            () -> {
                                ready.countDown();
                                ready.await();
                                try {
                                    while (!stop.get()) {
                                        int g = generation.get();
                                        if (g == 0) {
                                            continue;
                                        }
                                        // An even key was rewritten to generation g
                                        // before g was published, and nothing removes
                                        // it, so it must be present and at least g.
                                        int i = ThreadLocalRandom.current().nextInt(keys / 2) * 2;

                                        Optional<byte[]> found = store.get(key(i));

                                        if (found.isEmpty()) {
                                            throw new AssertionError(
                                                    "even key " + i + " vanished at generation " + g);
                                        }
                                        int seen = generationOf(found.get());
                                        assertTrue(
                                                seen >= g,
                                                "key "
                                                        + i
                                                        + " served generation "
                                                        + seen
                                                        + ", older than the published "
                                                        + g);
                                        reads.incrementAndGet();
                                    }
                                } catch (Throwable t) {
                                    failures.add(t);
                                    stop.set(true);
                                }
                                return null;
                            });
                }

                Thread.sleep(DURATION_MILLIS);
                stop.set(true);
                pool.shutdown();
                assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not stop");
            } finally {
                pool.shutdownNow();
            }

            report(failures);
            assertTrue(generation.get() > 1, "the writer barely ran");
            assertTrue(reads.get() > 1_000, "the readers barely ran: " + reads.get() + " reads");

            // And the store is still correct once everything has stopped.
            for (int i = 0; i < keys; i += 2) {
                assertTrue(store.get(key(i)).isPresent(), "even key " + i + " is missing at the end");
            }
        }
    }

    private static byte[] generationValue(int i, int generation) {
        return ("gen:" + generation + ":key:" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static int generationOf(byte[] value) {
        String text = new String(value, StandardCharsets.UTF_8);
        return Integer.parseInt(text.substring("gen:".length(), text.indexOf(":key:")));
    }

    /** Fails with the first worker failure, listing the rest. */
    private static void report(List<Throwable> failures) {
        if (failures.isEmpty()) {
            return;
        }
        List<String> described = new ArrayList<>();
        for (Throwable t : failures) {
            described.add(t.getClass().getName() + ": " + t.getMessage());
        }
        assertEquals(List.of(), described, "a worker failed");
        fail("unreachable");
    }
}

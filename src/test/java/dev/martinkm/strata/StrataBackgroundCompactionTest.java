package dev.martinkm.strata;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compaction moved off the writer's thread, and the three things that breaks if it
 * is done carelessly.
 *
 * <p>The reader properties are already covered by {@link StrataConcurrencyPropertyTest},
 * which now runs against the background compactor rather than against a writer
 * compacting inline, and the atomicity of the file-set swap by
 * {@link StrataCrashConsistencyTest}. What is new here is that the merge no longer
 * happens on the calling thread, that the writer is stalled rather than allowed to
 * pile level 0 up without limit, and that a crash taken at an arbitrary instant of a
 * background compaction still recovers every acknowledged key.
 */
class StrataBackgroundCompactionTest {

    private static byte[] key(int i) {
        return ("key-" + String.format("%06d", i)).getBytes(UTF_8);
    }

    private static byte[] value(int i) {
        return ("value-" + i + "-" + "x".repeat(30)).getBytes(UTF_8);
    }

    /**
     * The claim this change exists to make: the merge runs somewhere else.
     *
     * <p>Asserted structurally rather than by timing, because a latency measurement
     * makes a flaky test and the benchmark harness is where the number belongs. The
     * store records which thread installed the last compaction, and it must not be
     * the one that called {@code put}.
     */
    @Test
    void compactionRunsOnItsOwnThreadAndNotTheWriters(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir, 16)) {
            for (int i = 0; i < 2_000; i++) store.put(key(i), value(i));
            store.awaitCompactionIdle();

            assertTrue(store.compactionsCompleted() > 0, "no compaction ran at all");
            String ran = store.lastCompactionThread();
            assertNotEquals(Thread.currentThread().getName(), ran,
                    "the compaction ran on the writer's thread");
            assertTrue(ran.startsWith("strata-compactor-"),
                    "compaction ran on an unexpected thread: " + ran);
        }
    }

    /**
     * A writer that outruns the compactor is stalled, and level 0 stays bounded.
     *
     * <p>Two things are needed to make the writer the faster of the two, and both say
     * something about when this policy matters.
     *
     * <p>The keys are scattered rather than sequential. Sequential keys give level 0
     * tables that barely overlap what is below them, so a merge rewrites almost
     * nothing and the compactor keeps up easily. Scattered keys give level 0 tables
     * that each span the whole key range, so every merge rewrites most of level 1.
     * That is the same effect the write-amplification table in BENCHMARKS.md measures,
     * seen from the latency side.
     *
     * <p>The store is the unsynced one, which is the only test here that uses it. A
     * durable writer waits on the disk for every put and simply cannot outrun a
     * compactor that does not, so the backlog never forms and the policy never fires.
     * The store under test is not durable, and that is the point: back-pressure is
     * about the compactor keeping up with the writer, and the fsync otherwise hides
     * whether it does.
     *
     * <p>The bound is checked from the writer's own thread after each put returns,
     * which is exactly where the store promises it: a put does not return while level
     * 0 is at or above the stall trigger.
     *
     * <p>The stall counter is what keeps this from passing vacuously. Without it a
     * store that never backed up would leave the test green while testing nothing, and
     * an earlier version of this test did exactly that.
     */
    @Test
    @Tag("slow") // deliberately the worst case for compaction, so it is slow by design
    void aWriterOutrunningTheCompactorIsStalledAndLevelZeroStaysBounded(@TempDir Path dir) {
        int writes = 2_000;
        int bound = StrataStore.stallTrigger();
        java.util.TreeMap<String, byte[]> oracle = new java.util.TreeMap<>();
        java.util.Random rng = new java.util.Random(31);

        try (StrataStore store = StrataStore.openWithoutSync(dir, 16, 1024)) {
            int worst = 0;
            for (int i = 0; i < writes; i++) {
                byte[] k = key(rng.nextInt(1_000_000));
                store.put(k, value(i));
                oracle.put(new String(k, UTF_8), value(i));
                int level0 = store.tableCount(0);
                worst = Math.max(worst, level0);
                assertTrue(level0 <= bound, "level 0 reached " + level0 + " tables, past the "
                        + bound + " a put is supposed to stall at");
            }
            assertTrue(store.writeStalls() > 0,
                    "no writer ever stalled, so the policy was not exercised");
            assertTrue(worst >= bound / 2, "level 0 never backed up, worst was " + worst);

            // Back-pressure must not cost a write. Every key still reads back.
            store.awaitCompactionIdle();
            for (Map.Entry<String, byte[]> e : oracle.entrySet()) {
                assertArrayEquals(e.getValue(),
                        store.get(e.getKey().getBytes(UTF_8)).orElseThrow(
                                () -> new AssertionError("a key was lost")),
                        e.getKey() + " did not survive the stalled workload");
            }
        }
    }

    /**
     * A crash at an arbitrary instant of a background compaction.
     *
     * <p>{@link StrataCrashConsistencyTest} builds the crashed directory by hand,
     * which pins the two named instants exactly. This does the other thing, in the
     * spirit of the log fuzzing in {@link StrataDurabilityPropertyTest}: it copies the
     * live directory out from under a writer over and over, so the copies land
     * wherever they land, including inside the new window this change opens, where a
     * flush has committed a manifest while a compaction's output tables sit on disk
     * unnamed.
     *
     * <p>Every acknowledged key must survive every copy. That is exact rather than a
     * judgement call because the ordering the store keeps makes it so: a table is
     * fsynced and renamed before the manifest names it, the manifest is renamed over
     * in one step, and the log is only reset after the manifest that covers it has
     * committed. So a copy holds each key in a named table, or in the log, or in both,
     * and never in neither.
     */
    @Test
    @Tag("slow") // dozens of directory copies, each reopened and fully checked
    void aCopyTakenDuringBackgroundCompactionRecoversEveryAcknowledgedKey(@TempDir Path root)
            throws IOException {
        Path dir = root.resolve("store");
        int writes = 6_000;
        int every = 200;
        int copies = 0;
        int sawOrphans = 0;

        try (StrataStore store = StrataStore.open(dir, 16)) {
            for (int i = 0; i < writes; i++) {
                store.put(key(i), value(i));
                if (i % every != every - 1) continue;

                // Every key up to and including i has been acknowledged, so every one
                // of them must come back out of the copy.
                Path copy = copyOf(dir, root.resolve("crash-" + i));
                int filesAtCopy = sstableCount(copy);
                try (StrataStore recovered = StrataStore.open(copy, 16)) {
                    for (int j = 0; j <= i; j++) {
                        assertArrayEquals(value(j), recovered.get(key(j)).orElseThrow(
                                        () -> new AssertionError("a key was lost")),
                                "key " + j + " was lost by the copy taken at " + i);
                    }
                }
                if (sstableCount(copy) < filesAtCopy) sawOrphans++;
                copies++;
            }
        }

        assertTrue(copies >= 20, "only " + copies + " copies were checked");
        // Not every copy lands mid-compaction, but over this many none of them doing
        // so would mean the interesting window was never sampled.
        assertTrue(sawOrphans > 0,
                "no copy caught a compaction in flight, so the new window was never tested");
    }

    /**
     * An iterator opened before a background compaction still returns its whole
     * snapshot, and the files it pinned go away when it lets go.
     *
     * <p>{@link StrataConcurrencyPropertyTest} holds a scan across a compaction the
     * caller drives with {@code compact()}. This holds one across compactions the
     * writer triggers, which is the case that only exists now: the reader is not
     * cooperating with the compactor in any way, and the swap happens on a thread the
     * reader has never heard of.
     */
    @Test
    void anIteratorOutlivesTheCompactionsAWriterTriggers(@TempDir Path dir) throws IOException {
        int seeded = 3_000;
        try (StrataStore store = StrataStore.open(dir, 16)) {
            for (int i = 0; i < seeded; i++) store.put(key(i), value(i));
            store.awaitCompactionIdle();

            long before = store.compactionsCompleted();
            Stream<Map.Entry<byte[], byte[]>> scan = store.scan(null, null);
            Iterator<Map.Entry<byte[], byte[]>> rows = scan.iterator();
            assertTrue(rows.hasNext());
            rows.next();

            // Keep writing, with keys above everything the scan snapshotted, until the
            // compactor has swapped the file set several times underneath it.
            for (int i = seeded; store.compactionsCompleted() < before + 3; i++) {
                store.put(key(i), value(i));
            }
            int whileHeld = sstableCount(dir);

            String previous = null;
            long counted = 1;
            while (rows.hasNext()) {
                String current = new String(rows.next().getKey(), UTF_8);
                if (previous != null) {
                    assertTrue(previous.compareTo(current) < 0,
                            "the scan went backwards or repeated: " + previous + " then " + current);
                }
                previous = current;
                counted++;
            }
            assertTrue(counted >= seeded,
                    "the scan lost keys to a compaction: " + counted + " of " + seeded);

            scan.close();
            store.awaitCompactionIdle();
            assertTrue(sstableCount(dir) < whileHeld,
                    "nothing was reclaimed once the reader let go: " + whileHeld + " files before, "
                            + sstableCount(dir) + " after");
        }
    }

    // --- helpers -----------------------------------------------------------------

    /**
     * Copies the store's durable state, which is what a crash would leave. The temp
     * files an in-progress write uses end in {@code .tmp} and are not part of it: each
     * is renamed into place only once it is whole.
     *
     * <p>A crash freezes the whole directory at one instant and a file-by-file copy
     * does not, so a copy can catch a manifest that has already been superseded and
     * lose a table it names to the compaction that superseded it. That is this
     * helper's problem and not the store's, so it retries until it has a set that
     * hangs together. It converges because the store keeps committing new ones.
     */
    private static Path copyOf(Path dir, Path to) throws IOException {
        for (int attempt = 0; attempt < 50; attempt++) {
            Files.createDirectories(to);
            byte[] manifest = Files.readAllBytes(dir.resolve(Manifest.FILE_NAME));
            byte[] log = Files.readAllBytes(dir.resolve("wal.log"));
            boolean whole = true;
            try (Stream<Path> list = Files.list(dir)) {
                for (Path p : (Iterable<Path>) list::iterator) {
                    String name = p.getFileName().toString();
                    if (!name.startsWith("sst-") || !name.endsWith(".sst")) continue;
                    try {
                        Files.copy(p, to.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.NoSuchFileException e) {
                        whole = false; // retired mid-copy, so this manifest may be stale
                    }
                }
            }
            Files.write(to.resolve(Manifest.FILE_NAME), manifest);
            Files.write(to.resolve("wal.log"), log);
            if (whole && namedTablesPresent(to)) return to;
            deleteRecursively(to);
        }
        throw new AssertionError("could not take a self-consistent copy of " + dir);
    }

    /** Whether every table the copied manifest names made it into the copy. */
    private static boolean namedTablesPresent(Path copy) {
        for (String name : Manifest.read(copy).orElseThrow().names()) {
            if (!Files.exists(copy.resolve(name))) return false;
        }
        return true;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static int sstableCount(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(p -> p.getFileName().toString().endsWith(".sst")).count();
        }
    }
}

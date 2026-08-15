package dev.martinkm.strata;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash consistency of the <em>files</em>, which is the half
 * {@link StrataDurabilityPropertyTest} does not reach.
 *
 * <p>Those tests keep every write in the log and then truncate or corrupt it, so
 * they quantify over every way the log can be damaged. They deliberately never
 * let an SSTable exist. That leaves the other persistent state untested: the set
 * of table files, and the moments in a flush and in a compaction when that set is
 * half updated.
 *
 * <p>A crash is simulated here by reconstructing the directory rather than by
 * killing a process, which is what makes these tests deterministic. A compaction
 * writes its output tables and only then deletes the tables it consumed, so a
 * crash in between leaves the union of the two sets on disk. These tests build
 * that union by hand and reopen on it.
 */
class StrataCrashConsistencyTest {

    private static byte[] k(String s) {
        return s.getBytes(UTF_8);
    }

    /**
     * A crash after a compaction wrote its output but before it deleted its
     * inputs must not resurrect an overwritten value.
     *
     * <p>Compaction publishes the new level structure, then retires the tables it
     * consumed, and a retired table's file is deleted once the last reader leaves.
     * A crash is not a reader leaving. The process dies with both the inputs and
     * the outputs named {@code sst-<level>-<seq>.sst} in the directory, and
     * {@code open} rebuilds the levels from exactly those names. A level below
     * zero is supposed to be a disjoint run, so its tables are ordered by first
     * key and a lookup takes the first one whose range covers the key. Two tables
     * covering the same key, one stale and one fresh, breaks that assumption, and
     * which of them answers is decided by a sort that knows nothing about which is
     * newer.
     */
    @Test
    void aCrashBetweenCompactionOutputAndInputDeletionKeepsTheNewestValue(@TempDir Path root)
            throws IOException {
        Path dir = root.resolve("store");
        int keys = 200;

        // Build a store deep enough that compaction moves tables below level 0.
        try (StrataStore store = StrataStore.open(dir, 8)) {
            for (int i = 0; i < keys; i++) store.put(key(i), k("old-" + i));
            store.flush();
            store.compact();
        }

        // Overwrite every key and flush, but stop short of compacting. This is the
        // state the store is in at the instant the doomed compaction starts, so this
        // manifest is the one still on disk when it dies part way through.
        try (StrataStore store = StrataStore.open(dir, 8)) {
            for (int i = 0; i < keys; i++) store.put(key(i), k("new-" + i));
            store.flush();
        }
        Map<String, byte[]> before = tableFiles(dir);
        byte[] manifestBefore = manifest(dir);

        // Now the compaction that will crash: it merges the new values down into the
        // levels the old ones occupy.
        try (StrataStore store = StrataStore.open(dir, 8)) {
            store.compact();
        }
        Map<String, byte[]> after = tableFiles(dir);

        // The crashed directory: everything the compaction wrote, plus everything it
        // consumed but never got to delete, under the manifest that had not yet been
        // replaced. Both sets of files are present and complete. The manifest is the
        // only thing that says which of them is the store.
        Path crashed = crashedDir(root, "crashed", before, after, manifestBefore);

        try (StrataStore store = StrataStore.open(crashed, 8)) {
            for (int i = 0; i < keys; i++) {
                int at = i;
                assertArrayEquals(k("new-" + i), store.get(key(i)).orElseThrow(
                                () -> new AssertionError("key " + at + " vanished after the crash")),
                        "a crash during compaction resurrected the pre-compaction value for key " + i);
            }
        }
    }

    /**
     * The same crash, but for a delete: a tombstone merged down must not be undone
     * by the table it shadowed reappearing.
     *
     * <p>This is the sharper form of the property above. An overwritten value
     * coming back is wrong; a deleted key coming back is the failure a user
     * notices, because the store told them it was gone.
     */
    @Test
    void aCrashDuringCompactionDoesNotResurrectADeletedKey(@TempDir Path root) throws IOException {
        Path dir = root.resolve("store");
        int keys = 200;

        try (StrataStore store = StrataStore.open(dir, 8)) {
            for (int i = 0; i < keys; i++) store.put(key(i), k("value-" + i));
            store.flush();
            store.compact();
        }

        try (StrataStore store = StrataStore.open(dir, 8)) {
            for (int i = 0; i < keys; i += 2) store.delete(key(i));
            store.flush();
        }
        Map<String, byte[]> before = tableFiles(dir);
        byte[] manifestBefore = manifest(dir);

        try (StrataStore store = StrataStore.open(dir, 8)) {
            store.compact();
        }
        Map<String, byte[]> after = tableFiles(dir);

        Path crashed = crashedDir(root, "crashed", before, after, manifestBefore);

        try (StrataStore store = StrataStore.open(crashed, 8)) {
            for (int i = 0; i < keys; i++) {
                if (i % 2 == 0) {
                    assertTrue(store.get(key(i)).isEmpty(),
                            "a crash during compaction resurrected deleted key " + i);
                } else {
                    assertArrayEquals(k("value-" + i), store.get(key(i)).orElseThrow());
                }
            }
        }
    }

    /**
     * The other side of the same instant: a crash after the compaction committed but
     * before it deleted the tables it consumed.
     *
     * <p>The directory looks identical to the case above. Both sets of files are
     * present and complete, and nothing in a table file says which compaction wrote
     * it. Only the manifest differs, and it has to be enough on its own to flip the
     * answer from "the old values" to "the new ones". A fix that merely preferred
     * the newest sequence number would pass the two tests above and fail this one
     * for the same reason in reverse.
     *
     * <p>It also pins the cleanup: the consumed files are orphans now, and reopening
     * is what removes them. Without that they accumulate for the life of the store.
     */
    @Test
    void aCrashAfterTheCompactionCommittedKeepsTheNewStructureAndClearsTheOrphans(
            @TempDir Path root) throws IOException {
        Path dir = root.resolve("store");
        int keys = 200;

        try (StrataStore store = StrataStore.open(dir, 8)) {
            for (int i = 0; i < keys; i++) store.put(key(i), k("old-" + i));
            store.flush();
            store.compact();
        }

        try (StrataStore store = StrataStore.open(dir, 8)) {
            for (int i = 0; i < keys; i++) store.put(key(i), k("new-" + i));
            store.flush();
        }
        Map<String, byte[]> before = tableFiles(dir);

        try (StrataStore store = StrataStore.open(dir, 8)) {
            store.compact();
        }
        Map<String, byte[]> after = tableFiles(dir);
        byte[] manifestAfter = manifest(dir);

        Path crashed = crashedDir(root, "crashed-late", before, after, manifestAfter);
        int filesAtCrash = tableFiles(crashed).size();

        try (StrataStore store = StrataStore.open(crashed, 8)) {
            for (int i = 0; i < keys; i++) {
                int at = i;
                assertArrayEquals(k("new-" + i), store.get(key(i)).orElseThrow(
                                () -> new AssertionError("key " + at + " vanished after the crash")),
                        "a committed compaction did not survive the crash for key " + i);
            }
        }

        assertEquals(after.size(), tableFiles(crashed).size(),
                "reopening must delete the tables the committed compaction consumed");
        assertTrue(filesAtCrash > after.size(),
                "the crash must have left orphans, or this asserts nothing");
    }

    /**
     * A crash between a flush publishing its table and the log being reset must
     * lose nothing and duplicate nothing.
     *
     * <p>A flush writes the SSTable, renames it into place, clears the memtable and
     * only then empties the log, which is the safe order: a crash in the middle
     * leaves the data in both places rather than in neither. Replay then reapplies
     * records that the table already holds. That is only harmless because replay is
     * idempotent in write order, which is a property worth pinning rather than
     * assuming.
     */
    @Test
    void aCrashBetweenAFlushAndTheLogResetLosesNothing(@TempDir Path root) throws IOException {
        Path dir = root.resolve("store");
        TreeMap<String, String> oracle = new TreeMap<>();
        Random rng = new Random(17);

        try (StrataStore store = StrataStore.open(dir, 10_000)) {
            for (int i = 0; i < 400; i++) {
                String key = "key-" + rng.nextInt(80);
                if (rng.nextInt(10) < 7) {
                    String value = "v" + i;
                    store.put(k(key), k(value));
                    oracle.put(key, value);
                } else {
                    store.delete(k(key));
                    oracle.remove(key);
                }
            }
            // A copy of the log as it stands, before the flush empties it.
            byte[] logBeforeFlush = Files.readAllBytes(dir.resolve("wal.log"));
            store.flush();
            // The flush reset the log. Put the pre-flush log back: that is exactly
            // the state a crash between the rename and the reset would leave.
            Files.write(dir.resolve("wal.log"), logBeforeFlush);
        }

        try (StrataStore store = StrataStore.open(dir, 10_000)) {
            assertEquals(oracle.size(), store.size(),
                    "replaying a log the flush already covered changed the live key count");
            for (Map.Entry<String, String> e : oracle.entrySet()) {
                assertArrayEquals(k(e.getValue()), store.get(k(e.getKey())).orElseThrow(
                        () -> new AssertionError("key " + e.getKey() + " was lost")));
            }
            assertEquals(oracle, liveState(store));
        }
    }

    /**
     * Many restarts interleaved with a compaction-heavy workload, checked against
     * an oracle. A restart is a close and a reopen, which is the clean shutdown
     * path rather than a crash, but the small threshold means each run leaves a
     * different mix of levels and log behind, so the reopen has to rebuild the
     * level structure from the file names over and over.
     */
    @Test
    @Tag("slow") // 6,000 ops with a flush every 32 and a restart every 250
    void restartsInterleavedWithCompactionMatchTheOracle(@TempDir Path dir) {
        Random rng = new Random(23);
        TreeMap<String, String> oracle = new TreeMap<>();
        int keyspace = 300;

        StrataStore store = StrataStore.open(dir, 32);
        try {
            for (int i = 0; i < 6_000; i++) {
                if (i % 250 == 0 && i > 0) {
                    if (rng.nextBoolean()) store.compact();
                    store.close();
                    store = StrataStore.open(dir, 32);
                }
                String key = "key-" + String.format("%04d", rng.nextInt(keyspace));
                if (rng.nextInt(10) < 7) {
                    String value = "v" + i;
                    store.put(k(key), k(value));
                    oracle.put(key, value);
                } else {
                    store.delete(k(key));
                    oracle.remove(key);
                }
            }
            assertEquals(oracle, liveState(store));
            assertEquals(oracle.size(), store.size());
        } finally {
            store.close();
        }
    }

    // --- helpers -----------------------------------------------------------------

    /** Every {@code sst-*.sst} in {@code dir}, by file name, with its bytes. */
    private static Map<String, byte[]> tableFiles(Path dir) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (Stream<Path> list = Files.list(dir)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                String name = p.getFileName().toString();
                if (name.startsWith("sst-") && name.endsWith(".sst")) {
                    files.put(name, Files.readAllBytes(p));
                }
            }
        }
        return files;
    }

    /** The manifest as it stands, which is the record of which of those files count. */
    private static byte[] manifest(Path dir) throws IOException {
        return Files.readAllBytes(dir.resolve(Manifest.FILE_NAME));
    }

    /**
     * Builds the directory a crash leaves behind: every table file either set has,
     * and one chosen manifest. Which manifest is the entire question. Before the
     * compaction commits, the old one is still the store's; after, the new one is.
     */
    private static Path crashedDir(Path root, String name, Map<String, byte[]> tablesA,
                                   Map<String, byte[]> tablesB, byte[] manifest)
            throws IOException {
        Path crashed = root.resolve(name);
        Files.createDirectories(crashed);
        Map<String, byte[]> union = new HashMap<>(tablesA);
        union.putAll(tablesB);
        for (Map.Entry<String, byte[]> e : union.entrySet()) {
            Files.write(crashed.resolve(e.getKey()), e.getValue());
        }
        Files.write(crashed.resolve(Manifest.FILE_NAME), manifest);
        assertTrue(union.size() > Math.min(tablesA.size(), tablesB.size()),
                "the compaction must have changed the table set, or this simulates nothing");
        return crashed;
    }

    private static TreeMap<String, String> liveState(StrataStore store) {
        TreeMap<String, String> state = new TreeMap<>();
        try (Stream<Map.Entry<byte[], byte[]>> s = store.scan(null, null)) {
            for (Map.Entry<byte[], byte[]> e : (Iterable<Map.Entry<byte[], byte[]>>) s::iterator) {
                state.put(new String(e.getKey(), UTF_8), new String(e.getValue(), UTF_8));
            }
        }
        return state;
    }

    private static byte[] key(int i) {
        return k("key-" + String.format("%05d", i));
    }
}

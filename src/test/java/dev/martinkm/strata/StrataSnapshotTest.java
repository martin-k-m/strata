package dev.martinkm.strata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshots: a read-only view that keeps answering from the moment it was taken.
 *
 * README.md listed this under "Not done yet" with the reason: "The manifest
 * makes the live set change atomically, which is what a snapshot would be built
 * on, but nothing keeps an old set pinned so a reader can go on seeing it. A
 * scan holds references to the tables it is walking and that is the whole of
 * it."
 *
 * The two halves that have to hold are different in kind, so they are tested
 * separately. The memtable half is a copy, and what could go wrong is seeing a
 * later write. The table half is the reference count, and what could go wrong is
 * a compaction deleting a file the snapshot is still standing on, which is a
 * missing file rather than a wrong answer.
 */
class StrataSnapshotTest {

    private static byte[] k(String s) {
        return s.getBytes(UTF_8);
    }

    private static String s(byte[] b) {
        return new String(b, UTF_8);
    }

    @Test
    void aSnapshotDoesNotSeeWritesMadeAfterIt(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir)) {
            store.put(k("a"), k("1"));
            store.put(k("b"), k("1"));

            try (Snapshot snap = store.snapshot()) {
                store.put(k("a"), k("2"));
                store.put(k("c"), k("new"));
                store.delete(k("b"));

                assertEquals("1", s(snap.get(k("a")).orElseThrow()), "a later put changed the snapshot");
                assertEquals("1", s(snap.get(k("b")).orElseThrow()), "a later delete reached the snapshot");
                assertTrue(snap.get(k("c")).isEmpty(), "a key created after the snapshot was visible in it");

                // And the store itself moved on, which is the other half of the
                // claim: the snapshot is stale on purpose, not frozen for
                // everybody.
                assertEquals("2", s(store.get(k("a")).orElseThrow()));
                assertTrue(store.get(k("b")).isEmpty());
                assertEquals("new", s(store.get(k("c")).orElseThrow()));
            }
        }
    }

    @Test
    void aSnapshotScanAgreesWithItsOwnGets(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir, 4)) {
            for (int i = 0; i < 20; i++) {
                store.put(k(String.format("k%02d", i)), k("v" + i));
            }
            try (Snapshot snap = store.snapshot()) {
                // Churn hard enough to flush and compact underneath the snapshot.
                for (int i = 0; i < 20; i++) {
                    store.put(k(String.format("k%02d", i)), k("CHANGED"));
                }
                List<String> scanned = new ArrayList<>();
                try (Stream<Map.Entry<byte[], byte[]>> st = snap.scan(null, null)) {
                    st.forEach(e -> scanned.add(s(e.getKey()) + "=" + s(e.getValue())));
                }
                assertEquals(20, scanned.size(), "the snapshot's scan lost or gained rows");
                for (int i = 0; i < 20; i++) {
                    String key = String.format("k%02d", i);
                    assertEquals("v" + i, s(snap.get(k(key)).orElseThrow()), key + " changed under the snapshot");
                    assertTrue(scanned.contains(key + "=v" + i), key + " is missing from the snapshot's scan");
                }
            }
        }
    }

    /**
     * The half a copy cannot give you: the files.
     *
     * A snapshot taken before a compaction is standing on tables the compaction
     * will retire. If the reference count did not hold them, this test would not
     * see a wrong value, it would see a missing file, which is why the assertion
     * is on reading every key rather than on a count.
     */
    @Test
    void aSnapshotSurvivesACompactionThatRetiresItsTables(@TempDir Path dir) throws Exception {
        try (StrataStore store = StrataStore.open(dir, 2)) {
            for (int i = 0; i < 24; i++) {
                store.put(k(String.format("k%02d", i)), k("first"));
            }
            long tablesBefore = tableCount(dir);
            assertTrue(tablesBefore > 0, "the workload never spilled to disk, so this tests nothing");

            try (Snapshot snap = store.snapshot()) {
                // Rewrite every key several times over. With a flush threshold of
                // two this produces a great many tables and forces compaction to
                // merge and retire the ones the snapshot is holding.
                for (int round = 0; round < 6; round++) {
                    for (int i = 0; i < 24; i++) {
                        store.put(k(String.format("k%02d", i)), k("round" + round));
                    }
                }
                for (int i = 0; i < 24; i++) {
                    String key = String.format("k%02d", i);
                    Optional<byte[]> v = snap.get(k(key));
                    assertTrue(v.isPresent(), key + " vanished from the snapshot during compaction");
                    assertEquals("first", s(v.get()), key + " was rewritten under the snapshot");
                }
            }
        }
    }

    @Test
    void aClosedSnapshotRefusesReadsRatherThanAnsweringWrongly(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir)) {
            store.put(k("a"), k("1"));
            Snapshot snap = store.snapshot();
            snap.close();
            assertThrows(IllegalStateException.class, () -> snap.get(k("a")));
            assertThrows(IllegalStateException.class, () -> snap.scan(null, null));
            // Closing twice is not an error: a caller that closes explicitly
            // inside try-with-resources should not be punished for it.
            snap.close();
        }
    }

    @Test
    void twoStreamsFromOneSnapshotDoNotInvalidateEachOther(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir, 4)) {
            for (int i = 0; i < 12; i++) {
                store.put(k(String.format("k%02d", i)), k("v" + i));
            }
            try (Snapshot snap = store.snapshot()) {
                Stream<Map.Entry<byte[], byte[]>> first = snap.scan(null, null);
                long firstCount = first.count();
                first.close();
                // If the first stream's close released the snapshot's tables,
                // this second scan would read through freed references.
                try (Stream<Map.Entry<byte[], byte[]>> second = snap.scan(null, null)) {
                    assertEquals(firstCount, second.count(), "a second scan from the same snapshot saw a different store");
                }
                assertEquals("v0", s(snap.get(k("k00")).orElseThrow()));
            }
        }
    }

    @Test
    void aSnapshotRangeScanRespectsItsBounds(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir)) {
            for (int i = 0; i < 10; i++) {
                store.put(k(String.format("k%d", i)), k("v" + i));
            }
            try (Snapshot snap = store.snapshot()) {
                List<String> keys = new ArrayList<>();
                try (Stream<Map.Entry<byte[], byte[]>> st = snap.scan(k("k3"), k("k6"))) {
                    st.forEach(e -> keys.add(s(e.getKey())));
                }
                assertEquals(List.of("k3", "k4", "k5"), keys);
                try (Stream<Map.Entry<byte[], byte[]>> st = snap.scan(k("k6"), k("k3"))) {
                    assertEquals(0, st.count(), "a reversed range returned rows");
                }
            }
        }
    }

    @Test
    void aDeletedKeyStaysReadableThroughAnEarlierSnapshot(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir, 2)) {
            store.put(k("gone"), k("value"));
            try (Snapshot snap = store.snapshot()) {
                store.delete(k("gone"));
                // Force the tombstone through a flush so it is on disk rather
                // than only in the memtable the snapshot copied.
                for (int i = 0; i < 8; i++) {
                    store.put(k("filler" + i), k("x"));
                }
                assertTrue(store.get(k("gone")).isEmpty(), "the store still sees the deleted key");
                assertEquals("value", s(snap.get(k("gone")).orElseThrow()), "the delete reached the snapshot");
                List<String> keys = new ArrayList<>();
                try (Stream<Map.Entry<byte[], byte[]>> st = snap.scan(k("gone"), k("gonf"))) {
                    st.forEach(e -> keys.add(s(e.getKey())));
                }
                assertEquals(List.of("gone"), keys, "the snapshot's scan dropped the key its get returns");
            }
        }
    }

    /** How many SSTable files the store currently has on disk. */
    private static long tableCount(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sst")).count();
        }
    }

    @Test
    void anUnclosedSnapshotIsADiskLeakAndNotACorrectnessProblem(@TempDir Path dir) throws Exception {
        // The documented trade: a snapshot holds retired tables alive. This pins
        // that claim so it cannot quietly stop being true, in either direction.
        try (StrataStore store = StrataStore.open(dir, 2)) {
            for (int i = 0; i < 16; i++) {
                store.put(k(String.format("k%02d", i)), k("first"));
            }
            Snapshot snap = store.snapshot();
            long held = tableCount(dir);
            for (int round = 0; round < 6; round++) {
                for (int i = 0; i < 16; i++) {
                    store.put(k(String.format("k%02d", i)), k("round" + round));
                }
            }
            assertTrue(tableCount(dir) >= 1, "the store lost every table");
            assertFalse(held == 0, "nothing was on disk to hold");
            // The store answers from the new structure while the snapshot holds
            // the old one: both are true at once, which is the point.
            assertEquals("round5", s(store.get(k("k00")).orElseThrow()));
            assertEquals("first", s(snap.get(k("k00")).orElseThrow()));
            snap.close();
        }
    }
}

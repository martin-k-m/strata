package dev.martinkm.strata;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property and crash-fuzz tests for the durability guarantees strata advertises:
 * a write that returned survives, a crash can lose only writes still in flight,
 * and a damaged log never surfaces a value that was never written.
 *
 * <p>The existing {@link StrataStoreTest} checks correctness against an in-memory
 * oracle and covers two hand-built crash cases. These tests generalize the crash
 * cases into properties quantified over <em>every</em> truncation point and
 * <em>every</em> single-byte corruption of the log, and pin the store's ordering
 * and edge-value contracts. They use only JUnit and {@link Random}, keeping the
 * project's zero-dependency character.
 *
 * <p>All crash tests keep the write count well under the default flush threshold
 * (100,000) and use {@link StrataStore#open(Path)}, so every mutation stays in
 * the write-ahead log and no SSTable is created. That makes {@code wal.log} the
 * sole persistent state, so truncating or corrupting it fully determines what a
 * reopen can recover.
 */
class StrataDurabilityPropertyTest {

    private static byte[] k(String s) {
        return s.getBytes(UTF_8);
    }

    /** One generated mutation against the store. */
    private record Op(boolean put, String key, byte[] value) {}

    /**
     * Crash consistency, quantified over every truncation point.
     *
     * A crash can tear the log at any byte offset, so recovery must yield a state
     * that is some <em>prefix</em> of the write history: the writes that were fully
     * logged before the tear, and no others. This truncates the log to every length
     * from zero to its full size, reopens, and asserts the recovered contents equal
     * the store's state after some prefix of the operations. It never opens to a
     * fabricated value and never refuses to open.
     */
    @Test
    @Tag("slow") // one reopen per byte of the log
    void everyTruncationOfTheLogRecoversAValidPrefix(@TempDir Path dir) throws IOException {
        List<Op> ops = generateOps(new Random(1), 120, 16);
        List<TreeMap<String, String>> snapshots = prefixSnapshots(ops);
        Set<String> validStates = canonicalize(snapshots);

        applyAndClose(dir, ops);
        Path wal = dir.resolve("wal.log");
        byte[] full = Files.readAllBytes(wal);

        for (int len = 0; len <= full.length; len++) {
            Files.write(wal, Arrays.copyOf(full, len));
            TreeMap<String, String> recovered = reopenAndReadState(dir);
            assertTrue(validStates.contains(canonical(recovered)),
                    "truncation to " + len + " of " + full.length
                            + " bytes recovered a state that is not any write-history prefix");
        }

        // The untouched full log must recover exactly the final state, not merely a prefix.
        Files.write(wal, full);
        assertEquals(canonical(snapshots.get(snapshots.size() - 1)),
                canonical(reopenAndReadState(dir)), "the intact log must recover the final state");
    }

    /**
     * Corruption safety, quantified over every single-byte flip.
     *
     * Each record is CRC-checked, so a bit rot anywhere in the log must be caught:
     * the corrupt record and everything after it are dropped as an unreadable tail,
     * leaving a valid prefix. This flips every byte in turn and asserts the reopen
     * still lands on a prefix state, never a silently wrong value.
     */
    @Test
    @Tag("slow") // one reopen per byte of the log
    void anySingleByteCorruptionOfTheLogNeverSurfacesAWrongValue(@TempDir Path dir) throws IOException {
        List<Op> ops = generateOps(new Random(2), 100, 12);
        Set<String> validStates = canonicalize(prefixSnapshots(ops));

        applyAndClose(dir, ops);
        Path wal = dir.resolve("wal.log");
        byte[] full = Files.readAllBytes(wal);

        for (int i = 0; i < full.length; i++) {
            byte[] corrupt = full.clone();
            corrupt[i] = (byte) ~corrupt[i]; // flip every bit of one byte
            Files.write(wal, corrupt);
            TreeMap<String, String> recovered = reopenAndReadState(dir);
            assertTrue(validStates.contains(canonical(recovered)),
                    "a byte flip at offset " + i + " surfaced a state that is not a write-history prefix");
        }
    }

    /**
     * Ordering is unsigned-lexicographic, the property the README names and the one
     * a signed byte comparison silently gets wrong. Keys spanning the high half of
     * the byte range (>= 0x80) must scan after the low half, not before it. A flush
     * threshold of one puts every key in its own SSTable, so the k-way merge, not
     * just one in-memory map, has to honor the order.
     */
    @Test
    void scanOrderIsUnsignedLexicographicEvenForHighBytes(@TempDir Path dir) {
        List<byte[]> keys = List.of(
                new byte[] {0x00},
                new byte[] {0x01},
                new byte[] {0x7f},
                new byte[] {(byte) 0x80}, // 128 unsigned: must sort after 0x7f, not before 0x00
                new byte[] {(byte) 0x81},
                new byte[] {(byte) 0xfe},
                new byte[] {(byte) 0xff}, // 255 unsigned: the very last key
                new byte[] {(byte) 0x80, 0x00},
                new byte[] {(byte) 0xff, 0x00},
                new byte[] {0x00, (byte) 0xff});

        try (StrataStore store = StrataStore.open(dir, 1)) {
            int i = 0;
            for (byte[] key : keys) {
                store.put(key, k("v" + i++));
            }

            List<byte[]> expected = new ArrayList<>(keys);
            expected.sort(Arrays::compareUnsigned);

            List<byte[]> got = scanKeys(store);
            assertEquals(expected.size(), got.size());
            for (int j = 0; j < expected.size(); j++) {
                assertArrayEquals(expected.get(j), got.get(j),
                        "key at position " + j + " is out of unsigned order");
            }
        }
    }

    /**
     * An empty value is a real, present binding, distinct from an absent key. The
     * log encodes it with a zero-length payload, which the format must not confuse
     * with the negative length that marks a delete. This stores empty values and
     * keys and values spanning the full byte range, and confirms they round-trip
     * both live and after a reopen that rebuilds purely from the replayed log.
     */
    @Test
    void emptyValuesAndFullByteRangeKeysRoundTripAcrossAReopen(@TempDir Path dir) {
        byte[] emptyKey = new byte[] {0x2a};
        byte[] binaryKey = new byte[] {0x00, (byte) 0xff, 0x10, (byte) 0x80};
        byte[] binaryValue = new byte[] {(byte) 0xff, 0x00, 0x7f, (byte) 0x80, 0x00};

        try (StrataStore store = StrataStore.open(dir)) {
            store.put(emptyKey, new byte[0]);          // present, but zero-length
            store.put(binaryKey, binaryValue);
            store.put(k("normal"), k("value"));

            assertArrayEquals(new byte[0], store.get(emptyKey).orElseThrow(),
                    "an empty value must read back present and empty, not absent");
            assertArrayEquals(binaryValue, store.get(binaryKey).orElseThrow());
            assertEquals(3, store.size());
        }
        // A fresh open rebuilds from the log alone: the zero-length value must not
        // have been mistaken for a delete on replay.
        try (StrataStore store = StrataStore.open(dir)) {
            assertTrue(store.get(emptyKey).isPresent(), "the empty value must survive a reopen");
            assertArrayEquals(new byte[0], store.get(emptyKey).orElseThrow());
            assertArrayEquals(binaryValue, store.get(binaryKey).orElseThrow());
            assertEquals(3, store.size());
        }
    }

    /**
     * Recovery is idempotent and correct across repeated restarts. This runs random
     * operations against an oracle, closing and reopening the store many times
     * mid-stream with a small flush threshold, so each reopen must reconstruct state
     * from a mix of on-disk SSTables and a replayed log. The final store must equal
     * the oracle exactly.
     */
    @Test
    @Tag("slow") // 10,000 ops with a flush every 64 and a reopen every 500
    void randomOpsMatchTheOracleAcrossManyReopens(@TempDir Path dir) {
        Random rng = new Random(3);
        TreeMap<String, byte[]> oracle = new TreeMap<>();
        int keyspace = 200;

        StrataStore store = StrataStore.open(dir, 64);
        try {
            for (int i = 0; i < 10_000; i++) {
                if (i % 500 == 0 && i > 0) { // simulate a restart every 500 ops
                    store.close();
                    store = StrataStore.open(dir, 64);
                }
                String key = "key-" + String.format("%03d", rng.nextInt(keyspace));
                if (rng.nextInt(10) < 7) {
                    byte[] value = randomBytes(rng, 1 + rng.nextInt(30));
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
        } finally {
            store.close();
        }
    }

    // --- helpers -----------------------------------------------------------------

    /** A deterministic mix of puts and deletes over a small keyspace. */
    private static List<Op> generateOps(Random rng, int count, int keyspace) {
        List<Op> ops = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = "key-" + rng.nextInt(keyspace);
            if (rng.nextInt(10) < 7) {
                ops.add(new Op(true, key, randomBytes(rng, 1 + rng.nextInt(8))));
            } else {
                ops.add(new Op(false, key, null));
            }
        }
        return ops;
    }

    /** State after each prefix of {@code ops}: snapshot[j] reflects ops[0..j). */
    private static List<TreeMap<String, String>> prefixSnapshots(List<Op> ops) {
        List<TreeMap<String, String>> snapshots = new ArrayList<>(ops.size() + 1);
        TreeMap<String, String> state = new TreeMap<>();
        snapshots.add(new TreeMap<>(state)); // empty state, before any op
        for (Op op : ops) {
            if (op.put()) {
                state.put(op.key(), hex(op.value()));
            } else {
                state.remove(op.key());
            }
            snapshots.add(new TreeMap<>(state));
        }
        return snapshots;
    }

    /** Writes each op through a store and closes it, leaving the log on disk. */
    private static void applyAndClose(Path dir, List<Op> ops) {
        try (StrataStore store = StrataStore.open(dir)) {
            for (Op op : ops) {
                if (op.put()) {
                    store.put(k(op.key()), op.value());
                } else {
                    store.delete(k(op.key()));
                }
            }
        }
    }

    /** Opens the store, reads its full live state as key -> hex(value), and closes it. */
    private static TreeMap<String, String> reopenAndReadState(Path dir) {
        TreeMap<String, String> state = new TreeMap<>();
        try (StrataStore store = StrataStore.open(dir);
             Stream<Map.Entry<byte[], byte[]>> s = store.scan(null, null)) {
            for (Map.Entry<byte[], byte[]> e : (Iterable<Map.Entry<byte[], byte[]>>) s::iterator) {
                state.put(new String(e.getKey(), UTF_8), hex(e.getValue()));
            }
        }
        return state;
    }

    private static Set<String> canonicalize(List<TreeMap<String, String>> snapshots) {
        Set<String> set = new HashSet<>();
        for (TreeMap<String, String> snap : snapshots) {
            set.add(canonical(snap));
        }
        return set;
    }

    /** A stable string for a state map, so states can be compared by value. */
    private static String canonical(TreeMap<String, String> state) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : state.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    private static List<byte[]> scanKeys(StrataStore store) {
        List<byte[]> keys = new ArrayList<>();
        try (Stream<Map.Entry<byte[], byte[]>> s = store.scan(null, null)) {
            for (Map.Entry<byte[], byte[]> e : (Iterable<Map.Entry<byte[], byte[]>>) s::iterator) {
                keys.add(e.getKey());
            }
        }
        return keys;
    }

    private static byte[] randomBytes(Random rng, int len) {
        byte[] b = new byte[len];
        rng.nextBytes(b);
        return b;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }
}

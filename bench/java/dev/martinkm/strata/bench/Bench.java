package dev.martinkm.strata.bench;

import dev.martinkm.strata.StrataStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * The measurement harness behind docs/BENCHMARKS.md.
 *
 * This is not JMH. It is a timed loop with an explicit warmup phase, and it
 * reports a median and a p99 rather than a mean, because the interesting part of
 * an LSM tree is the tail: the put that lands on a flush, the read that lands
 * during a compaction. Every number the document quotes comes out of a run of
 * this class, and the command that produced it is printed alongside.
 *
 * <p>Two honest limitations, stated here so they are not discovered later. The
 * harness times individual operations with {@link System#nanoTime()}, whose own
 * cost is tens of nanoseconds, so a memtable read at a hundred nanoseconds is
 * measured with an instrument of the same order as the thing measured; those rows
 * are a ceiling on speed, not a precise latency. And a single JVM process runs one
 * scenario, so there is no fork-to-fork variance in the numbers, only
 * within-process variance across iterations.
 */
public final class Bench {

    private static final int KEY_BYTES = 16;
    private static final int VALUE_BYTES = 100;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Bench <scenario> [n] [outdir]");
            System.err.println("scenarios: write-seq write-rand read-split scan write-amp");
            System.err.println("           space-amp compaction-tail fsync all");
            System.exit(2);
        }
        String scenario = args[0];
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 200_000;
        Path root = args.length > 2 ? Path.of(args[2]) : Files.createTempDirectory("strata-bench");
        Files.createDirectories(root);

        header(scenario, n, root);
        switch (scenario) {
            case "write-seq" -> writeThroughput(root, n, false);
            case "write-rand" -> writeThroughput(root, n, true);
            case "read-split" -> readSplit(root, n);
            case "scan" -> scanThroughput(root, n);
            case "write-amp" -> writeAmplification(root, n);
            case "space-amp" -> spaceAmplification(root, n);
            case "compaction-tail" -> compactionTail(root, n);
            case "fsync" -> fsyncCost(root, n);
            case "all" -> {
                writeThroughput(root, n, false);
                writeThroughput(root, n, true);
                readSplit(root, n);
                scanThroughput(root, n);
                writeAmplification(root, n);
                spaceAmplification(root, n);
                compactionTail(root, n);
                fsyncCost(root, n);
            }
            default -> {
                System.err.println("unknown scenario: " + scenario);
                System.exit(2);
            }
        }
    }

    // ---------------------------------------------------------------- scenarios

    /**
     * Puts of fresh keys, sequential or random, through the ordinary durable path.
     * The warmup fills and discards a store a tenth the size, so the JIT has
     * compiled the write path and the file system has the directory hot before the
     * measured run starts.
     */
    private static void writeThroughput(Path root, int n, boolean random) throws IOException {
        String name = random ? "write-rand" : "write-seq";
        int warm = Math.max(1000, n / 10);

        Path warmDir = fresh(root, name + "-warmup");
        try (StrataStore s = StrataStore.open(warmDir, 100_000)) {
            fill(s, warm, random, 1);
        }

        Path dir = fresh(root, name);
        long[] latency = new long[n];
        byte[] value = value(VALUE_BYTES);
        Random rnd = new Random(42);
        long start;
        try (StrataStore s = StrataStore.open(dir, 100_000)) {
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                byte[] k = random ? randomKey(rnd) : sequentialKey(i);
                start = System.nanoTime();
                s.put(k, value);
                latency[i] = System.nanoTime() - start;
            }
            long elapsed = System.nanoTime() - t0;
            report(name, n, elapsed, latency);
            line("payload rate", String.format("%.2f MiB/s",
                    mib((long) n * (KEY_BYTES + VALUE_BYTES)) / seconds(elapsed)));
        }
    }

    /**
     * The point of the whole document: a point read costs three completely
     * different amounts depending on where the key lives.
     *
     * <p>Memtable hit reads a key still in memory. SSTable hit reads a key whose
     * only copy is on disk, with the memtable emptied by an explicit flush and
     * compaction first. Miss reads a key that was never written, which is the
     * bloom filter's whole job: it should cost less than a hit, because it skips
     * every table without touching its data.
     *
     * <p>The SSTable row is run twice, once with the default block cache and once
     * with a cache of a single block, which is as close to a cold read as this
     * harness gets. Both still sit above the operating system's page cache, so
     * neither is a disk seek; the difference between them is the cost of decoding
     * and checksumming a block rather than the cost of reaching the platter.
     */
    private static void readSplit(Path root, int n) throws IOException {
        int probes = Math.min(n, 200_000);
        byte[] value = value(VALUE_BYTES);

        // Memtable: a threshold above n keeps every key in memory.
        Path memDir = fresh(root, "read-memtable");
        try (StrataStore s = StrataStore.open(memDir, n * 2)) {
            for (int i = 0; i < n; i++) s.put(sequentialKey(i), value);
            timeReads("read-memtable-hit", s, probes, n, true);
        }

        // SSTable: a small threshold forces spills, then flush and compact empty
        // the memtable, so every hit below has to come off disk.
        Path sstDir = fresh(root, "read-sstable");
        try (StrataStore s = StrataStore.open(sstDir, Math.max(1000, n / 10))) {
            for (int i = 0; i < n; i++) s.put(sequentialKey(i), value);
            s.flush();
            s.compact();
            timeReads("read-sstable-hit (block cache 1024)", s, probes, n, true);
            timeReads("read-miss (bloom filter)", s, probes, n, false);
        }

        Path coldDir = fresh(root, "read-sstable-cold");
        try (StrataStore s = StrataStore.open(coldDir, Math.max(1000, n / 10), 1)) {
            for (int i = 0; i < n; i++) s.put(sequentialKey(i), value);
            s.flush();
            s.compact();
            timeReads("read-sstable-hit (block cache 1)", s, probes, n, true);
        }
    }

    /** Full and partial ordered scans, in entries per second. */
    private static void scanThroughput(Path root, int n) throws IOException {
        byte[] value = value(VALUE_BYTES);
        Path dir = fresh(root, "scan");
        try (StrataStore s = StrataStore.open(dir, Math.max(1000, n / 10))) {
            for (int i = 0; i < n; i++) s.put(sequentialKey(i), value);
            s.flush();
            s.compact();

            for (int w = 0; w < 3; w++) countScan(s, null, null); // warmup

            List<Long> rates = new ArrayList<>();
            long entries = 0;
            for (int it = 0; it < 5; it++) {
                long t0 = System.nanoTime();
                entries = countScan(s, null, null);
                long elapsed = System.nanoTime() - t0;
                rates.add((long) (entries / seconds(elapsed)));
            }
            rates.sort(Comparator.naturalOrder());
            line("scan (full, " + entries + " entries)",
                    String.format("%,d entries/s median of 5", rates.get(rates.size() / 2)));

            // A short scan pays the k-way merge setup over few entries, which is
            // where an LSM range read looks worst.
            long[] shortLat = new long[1000];
            for (int i = 0; i < 200; i++) countScan(s, sequentialKey(i), sequentialKey(i + 100));
            for (int i = 0; i < shortLat.length; i++) {
                long t0 = System.nanoTime();
                countScan(s, sequentialKey(i), sequentialKey(i + 100));
                shortLat[i] = System.nanoTime() - t0;
            }
            percentiles("scan (100 entries)", shortLat);
        }
    }

    /**
     * Write amplification, the number that says whether the level structure is
     * doing its job: every byte the store put on disk over every byte the caller
     * handed it. The WAL contributes one copy of each value before it is ever
     * flushed, so the floor is above one no matter how good the compaction is.
     */
    private static void writeAmplification(Path root, int n) throws IOException {
        for (int threshold : new int[] {n * 2, Math.max(1000, n / 10), Math.max(1000, n / 40)}) {
            Path dir = fresh(root, "write-amp-" + threshold);
            byte[] value = value(VALUE_BYTES);
            try (StrataStore s = StrataStore.open(dir, threshold)) {
                for (int i = 0; i < n; i++) s.put(randomKeyFor(i), value);
                s.flush();
                s.compact();
                StrataStore.IoStats io = s.ioStats();
                System.out.printf("  flush threshold %,d (%d flushes worth of keys)%n",
                        threshold, Math.max(1, n / threshold));
                System.out.printf("    logical     %,15d bytes%n", io.logical());
                System.out.printf("    wal         %,15d bytes  (x%.2f)%n",
                        io.wal(), (double) io.wal() / io.logical());
                System.out.printf("    flush       %,15d bytes  (x%.2f)%n",
                        io.flush(), (double) io.flush() / io.logical());
                System.out.printf("    compaction  %,15d bytes  (x%.2f)%n",
                        io.compaction(), (double) io.compaction() / io.logical());
                System.out.printf("    physical    %,15d bytes%n", io.physical());
                System.out.printf("    WRITE AMPLIFICATION  %.2fx%n", io.writeAmplification());
            }
        }
    }

    /**
     * Space amplification: bytes resident on disk over the bytes of the live keys
     * and values. Overwrites and deletes are what drive it up, because an
     * overwritten value stays on disk until a compaction reaches it.
     */
    private static void spaceAmplification(Path root, int n) throws IOException {
        byte[] value = value(VALUE_BYTES);
        int distinct = n / 4; // each key written four times on average
        long live = (long) distinct * (KEY_BYTES + VALUE_BYTES);

        System.out.printf("  %,d writes over %,d distinct keys, %,d live logical bytes%n",
                n, distinct, live);
        System.out.println("  on-disk figures are SSTable bytes only; the log is empty after a flush");

        // Two thresholds, because the interesting quantity is how much stale data
        // is still resident, and that is decided by whether compaction has caught
        // up. Three flushes is below the level-0 trigger of four, so nothing has
        // been merged and every overwrite is still on disk. Ten flushes is past it
        // several times over, so the level triggers have already reclaimed most of
        // it before anything is forced.
        int[] thresholds = {Math.max(1000, n / 3), Math.max(1000, n / 10)};
        String[] labels = {"below the level-0 trigger", "level triggers have run"};

        for (int c = 0; c < thresholds.length; c++) {
            Path dir = fresh(root, "space-amp-" + thresholds[c]);
            try (StrataStore s = StrataStore.open(dir, thresholds[c])) {
                Random rnd = new Random(7);
                for (int i = 0; i < n; i++) s.put(sequentialKey(rnd.nextInt(distinct)), value);
                s.flush();
                long before = sstableBytes(dir);
                System.out.printf("  flush threshold %,d (%s)%n", thresholds[c], labels[c]);
                System.out.printf("    as it lies         %,12d bytes  (%.2fx)%n",
                        before, (double) before / live);
                s.compact();
                long after = sstableBytes(dir);
                System.out.printf("    after compact()    %,12d bytes  (%.2fx)%n",
                        after, (double) after / live);
            }
        }
    }

    /**
     * Read latency at rest against read latency while a writer is flushing and
     * compacting on another thread. Compaction runs on the writer's thread in this
     * engine, so the reader is never blocked by a lock; whatever spike shows up is
     * disk and cache contention, not a stall.
     */
    private static void compactionTail(Path root, int n) throws Exception {
        byte[] value = value(VALUE_BYTES);
        int probes = Math.min(n, 100_000);

        Path dir = fresh(root, "compaction-tail");
        try (StrataStore s = StrataStore.open(dir, Math.max(1000, n / 20))) {
            for (int i = 0; i < n; i++) s.put(sequentialKey(i), value);
            s.flush();
            s.compact();

            timeReads("read at rest", s, probes, n, true);

            AtomicBoolean stop = new AtomicBoolean();
            Thread writer = new Thread(() -> {
                Random rnd = new Random(11);
                while (!stop.get()) s.put(sequentialKey(rnd.nextInt(n)), value);
            }, "bench-writer");
            writer.start();
            try {
                timeReads("read during compaction", s, probes, n, true);
            } finally {
                stop.set(true);
                writer.join();
            }
        }
    }

    /**
     * The price of the durability guarantee: the same put loop with the log fsynced
     * on every write and with it not fsynced at all. The second store is not
     * durable and exists only to make the first one's cost legible.
     */
    private static void fsyncCost(Path root, int n) throws IOException {
        int capped = Math.min(n, 50_000); // the synced run is slow enough to bound
        byte[] value = value(VALUE_BYTES);

        Path syncDir = fresh(root, "fsync-on");
        long[] onLat = new long[capped];
        try (StrataStore s = StrataStore.open(syncDir, 100_000)) {
            for (int i = 0; i < 1000; i++) s.put(sequentialKey(i), value);
            long t0 = System.nanoTime();
            for (int i = 0; i < capped; i++) {
                long a = System.nanoTime();
                s.put(sequentialKey(i), value);
                onLat[i] = System.nanoTime() - a;
            }
            report("put, fsync per write (durable)", capped, System.nanoTime() - t0, onLat);
        }

        Path nosyncDir = fresh(root, "fsync-off");
        long[] offLat = new long[capped];
        try (StrataStore s = StrataStore.openWithoutSync(nosyncDir, 100_000, 1024)) {
            for (int i = 0; i < 1000; i++) s.put(sequentialKey(i), value);
            long t0 = System.nanoTime();
            for (int i = 0; i < capped; i++) {
                long a = System.nanoTime();
                s.put(sequentialKey(i), value);
                offLat[i] = System.nanoTime() - a;
            }
            report("put, no fsync (NOT durable)", capped, System.nanoTime() - t0, offLat);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Times {@code probes} point reads, after a warmup pass a tenth as long. When
     * {@code hit} is set the keys are all present; otherwise they are all absent,
     * drawn from a key space the store never saw.
     */
    private static void timeReads(String name, StrataStore s, int probes, int n, boolean hit) {
        Random rnd = new Random(hit ? 99 : 100);
        for (int i = 0; i < probes / 10; i++) {
            s.get(hit ? sequentialKey(rnd.nextInt(n)) : missingKey(rnd.nextInt(n)));
        }
        long[] latency = new long[probes];
        long t0 = System.nanoTime();
        for (int i = 0; i < probes; i++) {
            byte[] k = hit ? sequentialKey(rnd.nextInt(n)) : missingKey(rnd.nextInt(n));
            long a = System.nanoTime();
            Optional<byte[]> v = s.get(k);
            latency[i] = System.nanoTime() - a;
            if (v.isPresent() != hit) {
                throw new IllegalStateException(name + ": unexpected result at probe " + i);
            }
        }
        report(name, probes, System.nanoTime() - t0, latency);
    }

    private static long countScan(StrataStore s, byte[] from, byte[] to) {
        try (Stream<Map.Entry<byte[], byte[]>> st = s.scan(from, to)) {
            return st.count();
        }
    }

    private static void fill(StrataStore s, int n, boolean random, long seed) {
        byte[] value = value(VALUE_BYTES);
        Random rnd = new Random(seed);
        for (int i = 0; i < n; i++) s.put(random ? randomKey(rnd) : sequentialKey(i), value);
    }

    private static void report(String name, int ops, long elapsedNanos, long[] latency) {
        System.out.printf("  %-38s %,12.0f ops/s%n", name, ops / seconds(elapsedNanos));
        percentiles("    ", latency);
    }

    private static void percentiles(String label, long[] latency) {
        long[] sorted = latency.clone();
        java.util.Arrays.sort(sorted);
        System.out.printf("  %-38s p50 %s   p99 %s   p999 %s   max %s%n",
                label.isBlank() ? "" : label,
                nanos(pct(sorted, 50)), nanos(pct(sorted, 99)),
                nanos(pct(sorted, 99.9)), nanos(sorted[sorted.length - 1]));
    }

    private static long pct(long[] sorted, double p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.min(Math.max(idx, 0), sorted.length - 1)];
    }

    private static String nanos(long v) {
        if (v < 10_000) return String.format("%,6d ns", v);
        if (v < 10_000_000) return String.format("%,6.1f us", v / 1_000.0);
        return String.format("%,6.1f ms", v / 1_000_000.0);
    }

    private static void line(String name, String value) {
        System.out.printf("  %-38s %s%n", name, value);
    }

    private static void header(String scenario, int n, Path root) {
        System.out.println("strata bench: " + scenario + "  n=" + n);
        System.out.println("  jvm      " + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("  os       " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        System.out.println("  cpus     " + Runtime.getRuntime().availableProcessors());
        System.out.println("  heap max " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MiB");
        System.out.println("  dir      " + root);
        System.out.println();
    }

    /** Total bytes of the SSTables in the store directory, excluding the log. */
    private static long sstableBytes(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            long total = 0;
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.getFileName().toString().endsWith(".sst")) total += Files.size(p);
            }
            return total;
        }
    }

    private static Path fresh(Path root, String name) throws IOException {
        Path dir = root.resolve(name);
        deleteRecursively(dir);
        Files.createDirectories(dir);
        return dir;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static byte[] value(int size) {
        byte[] v = new byte[size];
        new Random(1).nextBytes(v);
        return v;
    }

    /** A fixed-width, order-preserving key for index {@code i}. */
    private static byte[] sequentialKey(int i) {
        return String.format("key%013d", i).getBytes(StandardCharsets.US_ASCII);
    }

    /** A key that is never written, in a disjoint prefix so no bloom filter has seen it. */
    private static byte[] missingKey(int i) {
        return String.format("zzz%013d", i).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] randomKey(Random rnd) {
        byte[] k = new byte[KEY_BYTES];
        rnd.nextBytes(k);
        return k;
    }

    /** A deterministic scattered key, so a run is repeatable but the keys are not in order. */
    private static byte[] randomKeyFor(int i) {
        long h = i * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return String.format("key%013d", Math.abs(h % 1_000_000_000_000L))
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private static double mib(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private Bench() {}
}

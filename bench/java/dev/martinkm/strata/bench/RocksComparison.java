package dev.martinkm.strata.bench;

import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;

/**
 * The same workload as {@link Bench}, run against RocksDB on the same machine and
 * the same disk, so the comparison in docs/BENCHMARKS.md is measured rather than
 * assumed. strata is expected to lose. Publishing by how much is the point.
 *
 * <p>RocksDB is configured to match what strata actually does, not to be fast:
 * one column family, no block compression, and {@code WriteOptions.setSync(true)}
 * for the durable run, because strata fsyncs its log on every put. The unsynced
 * run is the same workload with that removed, matching
 * {@code StrataStore.openWithoutSync}.
 *
 * <p>This class is compiled and run separately from {@link Bench} so that a
 * machine without a usable {@code rocksdbjni} native library still gets every
 * other number in the document.
 */
public final class RocksComparison {

    private static final int VALUE_BYTES = 100;

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 50_000;
        Path root = args.length > 1 ? Path.of(args[1]) : Files.createTempDirectory("rocks-bench");
        Files.createDirectories(root);

        RocksDB.loadLibrary();
        System.out.println("rocksdb comparison  n=" + n);
        System.out.println("  rocksdb  " + RocksDB.rocksdbVersion());
        System.out.println("  jvm      " + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.version"));
        System.out.println("  dir      " + root);
        System.out.println();

        byte[] value = new byte[VALUE_BYTES];
        new Random(1).nextBytes(value);

        for (boolean sync : new boolean[] {true, false}) {
            Path dir = fresh(root, "rocks-" + (sync ? "sync" : "nosync"));
            try (Options options = new Options().setCreateIfMissing(true);
                 WriteOptions wo = new WriteOptions().setSync(sync);
                 RocksDB db = RocksDB.open(options, dir.toString())) {

                // Warmup a tenth the size, discarded, matching the strata harness.
                for (int i = 0; i < Math.max(1000, n / 10); i++) db.put(wo, key(i), value);

                long[] latency = new long[n];
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    long a = System.nanoTime();
                    db.put(wo, key(i), value);
                    latency[i] = System.nanoTime() - a;
                }
                report("rocksdb put, sync=" + sync, n, System.nanoTime() - t0, latency);

                if (!sync) {
                    // Reads only on the fast store, so building it does not cost minutes.
                    try (FlushOptions fo = new FlushOptions().setWaitForFlush(true)) {
                        db.flush(fo);
                    }
                    db.compactRange();
                    reads("rocksdb get, hit", db, n, true);
                    reads("rocksdb get, miss", db, n, false);
                    scan(db, n);
                }
            }
        }
    }

    private static void reads(String name, RocksDB db, int n, boolean hit) throws Exception {
        int probes = Math.min(n, 200_000);
        Random rnd = new Random(hit ? 99 : 100);
        for (int i = 0; i < probes / 10; i++) db.get(hit ? key(rnd.nextInt(n)) : missing(rnd.nextInt(n)));

        long[] latency = new long[probes];
        long t0 = System.nanoTime();
        for (int i = 0; i < probes; i++) {
            byte[] k = hit ? key(rnd.nextInt(n)) : missing(rnd.nextInt(n));
            long a = System.nanoTime();
            byte[] v = db.get(k);
            latency[i] = System.nanoTime() - a;
            if ((v != null) != hit) throw new IllegalStateException(name + ": unexpected result");
        }
        report(name, probes, System.nanoTime() - t0, latency);
    }

    private static void scan(RocksDB db, int n) {
        for (int w = 0; w < 3; w++) fullScan(db);
        long[] rates = new long[5];
        long entries = 0;
        for (int i = 0; i < rates.length; i++) {
            long t0 = System.nanoTime();
            entries = fullScan(db);
            rates[i] = (long) (entries / ((System.nanoTime() - t0) / 1e9));
        }
        Arrays.sort(rates);
        System.out.printf("  %-38s %,12d entries/s median of 5 (%,d entries)%n",
                "rocksdb scan (full)", rates[rates.length / 2], entries);
    }

    private static long fullScan(RocksDB db) {
        long count = 0;
        try (RocksIterator it = db.newIterator()) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                it.key();
                it.value();
                count++;
            }
        }
        return count;
    }

    private static void report(String name, int ops, long elapsed, long[] latency) {
        long[] sorted = latency.clone();
        Arrays.sort(sorted);
        System.out.printf("  %-38s %,12.0f ops/s%n", name, ops / (elapsed / 1e9));
        System.out.printf("  %-38s p50 %s   p99 %s   p999 %s   max %s%n", "",
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

    private static byte[] key(int i) {
        return String.format("key%013d", i).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] missing(int i) {
        return String.format("zzz%013d", i).getBytes(StandardCharsets.US_ASCII);
    }

    private static Path fresh(Path root, String name) throws Exception {
        Path dir = root.resolve(name);
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        Files.createDirectories(dir);
        return dir;
    }

    private RocksComparison() {}
}

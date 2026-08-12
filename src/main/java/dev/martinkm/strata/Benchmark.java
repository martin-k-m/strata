package dev.martinkm.strata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * A small throughput harness over a real on-disk store.
 *
 * It fills a store with N random keys, then times puts, gets that hit, gets that
 * miss and full scans, and prints operations per second for each. It is a timed
 * loop, not JMH: no warmup discipline, no statistical rigour, so read the numbers
 * as rough magnitudes rather than precise figures. Every put also fsyncs the log,
 * so the put rate is dominated by the disk, and a small flush threshold here forces
 * SSTables to exist so gets and scans cross more than the memtable.
 *
 * <p>Run it with {@code gradle bench}, or with {@code -Pbench.n=200000} to change N.
 */
public final class Benchmark {

    private Benchmark() {
    }

    public static void main(String[] args) throws IOException {
        int n = (args.length > 0) ? Integer.parseInt(args[0]) : 100_000;
        int flushThreshold = 20_000;
        Random rng = new Random(1);

        Path dir = Files.createTempDirectory("strata-bench");
        try (StrataStore store = StrataStore.open(dir, flushThreshold)) {
            byte[][] keys = new byte[n][];
            byte[][] values = new byte[n][];
            for (int i = 0; i < n; i++) {
                keys[i] = ("key-" + rng.nextInt(Integer.MAX_VALUE)).getBytes();
                values[i] = randomBytes(rng, 64);
            }

            System.out.println("strata benchmark");
            System.out.println("n=" + n + " flushThreshold=" + flushThreshold + " valueBytes=64");

            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                store.put(keys[i], values[i]);
            }
            report("put", n, System.nanoTime() - t0);

            long hits = 0;
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                if (store.get(keys[i]).isPresent()) hits++;
            }
            report("get (hit)", n, System.nanoTime() - t0);
            if (hits < 0) System.out.println(hits); // keep the JIT from dropping the loop

            // The same gets again, now that the first pass has warmed the block cache.
            // With many distinct keys the working set can exceed the cache, so this is
            // an honest second pass, not a guaranteed all-hit run.
            long warmHits = 0;
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                if (store.get(keys[i]).isPresent()) warmHits++;
            }
            report("get (warm)", n, System.nanoTime() - t0);
            if (warmHits < 0) System.out.println(warmHits);

            byte[][] absent = new byte[n][];
            for (int i = 0; i < n; i++) {
                absent[i] = ("absent-" + rng.nextInt(Integer.MAX_VALUE)).getBytes();
            }
            long misses = 0;
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                if (store.get(absent[i]).isEmpty()) misses++;
            }
            report("get (miss)", n, System.nanoTime() - t0);
            if (misses < 0) System.out.println(misses);

            long scanned = 0;
            t0 = System.nanoTime();
            try (Stream<Map.Entry<byte[], byte[]>> scan = store.scan(null, null)) {
                scanned = scan.count();
            }
            report("scan (full)", scanned, System.nanoTime() - t0);
            System.out.println("live keys scanned: " + scanned);
        } finally {
            deleteTree(dir);
        }
    }

    private static void report(String name, long ops, long nanos) {
        double seconds = nanos / 1e9;
        long perSec = (seconds > 0) ? Math.round(ops / seconds) : 0;
        System.out.printf("%-12s %,10d ops  %8.3f s  %,12d ops/s%n", name, ops, seconds, perSec);
    }

    private static byte[] randomBytes(Random rng, int len) {
        byte[] b = new byte[len];
        rng.nextBytes(b);
        return b;
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

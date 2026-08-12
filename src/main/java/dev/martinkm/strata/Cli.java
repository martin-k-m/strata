package dev.martinkm.strata;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A command-line front end over a {@link StrataStore}, so the store is usable
 * from a shell and not only as a library.
 *
 * Each invocation opens the store, does one command's worth of work and closes
 * it again. There is no long-running process and no server, so exactly one
 * command touches a store directory at a time. The store is single-writer, and
 * this holds to that: run one strata command against a directory at a time.
 *
 * <p>Keys and values on the command line are UTF-8 text, encoded to bytes for
 * the store. Values that carry a tab or a newline are out of scope. {@code scan}
 * prints one {@code key\t value} pair per line, so a value with either character
 * would break that layout. Keep keys and values to plain single-line text.
 *
 * <p>The command logic lives in {@link #run(String[], PrintStream, PrintStream)},
 * which returns an exit status rather than calling {@link System#exit}, so a test
 * can drive it and assert on the status and the captured output. {@link #main}
 * is the only place that exits the JVM.
 */
public final class Cli {

    private Cli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs one command and returns its exit status: 0 on success, 1 when a
     * {@code get} finds no value, 2 on an unknown command or a wrong argument
     * count. It never calls {@link System#exit}, so tests can assert on the
     * returned status and on {@code out} and {@code err}.
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            return usage(err);
        }
        String command = args[0];
        Path dir = Path.of(args[1]);

        switch (command) {
            case "put":
                if (args.length != 4) return usage(err);
                try (StrataStore store = StrataStore.open(dir)) {
                    store.put(bytes(args[2]), bytes(args[3]));
                    out.println("ok");
                }
                return 0;

            case "get":
                if (args.length != 3) return usage(err);
                try (StrataStore store = StrataStore.open(dir)) {
                    Optional<byte[]> value = store.get(bytes(args[2]));
                    if (value.isEmpty()) return 1;
                    out.println(text(value.get()));
                }
                return 0;

            case "delete":
                if (args.length != 3) return usage(err);
                try (StrataStore store = StrataStore.open(dir)) {
                    store.delete(bytes(args[2]));
                    out.println("ok");
                }
                return 0;

            case "scan":
                if (args.length < 2 || args.length > 4) return usage(err);
                byte[] from = (args.length > 2 && !args[2].isEmpty()) ? bytes(args[2]) : null;
                byte[] to = (args.length > 3 && !args[3].isEmpty()) ? bytes(args[3]) : null;
                try (StrataStore store = StrataStore.open(dir);
                     Stream<Map.Entry<byte[], byte[]>> scan = store.scan(from, to)) {
                    scan.forEach(e -> out.println(text(e.getKey()) + "\t" + text(e.getValue())));
                }
                return 0;

            case "compact":
                if (args.length != 2) return usage(err);
                try (StrataStore store = StrataStore.open(dir)) {
                    store.compact();
                }
                return 0;

            case "info":
                if (args.length != 2) return usage(err);
                try (StrataStore store = StrataStore.open(dir)) {
                    out.println("keys: " + store.size());
                    out.println("sstables: " + tableCount(store)
                            + " over " + store.populatedLevelCount() + " levels");
                }
                return 0;

            default:
                return usage(err);
        }
    }

    /** The total number of on-disk tables across every populated level. */
    private static int tableCount(StrataStore store) {
        int total = 0;
        int deepest = store.deepestLevel();
        for (int level = 0; level <= deepest; level++) {
            total += store.tableCount(level);
        }
        return total;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(UTF_8);
    }

    private static String text(byte[] b) {
        return new String(b, UTF_8);
    }

    /** Prints the usage to {@code err} and returns the exit-2 status. */
    private static int usage(PrintStream err) {
        err.println("usage: strata <command> <dir> [args]");
        err.println("  put <dir> <key> <value>   store a pair, print ok");
        err.println("  get <dir> <key>           print the value, exit 1 if absent");
        err.println("  delete <dir> <key>        remove the key");
        err.println("  scan <dir> [from] [to]    print key\\tvalue lines over [from, to)");
        err.println("  compact <dir>             run a full compaction");
        err.println("  info <dir>                print the live key count and a short summary");
        err.println("keys and values are UTF-8 text, single line, no tabs or newlines");
        return 2;
    }
}

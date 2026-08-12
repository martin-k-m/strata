package dev.martinkm.strata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    /** Captured stdout, stderr and the exit status of one Cli.run call. */
    private record Run(int status, String out, String err) {
    }

    private static Run run(Path dir, String... rest) {
        // rest[0] is the command; the directory slots in as the second argument,
        // then any remaining args (key, value, bounds) follow.
        String[] args = new String[rest.length + 1];
        args[0] = rest[0];
        args[1] = dir.toString();
        System.arraycopy(rest, 1, args, 2, rest.length - 1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Cli.run(args, new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8));
        return new Run(status, out.toString(UTF_8), err.toString(UTF_8));
    }

    @Test
    void putThenGetRoundTrips(@TempDir Path dir) {
        Run put = run(dir, "put", "alpha", "one");
        assertEquals(0, put.status());

        Run get = run(dir, "get", "alpha");
        assertEquals(0, get.status());
        assertEquals("one\n", get.out().replace("\r\n", "\n"));
    }

    @Test
    void getOfAnAbsentKeyExitsOneWithNoOutput(@TempDir Path dir) {
        Run get = run(dir, "get", "missing");
        assertEquals(1, get.status());
        assertEquals("", get.out());
    }

    @Test
    void deleteRemovesTheKey(@TempDir Path dir) {
        run(dir, "put", "gone", "value");
        Run del = run(dir, "delete", "gone");
        assertEquals(0, del.status());

        Run get = run(dir, "get", "gone");
        assertEquals(1, get.status());
        assertEquals("", get.out());
    }

    @Test
    void scanWithoutBoundsPrintsSortedKeyValueLines(@TempDir Path dir) {
        run(dir, "put", "c", "3");
        run(dir, "put", "a", "1");
        run(dir, "put", "b", "2");

        Run scan = run(dir, "scan");
        assertEquals(0, scan.status());
        assertEquals("a\t1\nb\t2\nc\t3\n", scan.out().replace("\r\n", "\n"));
    }

    @Test
    void scanWithBoundsIsHalfOpen(@TempDir Path dir) {
        run(dir, "put", "a", "1");
        run(dir, "put", "b", "2");
        run(dir, "put", "c", "3");
        run(dir, "put", "d", "4");

        Run scan = run(dir, "scan", "b", "d");
        assertEquals(0, scan.status());
        // [b, d): b and c, not d.
        assertEquals("b\t2\nc\t3\n", scan.out().replace("\r\n", "\n"));
    }

    @Test
    void infoReportsTheLiveKeyCount(@TempDir Path dir) {
        run(dir, "put", "a", "1");
        run(dir, "put", "b", "2");

        Run info = run(dir, "info");
        assertEquals(0, info.status());
        assertTrue(info.out().contains("keys: 2"), info.out());
        assertTrue(info.out().contains("sstables:"), info.out());
    }

    @Test
    void unknownCommandPrintsUsageAndExitsTwo(@TempDir Path dir) {
        Run bad = run(dir, "frobnicate");
        assertEquals(2, bad.status());
        assertEquals("", bad.out());
        assertTrue(bad.err().contains("usage:"), bad.err());
    }

    @Test
    void wrongArgumentCountExitsTwo(@TempDir Path dir) {
        // put wants a key and a value; give it only a key.
        Run bad = run(dir, "put", "onlykey");
        assertEquals(2, bad.status());
        assertFalse(bad.err().isEmpty());
    }
}

package dev.martinkm.strata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * The durable record of which table files are live.
 *
 * <p>Table names carry a table's level and sequence, which is enough to lay out a
 * level structure, but a name says nothing about whether the file belongs to the
 * store at all. A compaction writes its outputs and then deletes its inputs, and
 * between those two steps both sets are on disk under valid names. Recovering from
 * the directory listing at that instant produces a level that holds two tables
 * covering the same key, one stale and one fresh, which is not a disjoint run and
 * which lets a lookup answer from the stale one. See docs/BUGS.md, STRATA-2.
 *
 * <p>The manifest is the set itself, written in one atomic step. A file that is
 * not named in it is not part of the store, however complete and well named it is.
 * That is what makes the two-step compaction safe: the outputs exist but are not
 * live until the manifest commits, and the inputs stop being live at that same
 * instant, whether or not their files have been deleted yet.
 *
 * <p>The format is text, one entry per line, with a CRC32 of everything above it:
 *
 * <pre>
 * strata-manifest 1
 * next-seq 12
 * sst-00-0000000009.sst
 * sst-01-0000000011.sst
 * crc 3f2a1b04
 * </pre>
 *
 * <p>Durability is temp file, fsync, atomic rename, fsync the directory. The rename
 * is what makes a reader see either the whole previous manifest or the whole new
 * one. The CRC is not guarding against a torn write, which the rename already rules
 * out; it guards against the file being truncated or altered by something outside
 * the store, and it makes a damaged manifest fail loudly instead of silently
 * dropping the tables whose lines went missing.
 */
final class Manifest {

    static final String FILE_NAME = "manifest";
    private static final String TEMP_NAME = "manifest.tmp";
    private static final String HEADER = "strata-manifest 1";
    private static final String SEQ_PREFIX = "next-seq ";
    private static final String CRC_PREFIX = "crc ";

    /** The live set as of the last commit. */
    record Snapshot(long nextSeq, List<String> names) {}

    private Manifest() {}

    /**
     * Reads the manifest, or empty if the store has none. A store written before
     * manifests existed has table files and no manifest, which is the one case a
     * caller may treat as "recover from the directory listing".
     *
     * @throws UncheckedIOException if the manifest exists but does not parse or
     *     fails its checksum, which means the set of live tables is unknown. That
     *     is not a state to guess at, so it stops the open.
     */
    static Optional<Snapshot> read(Path dir) {
        Path path = dir.resolve(FILE_NAME);
        if (!Files.exists(path)) return Optional.empty();

        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read manifest " + path, e);
        }
        if (lines.size() < 3) {
            throw new UncheckedIOException(new IOException("manifest is too short: " + path));
        }
        if (!HEADER.equals(lines.get(0))) {
            throw new UncheckedIOException(
                    new IOException("not a strata manifest, or a newer format: " + path));
        }

        String crcLine = lines.get(lines.size() - 1);
        if (!crcLine.startsWith(CRC_PREFIX)) {
            throw new UncheckedIOException(
                    new IOException("manifest has no checksum line: " + path));
        }
        long stated;
        try {
            stated = Long.parseLong(crcLine.substring(CRC_PREFIX.length()).trim(), 16);
        } catch (NumberFormatException e) {
            throw new UncheckedIOException(
                    new IOException("manifest checksum is not a number: " + path, e));
        }

        List<String> body = lines.subList(0, lines.size() - 1);
        long actual = checksum(body);
        if (actual != stated) {
            throw new UncheckedIOException(new IOException(String.format(
                    "manifest checksum mismatch in %s: stated %08x, computed %08x",
                    path, stated, actual)));
        }

        String seqLine = lines.get(1);
        if (!seqLine.startsWith(SEQ_PREFIX)) {
            throw new UncheckedIOException(
                    new IOException("manifest has no next-seq line: " + path));
        }
        long nextSeq;
        try {
            nextSeq = Long.parseLong(seqLine.substring(SEQ_PREFIX.length()).trim());
        } catch (NumberFormatException e) {
            throw new UncheckedIOException(
                    new IOException("manifest next-seq is not a number: " + path, e));
        }

        List<String> names = new ArrayList<>(body.subList(2, body.size()));
        return Optional.of(new Snapshot(nextSeq, names));
    }

    /**
     * Replaces the manifest with {@code names}, atomically. On return the new set is
     * durable: a crash immediately afterwards recovers to exactly this set.
     *
     * <p>Duplicates are collapsed and order is preserved, so a caller can pass the
     * level structure flattened without tidying it first.
     */
    static void write(Path dir, long nextSeq, Collection<String> names) {
        Set<String> unique = new LinkedHashSet<>(names);

        List<String> body = new ArrayList<>(unique.size() + 2);
        body.add(HEADER);
        body.add(SEQ_PREFIX + nextSeq);
        body.addAll(unique);

        StringBuilder text = new StringBuilder();
        for (String line : body) text.append(line).append('\n');
        text.append(CRC_PREFIX).append(String.format("%08x", checksum(body))).append('\n');
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);

        Path temp = dir.resolve(TEMP_NAME);
        Path target = dir.resolve(FILE_NAME);
        try {
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(java.nio.ByteBuffer.wrap(bytes));
                ch.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Nothing below can offer the guarantee this class exists to provide,
                // so it is a failure to report rather than a fallback to take.
                throw new UncheckedIOException(
                        "the filesystem under " + dir + " cannot rename atomically, so the "
                                + "manifest cannot be committed safely", e);
            }
            syncDirectory(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write manifest " + target, e);
        }
    }

    /**
     * Makes the rename itself durable. Without this the new manifest's contents are
     * on disk but the directory entry pointing at them may not be, so a crash can
     * recover to the previous manifest even though the write returned.
     *
     * <p>Opening a directory for read and forcing it is the portable way to do this
     * on POSIX. Windows refuses to open a directory as a channel and does not need
     * the call, so the failure is expected there and ignored.
     */
    private static void syncDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Expected on Windows. See the javadoc above.
        }
    }

    private static long checksum(List<String> body) {
        CRC32 crc = new CRC32();
        for (String line : body) {
            crc.update(line.getBytes(StandardCharsets.UTF_8));
            crc.update('\n');
        }
        return crc.getValue();
    }
}

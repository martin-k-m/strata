package dev.martinkm.strata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
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
import java.util.zip.CRC32;

/**
 * The set of live table names, replaced by an atomic rename so the set changes in
 * one step. A table file the manifest does not name is not part of the store.
 *
 * <p>Why the file names are not enough is in DECISIONS.md, and what it cost is
 * STRATA-2 in BUGS.md.
 */
final class Manifest {

    static final String FILE_NAME = "manifest";
    private static final String TEMP_NAME = "manifest.tmp";

    /** Retries of the commit rename, for the Windows sharing case in {@link #commit}. */
    private static final int COMMIT_ATTEMPTS = 50;

    private static final long COMMIT_RETRY_MILLIS = 20;
    private static final String HEADER = "strata-manifest 1";
    private static final String SEQ_PREFIX = "next-seq ";
    private static final String CRC_PREFIX = "crc ";

    record Snapshot(long nextSeq, List<String> names) {}

    private Manifest() {}

    /**
     * Empty only for a store written before manifests existed, which is the one
     * case a caller may answer from the directory listing. A manifest that does not
     * parse throws instead, because falling back to the listing is the bug.
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
        if (lines.size() < 3 || !HEADER.equals(lines.get(0))) {
            throw damaged(path, "not a strata manifest, or a newer format");
        }

        List<String> body = lines.subList(0, lines.size() - 1);
        long stated = parse(path, lines.get(lines.size() - 1), CRC_PREFIX, 16);
        long actual = checksum(body);
        if (actual != stated) {
            throw damaged(path, String.format(
                    "checksum mismatch: stated %08x, computed %08x", stated, actual));
        }

        long nextSeq = parse(path, lines.get(1), SEQ_PREFIX, 10);
        return Optional.of(new Snapshot(nextSeq, new ArrayList<>(body.subList(2, body.size()))));
    }

    /**
     * Replaces the manifest. On return the new set is durable, so a crash
     * immediately afterwards recovers to exactly it. Duplicates are collapsed.
     */
    static void write(Path dir, long nextSeq, Collection<String> names) {
        List<String> body = new ArrayList<>();
        body.add(HEADER);
        body.add(SEQ_PREFIX + nextSeq);
        body.addAll(new LinkedHashSet<>(names));

        StringBuilder text = new StringBuilder();
        for (String line : body) text.append(line).append('\n');
        text.append(CRC_PREFIX).append(String.format("%08x", checksum(body))).append('\n');

        Path temp = dir.resolve(TEMP_NAME);
        Path target = dir.resolve(FILE_NAME);
        try {
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8)));
                ch.force(true);
            }
            commit(temp, target);
            syncDirectory(dir);
        } catch (AtomicMoveNotSupportedException e) {
            throw new UncheckedIOException(
                    "the filesystem under " + dir + " cannot rename atomically", e);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write manifest " + target, e);
        }
    }

    /**
     * Replaces the manifest with the temp file in one step.
     *
     * <p>On Windows a rename over a file someone else has open for reading fails with
     * an access-denied error, where on Linux it succeeds and the reader goes on
     * reading the file it already has. Anything can be that reader: a backup, an
     * indexer, a virus scanner, another process looking at the store. The share is
     * brief, so this retries rather than failing a commit that would have worked a
     * moment later, and gives up loudly instead of retrying forever.
     */
    private static void commit(Path temp, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException e) {
                if (attempt >= COMMIT_ATTEMPTS) throw e;
                try {
                    Thread.sleep(COMMIT_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Without this the rename itself can be lost, and the write undone by a crash. */
    private static void syncDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows refuses to open a directory and does not need the call.
        }
    }

    private static long parse(Path path, String line, String prefix, int radix) {
        if (!line.startsWith(prefix)) throw damaged(path, "expected a line starting " + prefix);
        try {
            return Long.parseLong(line.substring(prefix.length()).trim(), radix);
        } catch (NumberFormatException e) {
            throw damaged(path, "malformed " + prefix.trim() + " line");
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

    private static UncheckedIOException damaged(Path path, String why) {
        return new UncheckedIOException(new IOException(path + ": " + why));
    }
}

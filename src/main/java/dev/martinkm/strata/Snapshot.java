package dev.martinkm.strata;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A read-only view of the store as it stood when the snapshot was taken.
 *
 * <p>Reads through a snapshot answer from that moment and no later one. A write
 * made after it was taken is not visible through it, however long it is held,
 * and neither is a delete: a key the snapshot could see stays readable through
 * the snapshot after the store has forgotten it.
 *
 * <p>This is what {@link Store#scan} could not offer. A scan already pins the
 * tables it walks, but only for as long as one stream is open, and it sees
 * whatever the memtable happens to hold as it goes. A snapshot is the same
 * pinning made explicit and given a lifetime the caller controls, so two reads
 * separated by a write agree with each other.
 *
 * <h2>What it costs</h2>
 *
 * <p>A snapshot keeps every on-disk table it can see alive. A compaction that
 * runs while one is open still publishes its new structure and still serves new
 * readers from it, but the tables the snapshot holds are not deleted until it is
 * closed, so a long-lived snapshot keeps a copy of superseded data on disk. It
 * also copies the memtable as it stood, which is bounded by the flush threshold
 * rather than by the size of the store.
 *
 * <p>So: close it. It is {@link AutoCloseable} for that reason, and a snapshot
 * that is never closed is a disk leak rather than a correctness problem.
 */
public interface Snapshot extends AutoCloseable {

    /**
     * The value bound to {@code key} at the moment the snapshot was taken, or
     * empty if the key was absent or deleted then.
     */
    Optional<byte[]> get(byte[] key);

    /**
     * The live pairs with key in {@code [from, to)} as of the snapshot, in
     * ascending unsigned-lexicographic key order, with the same bound and
     * ordering rules as {@link Store#scan}.
     *
     * <p>The returned stream does not need closing separately; the snapshot owns
     * the tables. Closing the stream is harmless.
     */
    Stream<Map.Entry<byte[], byte[]>> scan(byte[] from, byte[] to);

    /**
     * Releases the tables this snapshot pinned. Idempotent, and it does not
     * throw: there is nothing a caller could usefully do about a failure here,
     * and a close that throws inside try-with-resources hides the exception that
     * brought you there.
     */
    @Override
    void close();
}

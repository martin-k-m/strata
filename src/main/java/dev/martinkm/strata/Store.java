package dev.martinkm.strata;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * An ordered, durable key-value store.
 *
 * Keys and values are opaque byte strings; the store never interprets them. A
 * key that has been {@link #put} is readable by {@link #get} until it is
 * {@link #delete}d, and every write survives a crash the moment the call that
 * made it returns. That durability guarantee is the whole point of the write
 * ahead log underneath.
 */
public interface Store extends AutoCloseable {

    /** Associates {@code value} with {@code key}, replacing any previous value. */
    void put(byte[] key, byte[] value);

    /** The value bound to {@code key}, or empty if it is absent or deleted. */
    Optional<byte[]> get(byte[] key);

    /** Removes {@code key}. A subsequent {@link #get} returns empty. */
    void delete(byte[] key);

    /**
     * The live key-value pairs with key in {@code [from, to)}, in ascending
     * unsigned-lexicographic key order. A {@code null} {@code from} starts at the
     * first key, a {@code null} {@code to} runs to the last. A reversed or empty
     * range yields no entries.
     *
     * <p>The scan merges the memtable and every SSTable, keeps the newest value for
     * each key and drops keys whose newest record is a tombstone. It is a k-way
     * merge over one iterator per layer, so it does not materialize the whole store
     * in memory; it holds one entry per layer at a time. The keys and values in the
     * returned entries are fresh copies the caller may keep or mutate.
     *
     * <p>The scan reads a snapshot of the layers taken when this method is called.
     * Because it is lazy, it should not run alongside a compaction that closes the
     * tables underneath it; the store is single-writer, so callers already serialize
     * against that.
     */
    Stream<Map.Entry<byte[], byte[]>> scan(byte[] from, byte[] to);

    /** The number of live keys. */
    long size();

    @Override
    void close();
}

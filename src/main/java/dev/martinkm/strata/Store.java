package dev.martinkm.strata;

import java.util.Optional;

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

    /** The number of live keys. */
    long size();

    @Override
    void close();
}

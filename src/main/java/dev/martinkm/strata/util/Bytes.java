package dev.martinkm.strata.util;

import java.util.Arrays;

/**
 * An immutable, comparable key.
 *
 * A raw {@code byte[]} cannot be a map key: its {@code equals} is identity and
 * its ordering is undefined, and an LSM engine needs both value equality and a
 * total order to keep keys sorted. {@code Bytes} defends a private copy on the
 * way in and hands one out on {@link #toArray()}, so a caller mutating its own
 * buffer can never reach inside a key already stored.
 *
 * <p>The order is unsigned lexicographic, the same order RocksDB and LevelDB use
 * by default, so {@code 0xFF} sorts after {@code 0x01} rather than before it as
 * signed {@code byte} comparison would have it.
 */
public final class Bytes implements Comparable<Bytes> {

    private final byte[] data;
    private final int hash;

    private Bytes(byte[] data) {
        this.data = data;
        this.hash = Arrays.hashCode(data);
    }

    /** Wraps a defensive copy of {@code data}. */
    public static Bytes copyOf(byte[] data) {
        return new Bytes(data.clone());
    }

    /** Wraps {@code data} without copying. Callers must not mutate it afterward. */
    public static Bytes wrap(byte[] data) {
        return new Bytes(data);
    }

    /** A fresh copy of the underlying bytes; safe to mutate. */
    public byte[] toArray() {
        return data.clone();
    }

    public int length() {
        return data.length;
    }

    @Override
    public int compareTo(Bytes other) {
        return Arrays.compareUnsigned(this.data, other.data);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Bytes b && Arrays.equals(this.data, b.data);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "Bytes[" + data.length + "]";
    }
}

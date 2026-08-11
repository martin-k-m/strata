package dev.martinkm.strata;

import java.util.Arrays;

/**
 * A bloom filter over a table's keys, so a read can skip a table that provably
 * does not hold the key.
 *
 * The filter answers one question: "is this key definitely absent?" A negative
 * is exact, so a miss lets {@code get} avoid touching the table's index and data
 * at all. A positive may be wrong (a false positive), which only costs a wasted
 * lookup, never a wrong answer. That asymmetry is the whole point: the memtable
 * and the sparse index still decide correctness, the bloom filter only trims
 * work off the common case of a key that is not present.
 *
 * <p>Bits are set by double hashing: two 64-bit hashes of the key are combined as
 * {@code h1 + i*h2} to derive {@code k} independent-enough positions, the standard
 * trick that avoids computing {@code k} separate hashes without measurably worse
 * false-positive rates. Sizing targets ten bits per key, which puts the false
 * positive rate near one percent.
 */
final class BloomFilter {

    private final long[] words;
    private final int numBits;
    private final int numHashes;

    private BloomFilter(long[] words, int numBits, int numHashes) {
        this.words = words;
        this.numBits = numBits;
        this.numHashes = numHashes;
    }

    /** Builds a filter sized for {@code expectedKeys} at roughly ten bits per key. */
    static BloomFilter create(int expectedKeys) {
        // At least one bit so the modulo is well defined even for an empty table.
        int bits = Math.max(64, expectedKeys * 10);
        int words = (bits + 63) / 64;
        return new BloomFilter(new long[words], words * 64, 7);
    }

    /** Rehydrates a filter from its on-disk form. */
    static BloomFilter load(int numBits, int numHashes, long[] words) {
        return new BloomFilter(words, numBits, numHashes);
    }

    void add(byte[] key) {
        long h1 = hash1(key);
        long h2 = hash2(key);
        for (int i = 0; i < numHashes; i++) {
            int bit = index(h1, h2, i);
            words[bit >>> 6] |= 1L << (bit & 63);
        }
    }

    /**
     * True if the key may be present. A false result means the key is certainly
     * absent, which is the useful direction.
     */
    boolean mightContain(byte[] key) {
        long h1 = hash1(key);
        long h2 = hash2(key);
        for (int i = 0; i < numHashes; i++) {
            int bit = index(h1, h2, i);
            if ((words[bit >>> 6] & (1L << (bit & 63))) == 0) return false;
        }
        return true;
    }

    private int index(long h1, long h2, int i) {
        long combined = h1 + (long) i * h2;
        // Mask off the sign so the modulo stays in range for a non-negative bit.
        return (int) (Long.remainderUnsigned(combined, numBits));
    }

    int numBits() {
        return numBits;
    }

    int numHashes() {
        return numHashes;
    }

    long[] words() {
        return words;
    }

    // FNV-1a over the key bytes, and a second hash that perturbs the basis so the
    // two are independent enough for double hashing.
    private static long hash1(byte[] key) {
        long h = 0xcbf29ce484222325L;
        for (byte b : key) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static long hash2(byte[] key) {
        long h = 0x9e3779b97f4a7c15L;
        for (byte b : key) {
            h = (h ^ (b & 0xff)) * 0xff51afd7ed558ccdL;
        }
        return h;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BloomFilter b
                && numBits == b.numBits
                && numHashes == b.numHashes
                && Arrays.equals(words, b.words);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(words);
    }
}

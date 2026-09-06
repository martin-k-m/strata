package dev.martinkm.strata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Block compression.
 *
 * README.md listed this under "Not done yet": "Blocks are checksummed and cached
 * now, but they are stored uncompressed, so the on-disk size is the raw key and
 * value bytes with no attempt to shrink them."
 *
 * Two things have to hold and they pull against each other. Compressible data
 * must actually get smaller, or the feature does nothing. Incompressible data
 * must not get meaningfully bigger, which is why the codec is chosen per block
 * rather than for the file: a deflater handed random bytes returns more bytes
 * than it was given, and a writer that stored that unconditionally would have
 * made the format worse for the exact workload least able to afford it.
 */
class StrataCompressionTest {

    private static byte[] k(String s) {
        return s.getBytes(UTF_8);
    }

    private static long bytesOnDisk(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            long total = 0;
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".sst")).toList()) {
                total += Files.size(p);
            }
            return total;
        }
    }

    /** Highly compressible: the same byte over and over. */
    private static byte[] repetitive(int bytes) {
        byte[] v = new byte[bytes];
        java.util.Arrays.fill(v, (byte) 'a');
        return v;
    }

    /**
     * A random key that still sorts distinctly, for a block with no structure in
     * it at all. The counter prefix keeps the keys unique without making them
     * compressible: sixteen random bytes dominate four of counter.
     */
    private static byte[] randomKey(Random rng, int i) {
        byte[] key = new byte[20];
        rng.nextBytes(key);
        key[0] = (byte) (i >>> 24);
        key[1] = (byte) (i >>> 16);
        key[2] = (byte) (i >>> 8);
        key[3] = (byte) i;
        return key;
    }

    /** Incompressible: a deflater cannot do anything with these. */
    private static byte[] random(Random rng, int bytes) {
        byte[] v = new byte[bytes];
        rng.nextBytes(v);
        return v;
    }

    @Test
    void repetitiveValuesTakeFarLessSpaceThanTheyOccupyInMemory(@TempDir Path dir) throws Exception {
        int entries = 200;
        int valueSize = 512;
        try (StrataStore store = StrataStore.open(dir, 1_000_000)) {
            for (int i = 0; i < entries; i++) {
                store.put(k(String.format("key%04d", i)), repetitive(valueSize));
            }
            store.flush();
        }
        long raw = (long) entries * valueSize;
        long onDisk = bytesOnDisk(dir);
        assertTrue(onDisk < raw / 4,
                "compressible data was not compressed: " + onDisk + " bytes on disk for " + raw + " bytes of values");
    }

    /**
     * The codec byte of the first data block, read straight out of the file.
     *
     * <p>The layout is {@code [storedLen: int][crc32: int][codec: byte]...} and
     * the first data block sits at offset 0, so this is the writer's actual
     * per-block decision rather than an inference from the file size.
     */
    private static byte firstBlockCodec(Path dir) throws Exception {
        Path table;
        try (Stream<Path> files = Files.list(dir)) {
            table = files.filter(p -> p.getFileName().toString().endsWith(".sst")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(table);
        return bytes[8];
    }

    /**
     * The stored bytes of the first block, and the raw bytes they decode to.
     *
     * <p>Layout: {@code [storedLen: int][crc32: int][codec: byte][rawLen: int]},
     * with the first data block at offset 0. {@code storedLen} covers the codec
     * byte and the rawLen field as well as the bytes themselves, so the payload
     * actually written is {@code storedLen - 5}.
     */
    private static long[] firstBlockSizes(Path dir) throws Exception {
        Path table;
        try (Stream<Path> files = Files.list(dir)) {
            table = files.filter(p -> p.getFileName().toString().endsWith(".sst")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(table);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes);
        int storedLen = b.getInt(0);
        int rawLen = b.getInt(9);
        return new long[] {storedLen - 5L, rawLen};
    }

    @Test
    void aBlockIsNeverStoredLargerThanItsRawBytes(@TempDir Path dir) throws Exception {
        // The guarantee the per-block codec choice exists to make, checked on the
        // shape least likely to keep it: random keys and random values.
        //
        // What this test was FIRST written to assert, and why it changed: it
        // asserted the codec byte was "none" for random data, and failed. Raw
        // deflate expands incompressible input by only a fraction of a percent,
        // and a block's repeated length prefixes compress by more than that, so a
        // block of entirely random keys and values still comes out five bytes
        // smaller and is stored deflated. The codec choice is genuinely per block
        // rather than per value, and the invariant worth pinning is not which
        // codec wins but that the loser is never stored.
        Random rng = new Random(12345);
        try (StrataStore store = StrataStore.open(dir, 1_000_000)) {
            for (int i = 0; i < 200; i++) {
                store.put(randomKey(rng, i), random(rng, 512));
            }
            store.flush();
        }
        long[] sizes = firstBlockSizes(dir);
        assertTrue(sizes[0] <= sizes[1],
                "the block was stored larger than its raw bytes: " + sizes[0] + " stored for " + sizes[1] + " raw");
    }

    @Test
    void aCompressibleBlockIsStoredCompressed(@TempDir Path dir) throws Exception {
        try (StrataStore store = StrataStore.open(dir, 1_000_000)) {
            for (int i = 0; i < 200; i++) {
                store.put(k(String.format("key%04d", i)), repetitive(512));
            }
            store.flush();
        }
        assertEquals(1, firstBlockCodec(dir), "a highly compressible block was stored raw");
    }

    @Test
    void everyValueReadsBackExactlyAfterCompression(@TempDir Path dir) {
        // The property that matters more than any size: compression is only worth
        // having if it is invisible. Mixed compressible and incompressible values
        // in one store, so both codecs are exercised in the same file.
        Random rng = new Random(99);
        byte[][] expected = new byte[120][];
        try (StrataStore store = StrataStore.open(dir, 1_000_000)) {
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (i % 2 == 0) ? repetitive(300 + i) : random(rng, 300 + i);
                store.put(k(String.format("key%04d", i)), expected[i]);
            }
            store.flush();
            for (int i = 0; i < expected.length; i++) {
                assertArrayEquals(expected[i], store.get(k(String.format("key%04d", i))).orElseThrow(),
                        "key" + i + " did not survive the round trip");
            }
        }
        // And again from a cold open, so the values come off disk rather than out
        // of the block cache the first pass populated.
        try (StrataStore reopened = StrataStore.open(dir, 1_000_000)) {
            for (int i = 0; i < expected.length; i++) {
                assertArrayEquals(expected[i], reopened.get(k(String.format("key%04d", i))).orElseThrow(),
                        "key" + i + " did not survive a reopen");
            }
        }
    }

    @Test
    void aScanOverCompressedBlocksIsStillOrderedAndComplete(@TempDir Path dir) {
        try (StrataStore store = StrataStore.open(dir, 1_000_000)) {
            for (int i = 0; i < 150; i++) {
                store.put(k(String.format("key%04d", i)), repetitive(200));
            }
            store.flush();
            java.util.List<String> keys = new java.util.ArrayList<>();
            try (Stream<java.util.Map.Entry<byte[], byte[]>> s = store.scan(null, null)) {
                s.forEach(e -> keys.add(new String(e.getKey(), UTF_8)));
            }
            assertEquals(150, keys.size(), "the scan lost rows across compressed blocks");
            for (int i = 1; i < keys.size(); i++) {
                assertTrue(keys.get(i - 1).compareTo(keys.get(i)) < 0, "the scan came back out of order");
            }
        }
    }

    @Test
    void aCorruptCompressedBlockIsAChecksumFailureRatherThanADecompressionOne(@TempDir Path dir) throws Exception {
        // The reason the checksum covers the stored bytes rather than the decoded
        // ones: damage is caught before the inflater ever sees it, so the error
        // names the table and offset instead of surfacing as a zlib complaint.
        try (StrataStore store = StrataStore.open(dir, 1_000_000)) {
            for (int i = 0; i < 100; i++) {
                store.put(k(String.format("key%04d", i)), repetitive(256));
            }
            store.flush();
        }
        Path table;
        try (Stream<Path> files = Files.list(dir)) {
            table = files.filter(p -> p.getFileName().toString().endsWith(".sst")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(table);
        // Flip a bit inside the first block's stored region, past the 8-byte header.
        bytes[20] ^= 0x40;
        Files.write(table, bytes);

        try (StrataStore reopened = StrataStore.open(dir, 1_000_000)) {
            ChecksumException thrown = null;
            try {
                for (int i = 0; i < 100; i++) {
                    reopened.get(k(String.format("key%04d", i)));
                }
            } catch (ChecksumException e) {
                thrown = e;
            }
            assertTrue(thrown != null, "a flipped bit in a compressed block was not caught");
        }
    }
}

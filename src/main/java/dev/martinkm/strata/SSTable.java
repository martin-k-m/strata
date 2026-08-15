package dev.martinkm.strata;

import dev.martinkm.strata.util.Bytes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * An immutable, sorted on-disk table: one flushed generation of the memtable.
 *
 * A table is written once, in a single pass over keys already in
 * unsigned-lexicographic order, and never mutated after. That immutability is
 * what lets reads run without locks and lets compaction reason about a table as
 * a fixed snapshot.
 *
 * <h2>File layout</h2>
 *
 * <pre>
 *   data:   [block]...                        every key, in sorted order, grouped into blocks
 *             block = [payloadLen: int][crc32: int][entry...]
 *                     crc32 is over the payload; a block holds up to INDEX_INTERVAL entries
 *             entry = [keyLen: int][key][valLen: int][value]
 *                     valLen = -1 marks a tombstone (a delete), which carries no value
 *   index:  [entry]...                        one entry per data block
 *             entry = [keyLen: int][key][offset: long]   offset points at the block header
 *   bloom:  [numBits: int][numHashes: int][word: long]...
 *   footer: [dataLen: long][indexOffset: long][indexCount: int]
 *           [bloomOffset: long][entryCount: int][magic: long]
 * </pre>
 *
 * The footer is a fixed {@value #FOOTER_LEN} bytes at the very end, so opening a
 * table is: read the footer, then the (small, sparse) index and the bloom filter
 * into memory. The data blocks stay on disk and are touched only on a lookup that
 * the bloom filter did not rule out.
 *
 * <p>The index is <em>sparse</em> on purpose: holding every key's offset in memory
 * would defeat the point of spilling to disk, so a lookup floors to the block that
 * may hold the key and reads that one block. A block is the read unit and the unit
 * a checksum covers, so a lookup reads and verifies exactly one block. A corrupt
 * block raises a {@link ChecksumException} naming the table and offset rather than
 * returning bytes that no longer match what was written.
 *
 * <p>Reads and a full scan are the only operations; there is no in-place update.
 * Decoded blocks are cached in a {@link BlockCache} keyed by table and offset, so a
 * repeated read of a hot key does not re-read or re-verify the same block.
 */
final class SSTable {

    /** Data entries per block, and so the cadence of the sparse index: one entry per block. */
    static final int INDEX_INTERVAL = 16;

    /** Value length written for a tombstone. A real value is never negative length. */
    private static final int TOMBSTONE_LEN = -1;

    // The magic doubles as a format version. STRATA2 is the block-checksummed layout;
    // a STRATA1 table (unblocked, no per-block checksum) is a different format and is
    // rejected by open() rather than misread.
    private static final long MAGIC = 0x53545241544132L; // "STRATA2"
    private static final int FOOTER_LEN = 8 + 8 + 4 + 8 + 4 + 8;

    /** Assigns each opened table a distinct identity, used to key its blocks in the cache. */
    private static final AtomicLong NEXT_ID = new AtomicLong();

    /**
     * The outcome of a point lookup in one table. Absent and tombstone are
     * distinct: a tombstone is a positive answer that the key was deleted here,
     * which must stop the search so an older table's stale value never surfaces.
     */
    static final class Result {
        static final Result ABSENT = new Result(null, false);
        static final Result TOMBSTONE = new Result(null, true);

        private final byte[] value;
        private final boolean present;

        private Result(byte[] value, boolean present) {
            this.value = value;
            this.present = present;
        }

        static Result of(byte[] value) {
            return new Result(value, true);
        }

        /** True if this table answered the lookup, whether with a value or a tombstone. */
        boolean isPresent() {
            return present;
        }

        /** True if the answer is a delete. Only meaningful when {@link #isPresent()}. */
        boolean isTombstone() {
            return present && value == null;
        }

        byte[] value() {
            return value;
        }
    }

    /** One key and its value, or a tombstone, as read back from a table. */
    record Entry(Bytes key, byte[] value, boolean tombstone) {}

    /**
     * A decoded data block: its entries in key order and the offset just past it in
     * the data region, which is where the next block begins. This is what the cache
     * holds, so a cached read touches no disk.
     */
    record Block(Entry[] entries, long endOffset) {}

    /**
     * One for the store's own hold on the table, plus one per reader currently
     * inside it. The file is closed, and deleted if it was retired, when this
     * reaches zero. See {@link #acquire}.
     */
    private final java.util.concurrent.atomic.AtomicInteger refs =
            new java.util.concurrent.atomic.AtomicInteger(1);

    /** Set by {@link #retire}: this table was compacted away, so its file should go. */
    private volatile boolean deleteWhenUnused;

    private final FileChannel channel;
    private final Path path;
    private final BlockCache cache;
    private final long id;
    private final BloomFilter bloom;
    private final Bytes[] indexKeys;
    private final long[] indexOffsets;
    private final long dataLen;
    private final int entryCount;
    private final Bytes firstKey;
    private final Bytes lastKey;

    private SSTable(FileChannel channel, Path path, BlockCache cache, long id, BloomFilter bloom,
                    Bytes[] indexKeys, long[] indexOffsets, long dataLen, int entryCount,
                    Bytes firstKey, Bytes lastKey) {
        this.channel = channel;
        this.path = path;
        this.cache = cache;
        this.id = id;
        this.bloom = bloom;
        this.indexKeys = indexKeys;
        this.indexOffsets = indexOffsets;
        this.dataLen = dataLen;
        this.entryCount = entryCount;
        this.firstKey = firstKey;
        this.lastKey = lastKey;
    }

    /**
     * Writes {@code entries} (already in ascending key order, tombstones carrying
     * a null value) to a new immutable table at {@code path}.
     *
     * The data is grouped into blocks of at most {@link #INDEX_INTERVAL} entries.
     * Each block is prefixed with its length and a CRC32 over its bytes, and the
     * sparse index holds one entry per block pointing at the block header. The table
     * is built into a temporary sibling file, fsynced, then atomically renamed into
     * place. A crash therefore leaves either no table or a complete one, never a
     * half-written file that a later open would trip over.
     */
    static void write(Path path, List<Map.Entry<Bytes, byte[]>> entries) {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        BloomFilter bloom = BloomFilter.create(entries.size());

        List<byte[]> indexKeys = new ArrayList<>();
        List<Long> indexOffsets = new ArrayList<>();

        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            long offset = 0;
            int inBlock = 0;
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            for (Map.Entry<Bytes, byte[]> e : entries) {
                byte[] key = e.getKey().toArray();
                byte[] value = e.getValue();
                bloom.add(key);
                if (inBlock == INDEX_INTERVAL) {
                    offset += writeBlock(ch, payload.toByteArray());
                    payload.reset();
                    inBlock = 0;
                }
                if (inBlock == 0) { // the first key of a block anchors the index
                    indexKeys.add(key);
                    indexOffsets.add(offset);
                }
                encodeEntry(payload, key, value);
                inBlock++;
            }
            if (inBlock > 0) {
                offset += writeBlock(ch, payload.toByteArray());
            }
            long dataLen = offset;

            long indexOffset = offset;
            for (int j = 0; j < indexKeys.size(); j++) {
                byte[] key = indexKeys.get(j);
                ByteBuffer buf = ByteBuffer.allocate(4 + key.length + 8);
                buf.putInt(key.length).put(key).putLong(indexOffsets.get(j)).flip();
                offset += buf.remaining();
                writeFully(ch, buf);
            }

            long bloomOffset = offset;
            long[] words = bloom.words();
            ByteBuffer bloomBuf = ByteBuffer.allocate(4 + 4 + words.length * 8);
            bloomBuf.putInt(bloom.numBits()).putInt(bloom.numHashes());
            for (long w : words) bloomBuf.putLong(w);
            bloomBuf.flip();
            writeFully(ch, bloomBuf);

            ByteBuffer footer = ByteBuffer.allocate(FOOTER_LEN);
            footer.putLong(dataLen)
                  .putLong(indexOffset)
                  .putInt(indexKeys.size())
                  .putLong(bloomOffset)
                  .putInt(entries.size())
                  .putLong(MAGIC)
                  .flip();
            writeFully(ch, footer);

            ch.force(true); // the table's bytes must be durable before it is named
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot write sstable " + path, ex);
        }

        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot publish sstable " + path, ex);
        }
    }

    /** Opens an existing table, loading its index and bloom filter into memory. */
    static SSTable open(Path path, BlockCache cache) {
        try {
            FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
            long size = ch.size();

            ByteBuffer footer = readAt(ch, size - FOOTER_LEN, FOOTER_LEN);
            footer.flip();
            long dataLen = footer.getLong();
            long indexOffset = footer.getLong();
            int indexCount = footer.getInt();
            long bloomOffset = footer.getLong();
            int entryCount = footer.getInt();
            long magic = footer.getLong();
            if (magic != MAGIC) {
                throw new IOException("unrecognized sstable format (bad magic) " + path);
            }

            int indexLen = (int) (bloomOffset - indexOffset);
            ByteBuffer index = readAt(ch, indexOffset, indexLen);
            index.flip();
            Bytes[] indexKeys = new Bytes[indexCount];
            long[] indexOffsets = new long[indexCount];
            for (int i = 0; i < indexCount; i++) {
                byte[] key = new byte[index.getInt()];
                index.get(key);
                indexKeys[i] = Bytes.wrap(key);
                indexOffsets[i] = index.getLong();
            }

            int bloomLen = (int) (size - FOOTER_LEN - bloomOffset);
            ByteBuffer bloomBuf = readAt(ch, bloomOffset, bloomLen);
            bloomBuf.flip();
            int numBits = bloomBuf.getInt();
            int numHashes = bloomBuf.getInt();
            long[] words = new long[(bloomLen - 8) / 8];
            for (int i = 0; i < words.length; i++) words[i] = bloomBuf.getLong();
            BloomFilter bloom = BloomFilter.load(numBits, numHashes, words);

            // The first and last keys bound the table's range, which the levels use to
            // keep runs disjoint and to test two tables for overlap. The first key is
            // the first indexed key; the last is the last entry of the last block.
            Bytes firstKey = (indexCount > 0) ? indexKeys[0] : null;
            Bytes lastKey = null;
            if (entryCount > 0) {
                Entry[] lastEntries = readBlockRaw(ch, path, indexOffsets[indexCount - 1]).entries();
                lastKey = lastEntries[lastEntries.length - 1].key();
            }

            long id = NEXT_ID.getAndIncrement();
            return new SSTable(ch, path, cache, id, bloom, indexKeys, indexOffsets, dataLen,
                    entryCount, firstKey, lastKey);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot open sstable " + path, ex);
        }
    }

    Path path() {
        return path;
    }

    int entryCount() {
        return entryCount;
    }

    /** The smallest key in the table, or null if it is empty. */
    Bytes firstKey() {
        return firstKey;
    }

    /** The largest key in the table, or null if it is empty. */
    Bytes lastKey() {
        return lastKey;
    }

    /** The length of the data block in bytes, a cheap proxy for the table's size. */
    long dataLen() {
        return dataLen;
    }

    /**
     * Looks up {@code key}. Returns {@link Result#ABSENT} if this table does not
     * hold the key, a value result if it does, or {@link Result#TOMBSTONE} if the
     * key was deleted in this table.
     */
    Result get(byte[] key) {
        // The bloom filter turns most misses into no disk work at all.
        if (!bloom.mightContain(key)) return Result.ABSENT;
        if (entryCount == 0) return Result.ABSENT;

        Bytes target = Bytes.wrap(key);
        // The floor offset names the one block that could hold the key: the next
        // indexed key is strictly greater, so a present key sits in this block.
        Block block = readBlock(floorOffset(target));
        for (Entry e : block.entries()) {
            int cmp = e.key().compareTo(target);
            if (cmp == 0) {
                return e.tombstone() ? Result.TOMBSTONE : Result.of(e.value().clone());
            }
            if (cmp > 0) return Result.ABSENT; // sorted: we have passed where it would be
        }
        return Result.ABSENT;
    }

    /**
     * A one-pass reader over every entry in key order, tombstones included.
     * Compaction uses it to merge tables; it is not a general cursor and reads whole
     * blocks through the shared channel, so it is single-threaded by construction.
     */
    Iterator scan() {
        return new Iterator(0, null, null);
    }

    /**
     * A reader over entries with key in {@code [from, to)} in key order, tombstones
     * included. A {@code null} bound is open on that side. The sparse index seeks to
     * the block holding {@code from} so a bounded scan does not read the whole table.
     */
    Iterator scan(byte[] from, byte[] to) {
        long start = (from == null) ? 0 : floorOffset(Bytes.wrap(from));
        return new Iterator(start, from, to);
    }

    final class Iterator {
        private long blockOffset;
        private Block block;
        private int idx;
        private boolean done;
        private final Bytes from;
        private final Bytes to;
        private Entry buffered;

        Iterator(long startBlockOffset, byte[] from, byte[] to) {
            this.blockOffset = startBlockOffset;
            this.from = (from == null) ? null : Bytes.wrap(from);
            this.to = (to == null) ? null : Bytes.wrap(to);
            buffered = readNextInRange();
        }

        boolean hasNext() {
            return buffered != null;
        }

        Entry next() {
            if (buffered == null) throw new NoSuchElementException();
            Entry e = buffered;
            buffered = readNextInRange();
            return e;
        }

        /**
         * Walks block by block to the next entry whose key falls in range, or null
         * past the end. The starting block can begin before {@code from}, so entries
         * below it are skipped; the first key at or above {@code to} ends the scan.
         */
        private Entry readNextInRange() {
            while (true) {
                if (done) return null;
                if (block == null || idx >= block.entries().length) {
                    if (blockOffset >= dataLen) {
                        done = true;
                        return null;
                    }
                    block = readBlock(blockOffset);
                    blockOffset = block.endOffset();
                    idx = 0;
                    continue;
                }
                Entry entry = block.entries()[idx++];
                Bytes k = entry.key();
                if (from != null && k.compareTo(from) < 0) continue; // block started early
                if (to != null && k.compareTo(to) >= 0) {            // past the range
                    done = true;
                    return null;
                }
                return entry;
            }
        }
    }

    /**
     * Takes a reference, or reports that the table is already gone.
     *
     * <p>A reader snapshots the level structure and then reads through the tables
     * it found, and a compaction can retire one of those tables in between. The
     * reader has no lock to stop it and should not want one, so instead it says
     * it is using the table, and gets told if it is too late. Too late is not an
     * error: the table's contents were merged into the new structure before it
     * was retired, so retaking the snapshot finds them.
     *
     * @return false if the last reference has already gone, in which case the
     *         caller holds nothing and must not read
     */
    boolean acquire() {
        for (; ; ) {
            int current = refs.get();
            if (current == 0) {
                return false;
            }
            if (refs.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Gives back a reference taken by {@link #acquire}, or the store's own. */
    void release() {
        if (refs.decrementAndGet() == 0) {
            closeForGood();
        }
    }

    /**
     * Drops the store's reference and marks the file for deletion, which happens
     * once the last reader still inside the table has left.
     *
     * <p>The alternative, deleting as soon as the table leaves the level
     * structure, is what this replaced: a reader part way through it then read a
     * closed channel and got an {@code UncheckedIOException} for a key that was
     * present the whole time.
     */
    void retire() {
        deleteWhenUnused = true;
        release();
    }

    /** Drops the store's reference without deleting anything. For closing the store. */
    void close() {
        release();
    }

    private void closeForGood() {
        cache.invalidate(id); // a retired table's blocks must never be served again
        try {
            channel.close();
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot close sstable " + path, ex);
        }
        if (deleteWhenUnused) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ex) {
                throw new UncheckedIOException("cannot remove compacted sstable " + path, ex);
            }
        }
    }

    /** The offset of the block whose first key is the greatest indexed key <= target, else 0. */
    private long floorOffset(Bytes target) {
        // Binary search the sparse index for the greatest key <= target.
        int lo = 0, hi = indexKeys.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (indexKeys[mid].compareTo(target) <= 0) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans < 0 ? 0 : indexOffsets[ans];
    }

    /**
     * Returns the decoded block at {@code offset}, from the cache if it is there or
     * from disk otherwise. A disk read verifies the block's checksum before it is
     * cached, so a cached block has already been checked once and is not re-verified.
     */
    private Block readBlock(long offset) {
        BlockCache.Key key = new BlockCache.Key(id, offset);
        Block cached = cache.get(key);
        if (cached != null) return cached;
        try {
            Block block = readBlockRaw(channel, path, offset);
            cache.put(key, block);
            return block;
        } catch (IOException ex) {
            throw new UncheckedIOException("sstable read failed " + path, ex);
        }
    }

    /**
     * Reads the block header and payload at {@code offset}, checks the CRC and decodes
     * the entries. A mismatch means the bytes on disk no longer match what was written,
     * so it raises a {@link ChecksumException} rather than returning corrupt data.
     */
    private static Block readBlockRaw(FileChannel ch, Path path, long offset) throws IOException {
        ByteBuffer header = readAt(ch, offset, 8);
        header.flip();
        int payloadLen = header.getInt();
        int expectedCrc = header.getInt();

        ByteBuffer payload = readAt(ch, offset + 8, payloadLen);
        payload.flip();
        CRC32 crc = new CRC32();
        crc.update(payload.duplicate());
        if ((int) crc.getValue() != expectedCrc) {
            throw new ChecksumException(path, offset);
        }

        List<Entry> entries = new ArrayList<>();
        while (payload.hasRemaining()) {
            byte[] key = new byte[payload.getInt()];
            payload.get(key);
            int valLen = payload.getInt();
            if (valLen == TOMBSTONE_LEN) {
                entries.add(new Entry(Bytes.wrap(key), null, true));
            } else {
                byte[] value = new byte[valLen];
                payload.get(value);
                entries.add(new Entry(Bytes.wrap(key), value, false));
            }
        }
        return new Block(entries.toArray(new Entry[0]), offset + 8 + payloadLen);
    }

    /** Appends one entry's bytes to {@code out}. A null value is a tombstone. */
    private static void encodeEntry(ByteArrayOutputStream out, byte[] key, byte[] value) {
        boolean tombstone = value == null;
        int valLen = tombstone ? 0 : value.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + key.length + 4 + valLen);
        buf.putInt(key.length).put(key);
        buf.putInt(tombstone ? TOMBSTONE_LEN : value.length);
        if (!tombstone) buf.put(value);
        out.writeBytes(buf.array());
    }

    /** Writes one block ([len][crc][payload]) and returns the bytes it occupies. */
    private static long writeBlock(FileChannel ch, byte[] payload) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(payload);
        ByteBuffer buf = ByteBuffer.allocate(8 + payload.length);
        buf.putInt(payload.length).putInt((int) crc.getValue()).put(payload).flip();
        writeFully(ch, buf);
        return 8L + payload.length;
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) ch.write(buf);
    }

    private static ByteBuffer readAt(FileChannel ch, long position, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        long at = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, at);
            if (n < 0) throw new IOException("unexpected end of sstable at " + at);
            at += n;
        }
        return buf;
    }
}

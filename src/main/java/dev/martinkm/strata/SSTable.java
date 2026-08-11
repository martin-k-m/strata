package dev.martinkm.strata;

import dev.martinkm.strata.util.Bytes;

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
 *   data:   [entry]...                       every key, in sorted order
 *             entry = [keyLen: int][key][valLen: int][value]
 *                     valLen = -1 marks a tombstone (a delete), which carries no value
 *   index:  [entry]...                       one entry per INDEX_INTERVAL data keys
 *             entry = [keyLen: int][key][offset: long]   offset points at the data entry
 *   bloom:  [numBits: int][numHashes: int][word: long]...
 *   footer: [dataLen: long][indexOffset: long][indexCount: int]
 *           [bloomOffset: long][entryCount: int][magic: long]
 * </pre>
 *
 * The footer is a fixed {@value #FOOTER_LEN} bytes at the very end, so opening a
 * table is: read the footer, then the (small, sparse) index and the bloom filter
 * into memory. The data block stays on disk and is touched only on a lookup that
 * the bloom filter did not rule out.
 *
 * <p>The index is <em>sparse</em> on purpose: holding every key's offset in memory
 * would defeat the point of spilling to disk, so a lookup floors to the nearest
 * indexed key and scans forward a bounded number of entries from there. Reads and
 * a full scan are the only operations; there is no in-place update.
 */
final class SSTable {

    /** A data entry offset every this many keys is kept in the sparse index. */
    static final int INDEX_INTERVAL = 16;

    /** Value length written for a tombstone. A real value is never negative length. */
    private static final int TOMBSTONE_LEN = -1;

    private static final long MAGIC = 0x53545241544131L; // "STRATA1"
    private static final int FOOTER_LEN = 8 + 8 + 4 + 8 + 4 + 8;

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

    private final FileChannel channel;
    private final Path path;
    private final BloomFilter bloom;
    private final Bytes[] indexKeys;
    private final long[] indexOffsets;
    private final long dataLen;
    private final int entryCount;

    private SSTable(FileChannel channel, Path path, BloomFilter bloom,
                    Bytes[] indexKeys, long[] indexOffsets, long dataLen, int entryCount) {
        this.channel = channel;
        this.path = path;
        this.bloom = bloom;
        this.indexKeys = indexKeys;
        this.indexOffsets = indexOffsets;
        this.dataLen = dataLen;
        this.entryCount = entryCount;
    }

    /**
     * Writes {@code entries} (already in ascending key order, tombstones carrying
     * a null value) to a new immutable table at {@code path}.
     *
     * The table is built into a temporary sibling file, fsynced, then atomically
     * renamed into place. A crash therefore leaves either no table or a complete
     * one, never a half-written file that a later open would trip over.
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
            int i = 0;
            for (Map.Entry<Bytes, byte[]> e : entries) {
                byte[] key = e.getKey().toArray();
                byte[] value = e.getValue();
                bloom.add(key);
                if (i % INDEX_INTERVAL == 0) {
                    indexKeys.add(key);
                    indexOffsets.add(offset);
                }
                ByteBuffer buf = encodeEntry(key, value);
                offset += buf.remaining();
                writeFully(ch, buf);
                i++;
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
    static SSTable open(Path path) {
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
                throw new IOException("not a strata sstable (bad magic) " + path);
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

            return new SSTable(ch, path, bloom, indexKeys, indexOffsets, dataLen, entryCount);
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

    /**
     * Looks up {@code key}. Returns {@link Result#ABSENT} if this table does not
     * hold the key, a value result if it does, or {@link Result#TOMBSTONE} if the
     * key was deleted in this table.
     */
    Result get(byte[] key) {
        // The bloom filter turns most misses into no disk work at all.
        if (!bloom.mightContain(key)) return Result.ABSENT;

        Bytes target = Bytes.wrap(key);
        long pos = floorOffset(target);
        try {
            while (pos < dataLen) {
                ByteBuffer header = readAt(channel, pos, 4);
                header.flip();
                int keyLen = header.getInt();
                ByteBuffer keyBuf = readAt(channel, pos + 4, keyLen);
                keyBuf.flip();
                byte[] entryKey = new byte[keyLen];
                keyBuf.get(entryKey);

                int cmp = Bytes.wrap(entryKey).compareTo(target);
                ByteBuffer valLenBuf = readAt(channel, pos + 4 + keyLen, 4);
                valLenBuf.flip();
                int valLen = valLenBuf.getInt();

                if (cmp == 0) {
                    if (valLen == TOMBSTONE_LEN) return Result.TOMBSTONE;
                    ByteBuffer valBuf = readAt(channel, pos + 4 + keyLen + 4, valLen);
                    valBuf.flip();
                    byte[] value = new byte[valLen];
                    valBuf.get(value);
                    return Result.of(value);
                }
                if (cmp > 0) return Result.ABSENT; // sorted: we have passed where it would be

                pos += 4 + keyLen + 4 + Math.max(valLen, 0);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("sstable read failed " + path, ex);
        }
        return Result.ABSENT;
    }

    /**
     * A one-pass reader over every entry in key order, tombstones included.
     * Compaction uses it to merge tables; it is not a general cursor and holds a
     * position in the shared channel, so it is single-threaded by construction.
     */
    Iterator scan() {
        return new Iterator(0, null, null);
    }

    /**
     * A reader over entries with key in {@code [from, to)} in key order, tombstones
     * included. A {@code null} bound is open on that side. The sparse index seeks
     * close to {@code from} so a bounded scan does not read the whole table.
     */
    Iterator scan(byte[] from, byte[] to) {
        long start = (from == null) ? 0 : floorOffset(Bytes.wrap(from));
        return new Iterator(start, from, to);
    }

    final class Iterator {
        private long pos;
        private final Bytes from;
        private final Bytes to;
        private Entry buffered;

        Iterator(long startPos, byte[] from, byte[] to) {
            this.pos = startPos;
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
         * Decodes forward from {@code pos} to the next entry whose key falls in
         * range, or null past the end. The floor offset can land before {@code from},
         * so entries below it are skipped; the first key at or above {@code to} ends
         * the scan.
         */
        private Entry readNextInRange() {
            try {
                while (pos < dataLen) {
                    ByteBuffer header = readAt(channel, pos, 4);
                    header.flip();
                    int keyLen = header.getInt();
                    ByteBuffer keyBuf = readAt(channel, pos + 4, keyLen);
                    keyBuf.flip();
                    byte[] key = new byte[keyLen];
                    keyBuf.get(key);
                    ByteBuffer valLenBuf = readAt(channel, pos + 4 + keyLen, 4);
                    valLenBuf.flip();
                    int valLen = valLenBuf.getInt();

                    Entry entry;
                    if (valLen == TOMBSTONE_LEN) {
                        pos += 4 + keyLen + 4;
                        entry = new Entry(Bytes.wrap(key), null, true);
                    } else {
                        ByteBuffer valBuf = readAt(channel, pos + 4 + keyLen + 4, valLen);
                        valBuf.flip();
                        byte[] value = new byte[valLen];
                        valBuf.get(value);
                        pos += 4 + keyLen + 4 + valLen;
                        entry = new Entry(Bytes.wrap(key), value, false);
                    }

                    Bytes k = entry.key();
                    if (from != null && k.compareTo(from) < 0) continue; // floor landed early
                    if (to != null && k.compareTo(to) >= 0) {            // past the range
                        pos = dataLen;
                        return null;
                    }
                    return entry;
                }
                return null;
            } catch (IOException ex) {
                throw new UncheckedIOException("sstable scan failed " + path, ex);
            }
        }
    }

    void close() {
        try {
            channel.close();
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot close sstable " + path, ex);
        }
    }

    /** The data offset of the last indexed key at or before {@code target}, else 0. */
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

    private static ByteBuffer encodeEntry(byte[] key, byte[] value) {
        boolean tombstone = value == null;
        int valLen = tombstone ? 0 : value.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + key.length + 4 + valLen);
        buf.putInt(key.length).put(key);
        buf.putInt(tombstone ? TOMBSTONE_LEN : value.length);
        if (!tombstone) buf.put(value);
        buf.flip();
        return buf;
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

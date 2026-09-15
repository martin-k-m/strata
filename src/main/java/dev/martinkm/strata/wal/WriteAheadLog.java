package dev.martinkm.strata.wal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.CRC32;

/**
 * The write-ahead log: the reason a write survives a crash.
 *
 * Every mutation is appended here and flushed to the platter <em>before</em> the
 * in-memory table is touched, so the durable record can never be behind what a
 * reader has already seen. On restart {@link #recover} replays the log to
 * rebuild that table.
 *
 * <p>Each record is length-prefixed and CRC-checked:
 *
 * <pre>
 *   [payloadLen: int][crc32: int][ type: byte | keyLen: int | key | valLen: int | value ]
 *                    \___ over the payload ___/     valLen = -1 marks a delete
 * </pre>
 *
 * A crash can only ever tear the <em>last</em> record, because the process dies
 * mid-append, so recovery reads until a record is short or fails its CRC, then
 * truncates the file to the last whole record. That is the difference between a
 * log that recovers and one that refuses to open after a hard kill.
 *
 * <p>A batch is one record holding several operations:
 *
 * <pre>
 *   [payloadLen: int][crc32: int][ BATCH | count: int | (type | keyLen | key | valLen | value)* ]
 * </pre>
 *
 * The batch shares one length prefix and one CRC, so the tear-the-tail rule
 * makes it atomic for free: a crash mid-append leaves a short or CRC-failing
 * record, and recovery drops the whole batch rather than a prefix of it. Nothing
 * else in this class knows a batch from a run of single records; {@link #recover}
 * hands the visitor one operation at a time either way.
 */
public final class WriteAheadLog implements AutoCloseable {

    public static final byte PUT = 1;
    public static final byte DELETE = 2;
    /** A record type that frames several operations as one unit of recovery. */
    public static final byte BATCH = 3;

    /** One operation, as written to and read back from the log. A null value is a delete. */
    public record Record(byte type, byte[] key, byte[] value) {}

    /** Receives each record during {@link #recover}, in the order it was written. */
    @FunctionalInterface
    public interface Visitor {
        void visit(byte type, byte[] key, byte[] value);
    }

    private final FileChannel channel;

    /**
     * Framed bytes handed to the channel since this log was opened, counting every
     * append including the ones a later {@link #reset} throws away. It is the WAL
     * term of the store's write amplification, so it must not be reset when the
     * file is: the bytes were still written.
     */
    private long bytesAppended;

    private WriteAheadLog(FileChannel channel) {
        this.channel = channel;
    }

    /** Framed bytes appended since open, for write-amplification accounting. */
    public synchronized long bytesAppended() {
        return bytesAppended;
    }

    /** Opens (creating if absent) the log at {@code path}. */
    public static WriteAheadLog open(Path path) {
        try {
            FileChannel ch = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            return new WriteAheadLog(ch);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open write-ahead log at " + path, e);
        }
    }

    /**
     * Replays every whole record to {@code visitor}, truncates a torn trailing
     * record if one is found, and positions the log for appending. Call once,
     * before the first {@link #append}.
     */
    public synchronized void recover(Visitor visitor) {
        try {
            long size = channel.size();
            long pos = 0;
            while (pos + 8 <= size) {
                ByteBuffer header = readAt(pos, 8);
                if (header == null) break;
                header.flip();
                int payloadLen = header.getInt();
                int expectedCrc = header.getInt();
                if (payloadLen <= 0 || pos + 8 + payloadLen > size) break; // torn tail

                ByteBuffer payload = readAt(pos + 8, payloadLen);
                if (payload == null) break;
                payload.flip();

                CRC32 crc = new CRC32();
                crc.update(payload.duplicate());
                if ((int) crc.getValue() != expectedCrc) break; // corrupt tail

                byte type = payload.get();
                if (type == BATCH) {
                    // The CRC already vouched for the whole record, so every
                    // operation in it is replayed: a batch is all or nothing.
                    int count = payload.getInt();
                    for (int i = 0; i < count; i++) {
                        visitOne(payload.get(), payload, visitor);
                    }
                } else {
                    visitOne(type, payload, visitor);
                }
                pos += 8 + payloadLen;
            }
            if (pos < channel.size()) {
                channel.truncate(pos); // drop the half-written record a crash left behind
            }
            channel.position(pos);
        } catch (IOException e) {
            throw new UncheckedIOException("write-ahead log recovery failed", e);
        }
    }

    /** Reads one operation's key and value out of {@code payload} and hands it on. */
    private static void visitOne(byte type, ByteBuffer payload, Visitor visitor) {
        byte[] key = new byte[payload.getInt()];
        payload.get(key);
        int valLen = payload.getInt();
        byte[] value = null;
        if (valLen >= 0) {
            value = new byte[valLen];
            payload.get(value);
        }
        visitor.visit(type, key, value);
    }

    /** Appends one record. Not durable until {@link #sync} returns. */
    public synchronized void append(byte type, byte[] key, byte[] value) {
        ByteBuffer payload = ByteBuffer.allocate(1 + opLength(key, value));
        payload.put(type);
        putOp(payload, key, value);
        payload.flip();
        write(payload);
    }

    /**
     * Appends every record in {@code records} as one log record, so a crash
     * during the append loses all of them or none. An empty list appends nothing.
     * Not durable until {@link #sync} returns.
     */
    public synchronized void appendBatch(List<Record> records) {
        if (records.isEmpty()) return;
        int payloadLen = 1 + 4;
        for (Record r : records) payloadLen += 1 + opLength(r.key(), r.value());

        ByteBuffer payload = ByteBuffer.allocate(payloadLen);
        payload.put(BATCH).putInt(records.size());
        for (Record r : records) {
            payload.put(r.type());
            putOp(payload, r.key(), r.value());
        }
        payload.flip();
        write(payload);
    }

    private static int opLength(byte[] key, byte[] value) {
        return 4 + key.length + 4 + (value == null ? 0 : value.length);
    }

    private static void putOp(ByteBuffer payload, byte[] key, byte[] value) {
        payload.putInt(key.length).put(key).putInt(value == null ? -1 : value.length);
        if (value != null) payload.put(value);
    }

    /** Frames {@code payload} with its length and CRC and hands it to the channel. */
    private void write(ByteBuffer payload) {
        int payloadLen = payload.remaining();
        CRC32 crc = new CRC32();
        crc.update(payload.duplicate());

        ByteBuffer frame = ByteBuffer.allocate(8 + payloadLen);
        frame.putInt(payloadLen).putInt((int) crc.getValue()).put(payload).flip();

        try {
            bytesAppended += frame.remaining();
            while (frame.hasRemaining()) channel.write(frame);
        } catch (IOException e) {
            throw new UncheckedIOException("write-ahead log append failed", e);
        }
    }

    /**
     * Empties the log and forces the truncation to disk, positioning it to append
     * from the start again.
     *
     * A flush calls this once the memtable it covered is durable in an SSTable:
     * those records are now redundant, so dropping them keeps the log bounded
     * rather than letting it grow for the life of the store. The order at the call
     * site matters. The SSTable must be fsynced and renamed into place first, so a
     * crash between the two leaves the data still recoverable from this log rather
     * than lost from a log already cleared.
     */
    public synchronized void reset() {
        try {
            channel.truncate(0);
            channel.position(0);
            channel.force(true); // the emptying itself must survive a crash
        } catch (IOException e) {
            throw new UncheckedIOException("write-ahead log reset failed", e);
        }
    }

    /** Forces every prior append to durable storage. */
    public synchronized void sync() {
        try {
            channel.force(false); // data only; the file's metadata does not gate durability here
        } catch (IOException e) {
            throw new UncheckedIOException("write-ahead log sync failed", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            channel.force(false);
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException("write-ahead log close failed", e);
        }
    }

    /** Reads exactly {@code len} bytes at {@code position}, or null at end of file. */
    private ByteBuffer readAt(long position, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        long at = position;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, at);
            if (n < 0) return null; // reached EOF before filling: incomplete record
            at += n;
        }
        return buf;
    }
}

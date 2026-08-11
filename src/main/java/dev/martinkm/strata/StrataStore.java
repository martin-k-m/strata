package dev.martinkm.strata;

import dev.martinkm.strata.util.Bytes;
import dev.martinkm.strata.wal.WriteAheadLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * A durable, ordered key-value store — the first slice of an LSM engine.
 *
 * The write path is the classic log-structured one: append the mutation to the
 * {@link WriteAheadLog} and force it to disk, then apply it to an in-memory
 * sorted table (the <em>memtable</em>, a {@link ConcurrentSkipListMap} so reads
 * run lock-free while a single writer appends). Reads answer from the memtable.
 * On {@link #open} the log is replayed to rebuild it, so a process that is
 * killed at any point comes back holding exactly the writes whose {@code put}
 * or {@code delete} had returned.
 *
 * <p>What is deliberately <em>not</em> here yet: the memtable never flushes to an
 * on-disk sorted table (an SSTable), so the whole dataset lives in memory and
 * the log grows without bound. Flush, SSTables and compaction are the next
 * slices; the interface above them will not change.
 */
public final class StrataStore implements Store {

    private final WriteAheadLog wal;
    private final ConcurrentNavigableMap<Bytes, byte[]> memtable = new ConcurrentSkipListMap<>();

    private StrataStore(WriteAheadLog wal) {
        this.wal = wal;
    }

    /**
     * Opens a store rooted at directory {@code dir}, creating it if needed and
     * recovering any existing write-ahead log.
     */
    public static StrataStore open(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create store directory " + dir, e);
        }
        WriteAheadLog wal = WriteAheadLog.open(dir.resolve("wal.log"));
        StrataStore store = new StrataStore(wal);
        // Replay rebuilds the memtable in write order, so a later put/delete of a
        // key correctly wins over an earlier one.
        wal.recover((type, key, value) -> {
            if (type == WriteAheadLog.PUT) {
                store.memtable.put(Bytes.wrap(key), value);
            } else {
                store.memtable.remove(Bytes.wrap(key));
            }
        });
        return store;
    }

    @Override
    public void put(byte[] key, byte[] value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        // Log-before-apply: the record is durable before any reader can observe
        // the new value, so a crash can lose a write but never expose one that
        // did not survive.
        wal.append(WriteAheadLog.PUT, key, value);
        wal.sync();
        memtable.put(Bytes.copyOf(key), value.clone());
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        Objects.requireNonNull(key, "key");
        byte[] value = memtable.get(Bytes.wrap(key));
        return Optional.ofNullable(value).map(byte[]::clone);
    }

    @Override
    public void delete(byte[] key) {
        Objects.requireNonNull(key, "key");
        wal.append(WriteAheadLog.DELETE, key, null);
        wal.sync();
        memtable.remove(Bytes.wrap(key));
    }

    @Override
    public long size() {
        return memtable.size();
    }

    @Override
    public void close() {
        wal.close();
    }
}

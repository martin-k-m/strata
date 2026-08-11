package dev.martinkm.strata;

import dev.martinkm.strata.util.Bytes;
import dev.martinkm.strata.wal.WriteAheadLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A durable, ordered key-value store: the write path over a memtable that spills
 * to immutable on-disk tables.
 *
 * The write path is the classic log-structured one. Append the mutation to the
 * {@link WriteAheadLog} and force it to disk, then apply it to an in-memory
 * sorted table (the <em>memtable</em>, a {@link ConcurrentSkipListMap}). When the
 * memtable reaches a size threshold it is flushed to an {@link SSTable}, an
 * immutable sorted file, and the log is rolled empty so both memory and log stay
 * bounded. A read answers from the memtable first, then from the SSTables newest
 * to oldest, stopping at the first table that holds the key (or a tombstone for
 * it).
 *
 * <p>A delete does not remove the key from the memtable. It writes a
 * <em>tombstone</em>, a marker that must outlive the memtable so that after a
 * flush it still shadows an older value the same key may have in an on-disk
 * table. Tombstones are only truly dropped by a full {@link #compact()}, once no
 * older table remains for them to shadow.
 *
 * <p>Concurrency is single-writer: {@code put}, {@code delete}, flush and compaction
 * do not run concurrently with one another. Reads run alongside a writer without
 * locking, which is why a flush publishes the new table before it clears the
 * memtable, so a key is never briefly absent from both.
 */
public final class StrataStore implements Store {

    /**
     * The marker stored in the memtable for a deleted key. It is a private
     * sentinel compared by identity, never by contents, so it can never collide
     * with a real value a caller happens to store.
     */
    private static final byte[] TOMBSTONE = new byte[0];

    /** Default flush threshold: memtable entries before a spill to disk. */
    private static final int DEFAULT_FLUSH_THRESHOLD = 100_000;

    /** SSTable count that triggers a full compaction after a flush. */
    private static final int COMPACTION_TRIGGER = 4;

    private static final String SSTABLE_SUFFIX = ".sst";
    private static final String SSTABLE_PREFIX = "sst-";

    private final Path dir;
    private final WriteAheadLog wal;
    private final int flushThreshold;

    private volatile ConcurrentNavigableMap<Bytes, byte[]> memtable = new ConcurrentSkipListMap<>();
    // Newest table first, so a lookup walks it front to back and the first hit wins.
    private volatile List<SSTable> sstables = new ArrayList<>();
    private long nextSeq;

    private StrataStore(Path dir, WriteAheadLog wal, int flushThreshold) {
        this.dir = dir;
        this.wal = wal;
        this.flushThreshold = flushThreshold;
    }

    /** Opens a store at {@code dir} with the default flush threshold. */
    public static StrataStore open(Path dir) {
        return open(dir, DEFAULT_FLUSH_THRESHOLD);
    }

    /**
     * Opens a store rooted at {@code dir}, creating it if needed, loading any
     * existing SSTables and replaying the write-ahead log on top. A small
     * {@code flushThreshold} forces frequent flushes, which is mainly useful to
     * tests that want on-disk tables to exist without writing a lot of data.
     */
    public static StrataStore open(Path dir, int flushThreshold) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create store directory " + dir, e);
        }
        WriteAheadLog wal = WriteAheadLog.open(dir.resolve("wal.log"));
        StrataStore store = new StrataStore(dir, wal, flushThreshold);
        store.loadSSTables();
        // Replay rebuilds the memtable in write order, so a later put/delete of a
        // key correctly wins over an earlier one. A delete replays as a tombstone,
        // not a removal, so it still shadows any value the key holds in an SSTable.
        wal.recover((type, key, value) -> {
            if (type == WriteAheadLog.PUT) {
                store.memtable.put(Bytes.wrap(key), value);
            } else {
                store.memtable.put(Bytes.wrap(key), TOMBSTONE);
            }
        });
        return store;
    }

    @Override
    public synchronized void put(byte[] key, byte[] value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        // Log-before-apply: the record is durable before any reader can observe
        // the new value, so a crash can lose a write but never expose one that
        // did not survive.
        wal.append(WriteAheadLog.PUT, key, value);
        wal.sync();
        memtable.put(Bytes.copyOf(key), value.clone());
        maybeFlush();
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        Objects.requireNonNull(key, "key");
        // Capture consistent snapshots. A concurrent flush publishes its table
        // before clearing the memtable, so a key is present in at least one of the
        // two references we read here.
        ConcurrentNavigableMap<Bytes, byte[]> mem = memtable;
        List<SSTable> tables = sstables;

        byte[] fromMem = mem.get(Bytes.wrap(key));
        if (fromMem != null) {
            return fromMem == TOMBSTONE ? Optional.empty() : Optional.of(fromMem.clone());
        }
        for (SSTable table : tables) { // newest to oldest
            SSTable.Result r = table.get(key);
            if (r.isPresent()) {
                return r.isTombstone() ? Optional.empty() : Optional.of(r.value());
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized void delete(byte[] key) {
        Objects.requireNonNull(key, "key");
        wal.append(WriteAheadLog.DELETE, key, null);
        wal.sync();
        // A tombstone, not a removal: after a flush this marker must remain to
        // shadow any older on-disk value for the same key.
        memtable.put(Bytes.copyOf(key), TOMBSTONE);
        maybeFlush();
    }

    @Override
    public Stream<Map.Entry<byte[], byte[]>> scan(byte[] from, byte[] to) {
        // Snapshot the layers the way get() does: a concurrent flush publishes its
        // table before clearing the memtable, so no live key falls between the two.
        ConcurrentNavigableMap<Bytes, byte[]> mem = memtable;
        List<SSTable> tables = sstables;

        // A reversed or empty range has nothing in it, and the memtable's subMap
        // would reject a reversed one, so answer it directly.
        if (from != null && to != null && Bytes.wrap(from).compareTo(Bytes.wrap(to)) >= 0) {
            return Stream.empty();
        }

        // One source per layer, newest first: the memtable, then the SSTables newest
        // to oldest. Each yields cells in key order, which is what the merge needs.
        List<Iterator<MergingIterator.Cell>> sources = new ArrayList<>(tables.size() + 1);
        sources.add(memtableSource(mem, from, to));
        for (SSTable table : tables) { // newest to oldest
            sources.add(sstableSource(table.scan(from, to)));
        }

        MergingIterator merged = new MergingIterator(sources);
        Spliterator<MergingIterator.Cell> spliterator = Spliterators.spliteratorUnknownSize(
                merged, Spliterator.ORDERED | Spliterator.NONNULL);
        return StreamSupport.stream(spliterator, false)
                .map(cell -> new AbstractMap.SimpleImmutableEntry<byte[], byte[]>(
                        cell.key().toArray(), cell.value().clone()));
    }

    /** The memtable rows in {@code [from, to)} as merge cells, tombstones marked. */
    private Iterator<MergingIterator.Cell> memtableSource(
            ConcurrentNavigableMap<Bytes, byte[]> mem, byte[] from, byte[] to) {
        ConcurrentNavigableMap<Bytes, byte[]> range;
        if (from == null && to == null) {
            range = mem;
        } else if (from == null) {
            range = mem.headMap(Bytes.wrap(to), false);
        } else if (to == null) {
            range = mem.tailMap(Bytes.wrap(from), true);
        } else {
            range = mem.subMap(Bytes.wrap(from), true, Bytes.wrap(to), false);
        }
        Iterator<Map.Entry<Bytes, byte[]>> it = range.entrySet().iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public MergingIterator.Cell next() {
                Map.Entry<Bytes, byte[]> e = it.next();
                boolean tombstone = e.getValue() == TOMBSTONE;
                return new MergingIterator.Cell(e.getKey(), tombstone ? null : e.getValue(), tombstone);
            }
        };
    }

    /** Adapts an {@link SSTable.Iterator} to the merge's cell iterator. */
    private static Iterator<MergingIterator.Cell> sstableSource(SSTable.Iterator it) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public MergingIterator.Cell next() {
                SSTable.Entry e = it.next();
                return new MergingIterator.Cell(e.key(), e.value(), e.tombstone());
            }
        };
    }

    @Override
    public long size() {
        // The number of live keys across every layer. This scans all tables, so it
        // is not cheap; it exists for tests and introspection, not the hot path.
        ConcurrentNavigableMap<Bytes, byte[]> mem = memtable;
        List<SSTable> tables = sstables;

        TreeMap<Bytes, Boolean> live = new TreeMap<>();
        // Oldest first so newer layers overwrite older ones for the same key.
        for (int i = tables.size() - 1; i >= 0; i--) {
            SSTable.Iterator it = tables.get(i).scan();
            while (it.hasNext()) {
                SSTable.Entry e = it.next();
                live.put(e.key(), !e.tombstone());
            }
        }
        for (Map.Entry<Bytes, byte[]> e : mem.entrySet()) {
            live.put(e.getKey(), e.getValue() != TOMBSTONE);
        }
        return live.values().stream().filter(Boolean::booleanValue).count();
    }

    /**
     * Spills the current memtable to a new SSTable and rolls the log empty. A
     * no-op if the memtable is empty. Exposed mainly so tests can force a flush at
     * a chosen point; normally {@link #maybeFlush()} drives it off the threshold.
     */
    public synchronized void flush() {
        if (memtable.isEmpty()) return;

        List<Map.Entry<Bytes, byte[]>> entries = new ArrayList<>(memtable.size());
        for (Map.Entry<Bytes, byte[]> e : memtable.entrySet()) {
            byte[] value = (e.getValue() == TOMBSTONE) ? null : e.getValue();
            entries.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), value));
        }

        Path path = dir.resolve(sstableName(nextSeq));
        SSTable.write(path, entries); // fsynced and atomically renamed before it is opened
        SSTable table = SSTable.open(path);

        // Publish the table before clearing the memtable, so a concurrent reader
        // always finds each key in one place or the other.
        List<SSTable> updated = new ArrayList<>(sstables.size() + 1);
        updated.add(table);
        updated.addAll(sstables);
        sstables = updated;
        memtable = new ConcurrentSkipListMap<>();
        nextSeq++;

        // The memtable's writes now live in the durable table, so the log records
        // that carried them are redundant and can be dropped.
        wal.reset();

        if (sstables.size() >= COMPACTION_TRIGGER) compact();
    }

    /**
     * Merges every SSTable into one, keeping only the newest value for each key
     * and dropping tombstones. Dropping a tombstone is safe only here, in a full
     * merge: once no older table survives, there is nothing left for the tombstone
     * to shadow, so the delete is complete and the marker is pure overhead.
     *
     * A no-op below two tables, where there is nothing to merge.
     */
    public synchronized void compact() {
        List<SSTable> tables = sstables;
        if (tables.size() < 2) return;

        // Merge newest to oldest into a sorted map; the first write for a key wins,
        // so iterate newest first and only fill a key that is not already set.
        TreeMap<Bytes, byte[]> merged = new TreeMap<>();
        java.util.Set<Bytes> seen = new java.util.HashSet<>();
        for (SSTable table : tables) { // newest to oldest
            SSTable.Iterator it = table.scan();
            while (it.hasNext()) {
                SSTable.Entry e = it.next();
                if (!seen.add(e.key())) continue; // a newer table already decided this key
                if (!e.tombstone()) merged.put(e.key(), e.value());
            }
        }

        List<Map.Entry<Bytes, byte[]>> entries = new ArrayList<>(merged.entrySet());
        Path path = dir.resolve(sstableName(nextSeq));
        SSTable.write(path, entries);
        SSTable compacted = SSTable.open(path);
        nextSeq++;

        List<SSTable> updated = new ArrayList<>();
        updated.add(compacted);
        sstables = updated;

        // The merged inputs are unreferenced now; close and remove their files.
        for (SSTable old : tables) {
            old.close();
            try {
                Files.deleteIfExists(old.path());
            } catch (IOException e) {
                throw new UncheckedIOException("cannot remove compacted sstable " + old.path(), e);
            }
        }
    }

    @Override
    public synchronized void close() {
        wal.close();
        for (SSTable table : sstables) table.close();
    }

    private void maybeFlush() {
        if (memtable.size() >= flushThreshold) flush();
    }

    /** Loads existing tables at open, ordering them newest (highest sequence) first. */
    private void loadSSTables() {
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(SSTABLE_PREFIX) && n.endsWith(SSTABLE_SUFFIX);
            }).forEach(paths::add);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list store directory " + dir, e);
        }
        // Zero-padded names sort lexicographically the same as by sequence number.
        paths.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));

        List<SSTable> loaded = new ArrayList<>();
        long maxSeq = -1;
        for (Path p : paths) {
            SSTable table = SSTable.open(p);
            loaded.add(0, table); // prepend so the newest ends up first
            maxSeq = Math.max(maxSeq, sequenceOf(p));
        }
        sstables = loaded;
        nextSeq = maxSeq + 1;
    }

    private static String sstableName(long seq) {
        return String.format("%s%010d%s", SSTABLE_PREFIX, seq, SSTABLE_SUFFIX);
    }

    private static long sequenceOf(Path path) {
        String n = path.getFileName().toString();
        String digits = n.substring(SSTABLE_PREFIX.length(), n.length() - SSTABLE_SUFFIX.length());
        return Long.parseLong(digits);
    }
}

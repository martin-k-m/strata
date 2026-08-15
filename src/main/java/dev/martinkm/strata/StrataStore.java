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
 *
 * <p>Compaction needs the same care in the other direction. It publishes the new
 * level structure before it retires the tables it consumed, so no read starting
 * afterwards can find them, but a read that started earlier is already walking
 * one. Those tables are therefore reference counted: a reader takes a reference
 * on every table it is about to read and gives it back when it is done, and a
 * retired table is closed and deleted by whichever of them leaves last. A reader
 * that arrives just too late to take one retakes its snapshot instead, and finds
 * the same keys in the tables the compaction wrote.
 *
 * <p>{@link #scan} reads its tables lazily, so it holds its references until the
 * stream is closed. Close it, as with any stream over a file.
 *
 * <h2>Levels</h2>
 *
 * On-disk tables are organised into levels, which is what keeps a compaction from
 * rewriting the whole store. A flush drops a table into level 0. Tables in level 0
 * come straight from the memtable and may overlap each other in key range. Every
 * level below is a <em>run</em>: its tables hold disjoint key ranges, so at most one
 * table per level can hold a given key. For any key, a shallower level is newer than
 * a deeper one, because a key only reaches level L+1 by being merged down out of
 * level L, and the merge lets the shallower copy win.
 *
 * <p>Two triggers move data down. When level 0 reaches {@link #L0_COMPACTION_TRIGGER}
 * tables it is merged into level 1. When a deeper level exceeds its table budget one
 * of its tables is merged into the level below it, rewriting only the tables there
 * that overlap it rather than the entire store. A merge keeps the newest value per
 * key and drops a tombstone only when it is landing in the deepest populated level,
 * where no older table can still hold the key it shadows.
 *
 * <p>This does less work per compaction than a single full merge, so it lowers write
 * amplification, and it bounds read amplification to roughly one table per level plus
 * the level-0 stack. It is an honest simplification of a real engine: level 0 tables
 * usually span most of the key range, so an L0 into L1 merge still tends to rewrite
 * much of level 1, and the store is still single-writer and still pauses the writer
 * for the duration of a compaction rather than running it in the background.
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

    /** Level-0 table count that triggers a merge of level 0 into level 1. */
    private static final int L0_COMPACTION_TRIGGER = 4;

    /** Table budget of level 1. Each level below holds {@link #LEVEL_FANOUT} times more. */
    private static final int LEVEL1_MAX_TABLES = 4;

    /** How much larger each level's table budget is than the one above it. */
    private static final int LEVEL_FANOUT = 4;

    private static final String SSTABLE_SUFFIX = ".sst";
    private static final String SSTABLE_PREFIX = "sst-";

    private final Path dir;
    private final WriteAheadLog wal;
    private final int flushThreshold;

    /**
     * The shared cache of decoded SSTable blocks. It is bounded and in-heap, keyed by
     * table identity plus offset, so a compaction that retires a table drops its blocks
     * rather than serving stale ones.
     */
    private final BlockCache blockCache;

    /**
     * Target number of entries per on-disk table below level 0. A merged run is
     * split into tables of about this many keys, so a level holds several tables that
     * can be moved down one at a time. It tracks the flush threshold, so a level's
     * tables are about the size of a flushed memtable.
     */
    private final int targetTableEntries;

    /**
     * Whether {@code put} and {@code delete} fsync the log before returning. True
     * for every store opened through the ordinary {@link #open} overloads, and the
     * only setting under which this store is durable. It is false only for
     * {@link #openWithoutSync}, which exists so a benchmark can price the fsync.
     */
    private final boolean fsyncOnWrite;

    /** Key and value bytes handed to this store by callers, the write-amplification denominator. */
    private long logicalBytesWritten;

    /** Bytes of SSTable written by flushes. */
    private long flushBytesWritten;

    /** Bytes of SSTable written by compactions. */
    private long compactionBytesWritten;

    private volatile ConcurrentNavigableMap<Bytes, byte[]> memtable = new ConcurrentSkipListMap<>();
    // Tables by level. levels.get(0) is level 0, newest table first. Each deeper level
    // is a run whose tables are sorted by key and hold disjoint ranges. The whole
    // structure is replaced, never mutated in place, so a lockless reader sees a
    // consistent snapshot.
    private volatile List<List<SSTable>> levels = new ArrayList<>();
    private long nextSeq;

    private StrataStore(Path dir, WriteAheadLog wal, int flushThreshold, int cacheBlocks,
                        boolean fsyncOnWrite) {
        this.dir = dir;
        this.wal = wal;
        this.flushThreshold = flushThreshold;
        this.targetTableEntries = Math.max(1, flushThreshold);
        this.blockCache = new BlockCache(cacheBlocks);
        this.fsyncOnWrite = fsyncOnWrite;
    }

    /** Opens a store at {@code dir} with the default flush threshold and cache size. */
    public static StrataStore open(Path dir) {
        return open(dir, DEFAULT_FLUSH_THRESHOLD);
    }

    /** Opens a store at {@code dir} with the default block cache size. */
    public static StrataStore open(Path dir, int flushThreshold) {
        return open(dir, flushThreshold, BlockCache.DEFAULT_MAX_BLOCKS);
    }

    /**
     * Opens a store rooted at {@code dir}, creating it if needed, loading any
     * existing SSTables and replaying the write-ahead log on top. A small
     * {@code flushThreshold} forces frequent flushes, which is mainly useful to
     * tests that want on-disk tables to exist without writing a lot of data.
     * {@code cacheBlocks} bounds the decoded-block cache; a tiny value is mainly
     * useful to tests that want to exercise eviction.
     */
    public static StrataStore open(Path dir, int flushThreshold, int cacheBlocks) {
        return open(dir, flushThreshold, cacheBlocks, true);
    }

    /**
     * Opens a store that appends to the log but never fsyncs it on a write, so a
     * {@code put} that returned can be lost to a machine crash or a power cut. This
     * store is <strong>not durable</strong>. It exists so a benchmark can measure
     * what the fsync in the ordinary write path costs, by running the same workload
     * with it removed; nothing else should call it.
     */
    public static StrataStore openWithoutSync(Path dir, int flushThreshold, int cacheBlocks) {
        return open(dir, flushThreshold, cacheBlocks, false);
    }

    private static StrataStore open(Path dir, int flushThreshold, int cacheBlocks,
                                    boolean fsyncOnWrite) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create store directory " + dir, e);
        }
        WriteAheadLog wal = WriteAheadLog.open(dir.resolve("wal.log"));
        StrataStore store = new StrataStore(dir, wal, flushThreshold, cacheBlocks, fsyncOnWrite);
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
        if (fsyncOnWrite) wal.sync();
        logicalBytesWritten += (long) key.length + value.length;
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
        List<SSTable> tables = hold();

        try {
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
        } finally {
            for (SSTable table : tables) table.release();
        }
    }

    /**
     * The tables to read, newest first, each with a reference taken so that a
     * compaction cannot retire it out from under the read.
     *
     * <p>The snapshot of {@code levels} and the taking of the references are two
     * steps, so a compaction can retire a table in between. That shows up as a
     * failed {@code acquire}, and the answer is to take a fresh snapshot: the
     * retired table's contents were merged into the new structure before it was
     * retired, so the newer snapshot has them. Compaction publishes before it
     * retires, which is what makes retrying converge rather than chase.
     *
     * <p>The caller must release every table it is given.
     */
    private List<SSTable> hold() {
        for (; ; ) {
            List<SSTable> candidates = readOrder(levels);
            List<SSTable> held = new ArrayList<>(candidates.size());
            boolean complete = true;
            for (SSTable table : candidates) {
                if (table.acquire()) {
                    held.add(table);
                } else {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                return held;
            }
            for (SSTable table : held) table.release();
        }
    }

    @Override
    public synchronized void delete(byte[] key) {
        Objects.requireNonNull(key, "key");
        wal.append(WriteAheadLog.DELETE, key, null);
        if (fsyncOnWrite) wal.sync();
        logicalBytesWritten += key.length;
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

        // A reversed or empty range has nothing in it, and the memtable's subMap
        // would reject a reversed one, so answer it directly.
        if (from != null && to != null && Bytes.wrap(from).compareTo(Bytes.wrap(to)) >= 0) {
            return Stream.empty();
        }

        // Held for as long as the stream is being consumed, not just for the call:
        // a scan reads its tables lazily, so it is inside them until it is closed.
        // That is why the returned stream must be closed, as every stream over a
        // file has to be, and why every caller here uses it in a try-with-resources.
        List<SSTable> tables = hold();

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
                // The witness keeps the stream typed as the declared return type
                // rather than as the concrete entry, so onClose below returns it.
                .<Map.Entry<byte[], byte[]>>map(cell -> new AbstractMap.SimpleImmutableEntry<>(
                        cell.key().toArray(), cell.value().clone()))
                .onClose(() -> {
                    for (SSTable table : tables) table.release();
                });
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
        List<SSTable> tables = readOrder(levels);

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

        Path path = dir.resolve(sstableName(0, nextSeq));
        SSTable.write(path, entries); // fsynced and atomically renamed before it is opened
        flushBytesWritten += fileSize(path);
        SSTable table = SSTable.open(path, blockCache);
        nextSeq++;

        // Publish the table into level 0 before clearing the memtable, so a concurrent
        // reader always finds each key in one place or the other. Newest first.
        List<List<SSTable>> updated = copyLevels(levels);
        updated.get(0).add(0, table);
        levels = updated;
        memtable = new ConcurrentSkipListMap<>();

        // Before the log is dropped, never after: a crash in between recovers
        // without the table but with the records that rebuild it.
        commitManifest();

        // The memtable's writes now live in the durable table, so the log records
        // that carried them are redundant and can be dropped.
        wal.reset();

        maybeCompact();
    }

    /**
     * Runs compaction to completion, then forces level 0 empty by merging it down
     * even if it has not reached the trigger. It leaves a valid leveled layout, not
     * a single table. Exposed mainly so tests can drain pending work at a chosen
     * point; normally {@link #maybeCompact()} drives it off the level triggers.
     */
    public synchronized void compact() {
        while (compactionStep(true)) {
            // keep going until level 0 is empty and no level is over its budget
        }
    }

    /** Runs any compaction the level triggers call for, then stops. */
    private void maybeCompact() {
        while (compactionStep(false)) {
            // one step per iteration until nothing is over its trigger
        }
    }

    /**
     * Performs one compaction if a level calls for it and returns whether it did.
     *
     * <p>Level 0 is merged into level 1 when it reaches {@link #L0_COMPACTION_TRIGGER}
     * tables, or unconditionally when {@code forceL0} is set and it is not empty.
     * Otherwise the shallowest level that is over its table budget has one table
     * merged into the level below it. Either way the inputs are the source tables
     * plus the tables in the target level that overlap their key range, and the
     * output is a fresh disjoint run split into tables of about
     * {@link #targetTableEntries} keys.
     */
    private boolean compactionStep(boolean forceL0) {
        List<List<SSTable>> snapshot = levels;
        if (snapshot.isEmpty()) return false;
        List<SSTable> level0 = snapshot.get(0);

        int target;
        List<SSTable> sources = new ArrayList<>(); // newest first
        List<SSTable> targetOverlap;

        if (level0.size() >= L0_COMPACTION_TRIGGER || (forceL0 && !level0.isEmpty())) {
            target = 1;
            sources.addAll(level0); // already newest first
            targetOverlap = overlapping(levelAt(snapshot, target), keyRange(level0));
        } else {
            int over = shallowestOverBudget(snapshot);
            if (over < 0) return false;
            target = over + 1;
            // Pick the table with the smallest first key, a stable, simple choice.
            SSTable picked = smallestFirstKey(snapshot.get(over));
            sources.add(picked);
            targetOverlap = overlapping(levelAt(snapshot, target), keyRange(List.of(picked)));
        }
        sources.addAll(targetOverlap); // older than every source above

        // A tombstone can be discarded only when it is landing in the deepest level
        // that still holds data, where nothing older survives for it to shadow.
        boolean dropTombstones = isDeepestPopulated(snapshot, target);

        List<Map.Entry<Bytes, byte[]>> merged = mergeSources(sources, dropTombstones);
        List<SSTable> output = writeRun(merged, target);

        // Build the new level structure: drop the consumed tables, add the output run.
        List<List<SSTable>> updated = copyLevels(snapshot);
        while (updated.size() <= target) updated.add(new ArrayList<>());
        if (target == 1) {
            updated.get(0).clear(); // the whole of level 0 was merged down
        } else {
            updated.get(target - 1).remove(sources.get(0)); // the single picked table
        }
        List<SSTable> newTarget = updated.get(target);
        newTarget.removeAll(targetOverlap);
        newTarget.addAll(output);
        newTarget.sort(java.util.Comparator.comparing(SSTable::firstKey));
        levels = updated;

        // Outputs written, inputs not yet deleted. This is the instant the swap
        // becomes durable, and there is no other at which both sets are live.
        commitManifest();

        // The consumed inputs are out of the level structure now, so no new read
        // will find them. A read that started before the line above may still be
        // inside one, so this drops the store's own reference and leaves the close
        // and the delete to whoever leaves last. Closing them here regardless is
        // what this replaced: a reader part way through a consumed table hit a
        // closed channel and failed a lookup for a key that never went anywhere.
        for (SSTable old : new ArrayList<>(sources)) {
            old.retire();
        }
        return true;
    }

    /**
     * Merges {@code sources} (newest first, each in key order) into one sorted run,
     * keeping the newest record per key. A dropped tombstone leaves no entry; a kept
     * one is written with a null value so it still shadows deeper levels.
     */
    private static List<Map.Entry<Bytes, byte[]>> mergeSources(
            List<SSTable> sources, boolean dropTombstones) {
        TreeMap<Bytes, byte[]> merged = new TreeMap<>();
        java.util.Set<Bytes> seen = new java.util.HashSet<>();
        for (SSTable table : sources) { // newest to oldest
            SSTable.Iterator it = table.scan();
            while (it.hasNext()) {
                SSTable.Entry e = it.next();
                if (!seen.add(e.key())) continue; // a newer source already decided this key
                if (e.tombstone()) {
                    if (!dropTombstones) merged.put(e.key(), null);
                } else {
                    merged.put(e.key(), e.value());
                }
            }
        }
        return new ArrayList<>(merged.entrySet());
    }

    /** Writes {@code entries} to level {@code target} as tables of about the target size. */
    private List<SSTable> writeRun(List<Map.Entry<Bytes, byte[]>> entries, int target) {
        List<SSTable> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += targetTableEntries) {
            List<Map.Entry<Bytes, byte[]>> chunk =
                    entries.subList(i, Math.min(i + targetTableEntries, entries.size()));
            Path path = dir.resolve(sstableName(target, nextSeq));
            SSTable.write(path, chunk);
            compactionBytesWritten += fileSize(path);
            out.add(SSTable.open(path, blockCache));
            nextSeq++;
        }
        return out;
    }

    @Override
    public synchronized void close() {
        wal.close();
        for (List<SSTable> level : levels) {
            for (SSTable table : level) table.close();
        }
    }

    /**
     * What this store has written, in bytes, since it was opened.
     *
     * @param logical    key and value bytes handed in by callers
     * @param wal        framed bytes appended to the write-ahead log, including
     *                   those a later flush threw away by resetting it
     * @param flush      SSTable bytes written by memtable flushes
     * @param compaction SSTable bytes written by compactions
     */
    public record IoStats(long logical, long wal, long flush, long compaction) {

        /** Every byte this store put on disk. */
        public long physical() {
            return wal + flush + compaction;
        }

        /**
         * Write amplification: bytes on disk over bytes the caller wrote. One would
         * mean the store wrote exactly what it was given, which no log-structured
         * engine achieves, because the WAL alone writes every value once before a
         * flush writes it again.
         */
        public double writeAmplification() {
            return logical == 0 ? Double.NaN : (double) physical() / logical;
        }
    }

    /** The byte counters behind write amplification. Snapshot, not live. */
    public synchronized IoStats ioStats() {
        return new IoStats(logicalBytesWritten, wal.bytesAppended(), flushBytesWritten,
                compactionBytesWritten);
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot size " + path, e);
        }
    }

    /** Block reads served from the cache since open. For tests and introspection. */
    long blockCacheHits() {
        return blockCache.hitCount();
    }

    /** Block reads that missed the cache and touched disk since open. For tests. */
    long blockCacheMisses() {
        return blockCache.missCount();
    }

    /** The number of levels that currently hold at least one table. For tests. */
    synchronized int populatedLevelCount() {
        int count = 0;
        for (List<SSTable> level : levels) {
            if (!level.isEmpty()) count++;
        }
        return count;
    }

    /** The number of tables in {@code level}, zero past the deepest one. For tests. */
    synchronized int tableCount(int level) {
        List<List<SSTable>> snapshot = levels;
        return (level < snapshot.size()) ? snapshot.get(level).size() : 0;
    }

    /** The index of the deepest level that holds a table, or -1 if the store is empty. For tests. */
    synchronized int deepestLevel() {
        List<List<SSTable>> snapshot = levels;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            if (!snapshot.get(i).isEmpty()) return i;
        }
        return -1;
    }

    /** Flattens the levels into read priority order: level 0 newest first, then deeper levels. */
    private static List<SSTable> readOrder(List<List<SSTable>> levels) {
        List<SSTable> out = new ArrayList<>();
        for (List<SSTable> level : levels) out.addAll(level);
        return out;
    }

    private static List<List<SSTable>> copyLevels(List<List<SSTable>> levels) {
        List<List<SSTable>> copy = new ArrayList<>(levels.size() + 1);
        for (List<SSTable> level : levels) copy.add(new ArrayList<>(level));
        if (copy.isEmpty()) copy.add(new ArrayList<>()); // always keep level 0 present
        return copy;
    }

    /** The tables at {@code level}, growing the structure with empty levels as needed. */
    private static List<SSTable> levelAt(List<List<SSTable>> levels, int level) {
        return (level < levels.size()) ? levels.get(level) : List.of();
    }

    /** The maximum table count a level may hold before it spills one table downward. */
    private static int levelMaxTables(int level) {
        // Level 0 is governed by the count trigger, not a budget. Deeper levels grow
        // geometrically, which is what bounds the number of levels for a given data
        // size and so bounds read amplification.
        int max = LEVEL1_MAX_TABLES;
        for (int i = 1; i < level; i++) max *= LEVEL_FANOUT;
        return max;
    }

    /** The shallowest level at or below 1 that is over its table budget, or -1 if none is. */
    private static int shallowestOverBudget(List<List<SSTable>> levels) {
        for (int level = 1; level < levels.size(); level++) {
            if (levels.get(level).size() > levelMaxTables(level)) return level;
        }
        return -1;
    }

    /** True if no level deeper than {@code target} holds a table. */
    private static boolean isDeepestPopulated(List<List<SSTable>> levels, int target) {
        for (int level = target + 1; level < levels.size(); level++) {
            if (!levels.get(level).isEmpty()) return false;
        }
        return true;
    }

    /** The [min first key, max last key] spanned by {@code tables}, or null if empty. */
    private static Bytes[] keyRange(List<SSTable> tables) {
        Bytes min = null, max = null;
        for (SSTable t : tables) {
            if (t.firstKey() == null) continue;
            if (min == null || t.firstKey().compareTo(min) < 0) min = t.firstKey();
            if (max == null || t.lastKey().compareTo(max) > 0) max = t.lastKey();
        }
        return (min == null) ? null : new Bytes[] {min, max};
    }

    /** The tables in {@code level} whose range intersects {@code range}. */
    private static List<SSTable> overlapping(List<SSTable> level, Bytes[] range) {
        List<SSTable> out = new ArrayList<>();
        if (range == null) return out;
        for (SSTable t : level) {
            if (t.firstKey() == null) continue;
            // Two ranges overlap when neither lies entirely to one side of the other.
            if (t.firstKey().compareTo(range[1]) <= 0 && range[0].compareTo(t.lastKey()) <= 0) {
                out.add(t);
            }
        }
        return out;
    }

    private static SSTable smallestFirstKey(List<SSTable> level) {
        SSTable best = null;
        for (SSTable t : level) {
            if (best == null || t.firstKey().compareTo(best.firstKey()) < 0) best = t;
        }
        return best;
    }

    private void maybeFlush() {
        if (memtable.size() >= flushThreshold) flush();
    }

    /**
     * Loads existing tables at open, reconstructing the levels from the level and
     * sequence encoded in each file name. The level and the sequence are the whole
     * manifest: no separate file is needed, because the name carries both which level
     * a table sits in and, through its sequence, which of two level-0 tables is newer.
     */
    private void loadSSTables() {
        List<Path> onDisk = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> parseName(p.getFileName().toString()) != null).forEach(onDisk::add);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list store directory " + dir, e);
        }

        Optional<Manifest.Snapshot> committed = Manifest.read(dir);

        // Without a manifest the listing is the only record there is, so it becomes
        // the set and is committed below. With one, the manifest is the set: anything
        // else is a crashed compaction's leftovers, and reading it back is STRATA-2.
        List<Path> paths = onDisk;
        long manifestSeq = -1;
        if (committed.isPresent()) {
            java.util.Set<String> live = new java.util.HashSet<>(committed.get().names());
            manifestSeq = committed.get().nextSeq();
            paths = new ArrayList<>();
            List<Path> orphans = new ArrayList<>();
            for (Path p : onDisk) {
                (live.contains(p.getFileName().toString()) ? paths : orphans).add(p);
            }
            if (paths.size() != live.size()) {
                java.util.Set<String> missing = new java.util.TreeSet<>(live);
                for (Path p : paths) missing.remove(p.getFileName().toString());
                throw new UncheckedIOException(new IOException(
                        "the manifest names tables that are not in " + dir + ": " + missing));
            }
            // Only once the manifest is known to be satisfiable, so a store that
            // fails to open still has every file it had.
            for (Path orphan : orphans) {
                try {
                    Files.deleteIfExists(orphan);
                } catch (IOException e) {
                    // Costs disk, not correctness.
                }
            }
        }

        List<List<SSTable>> loaded = new ArrayList<>();
        loaded.add(new ArrayList<>()); // level 0 always present
        long maxSeq = -1;
        for (Path p : paths) {
            long[] parsed = parseName(p.getFileName().toString());
            int level = (int) parsed[0];
            long seq = parsed[1];
            while (loaded.size() <= level) loaded.add(new ArrayList<>());
            loaded.get(level).add(SSTable.open(p, blockCache));
            maxSeq = Math.max(maxSeq, seq);
        }

        // Level 0 is newest first (highest sequence first); each deeper level is a run
        // ordered by key. Both orderings are what the read and merge paths assume.
        loaded.get(0).sort(java.util.Comparator.comparingLong(this::sequenceOf).reversed());
        for (int level = 1; level < loaded.size(); level++) {
            loaded.get(level).sort(java.util.Comparator.comparing(SSTable::firstKey));
        }

        levels = loaded;
        // The manifest's counter as well as the live names, so a delete that failed
        // cannot leave a stale file for a reused sequence to collide with.
        nextSeq = Math.max(maxSeq + 1, manifestSeq);

        if (committed.isEmpty()) commitManifest();
    }

    /**
     * The commit point. Until it returns, tables written since the last commit are
     * files that exist and are not part of the store, and dropped ones still are.
     */
    private void commitManifest() {
        List<String> names = new ArrayList<>();
        for (List<SSTable> level : levels) {
            for (SSTable table : level) names.add(table.path().getFileName().toString());
        }
        Manifest.write(dir, nextSeq, names);
    }

    private long sequenceOf(SSTable table) {
        return parseName(table.path().getFileName().toString())[1];
    }

    private static String sstableName(int level, long seq) {
        return String.format("%s%02d-%010d%s", SSTABLE_PREFIX, level, seq, SSTABLE_SUFFIX);
    }

    /**
     * Parses {@code sst-<level>-<seq>.sst} into {@code [level, seq]}, or null if the
     * name is not a strata table name.
     */
    private static long[] parseName(String name) {
        if (!name.startsWith(SSTABLE_PREFIX) || !name.endsWith(SSTABLE_SUFFIX)) return null;
        String middle = name.substring(SSTABLE_PREFIX.length(), name.length() - SSTABLE_SUFFIX.length());
        int dash = middle.indexOf('-');
        if (dash < 0) return null;
        try {
            long level = Long.parseLong(middle.substring(0, dash));
            long seq = Long.parseLong(middle.substring(dash + 1));
            return new long[] {level, seq};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

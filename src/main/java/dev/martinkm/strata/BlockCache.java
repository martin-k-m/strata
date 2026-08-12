package dev.martinkm.strata;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small in-heap LRU cache of decoded SSTable data blocks.
 *
 * A read seeks to a block, reads it, verifies its checksum and decodes it. Doing
 * that again for every read of a hot key is wasted work, so a decoded block is
 * kept here keyed by its table and offset. A later read of any key in that block
 * is answered from memory with no disk read and no second checksum check.
 *
 * <p>Keying by table identity plus offset is what keeps the cache correct under the
 * single-writer model. Tables are immutable and a compaction replaces a table
 * rather than mutating it, so a given (table, offset) always names the same bytes.
 * A retired table's blocks are dropped by {@link #invalidate} when it closes, so a
 * stale block is never served for a table that no longer exists.
 *
 * <p>The bound is a block count, not a byte budget, so a cache of very large blocks
 * uses more memory than one of small blocks. It is a deliberate simplicity: block
 * sizes here are bounded by {@link SSTable#INDEX_INTERVAL} entries, so a count is a
 * reasonable proxy. Access is guarded by a single lock, which suits the store's one
 * writer and occasional concurrent readers rather than a high-contention workload.
 */
final class BlockCache {

    /** Default bound: how many decoded blocks the cache holds before evicting. */
    static final int DEFAULT_MAX_BLOCKS = 1024;

    /** Identifies a block by its owning table and its offset in that table. */
    record Key(long tableId, long offset) {}

    private final int maxBlocks;
    private final LinkedHashMap<Key, SSTable.Block> map;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    BlockCache(int maxBlocks) {
        this.maxBlocks = Math.max(1, maxBlocks);
        // Access-ordered, so the eldest entry is the least recently used one.
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, SSTable.Block> eldest) {
                return size() > BlockCache.this.maxBlocks;
            }
        };
    }

    /** The cached block for {@code key}, or null on a miss. Both outcomes are counted. */
    synchronized SSTable.Block get(Key key) {
        SSTable.Block block = map.get(key);
        if (block != null) {
            hits.incrementAndGet();
        } else {
            misses.incrementAndGet();
        }
        return block;
    }

    synchronized void put(Key key, SSTable.Block block) {
        map.put(key, block);
    }

    /** Drops every block belonging to {@code tableId}, called when a table retires. */
    synchronized void invalidate(long tableId) {
        Iterator<Map.Entry<Key, SSTable.Block>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().tableId() == tableId) it.remove();
        }
    }

    /** Reads served from memory since open. For tests and introspection. */
    long hitCount() {
        return hits.get();
    }

    /** Reads that missed and had to touch disk since open. For tests and introspection. */
    long missCount() {
        return misses.get();
    }
}

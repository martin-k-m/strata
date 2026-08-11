package dev.martinkm.strata;

import dev.martinkm.strata.util.Bytes;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

/**
 * A k-way merge over the store's layers, newest wins per key.
 *
 * Each source is an iterator that already yields entries in ascending
 * unsigned-lexicographic key order with no duplicate keys within it: the memtable
 * and each SSTable qualify. Sources are passed newest first, so the memtable comes
 * before the SSTables and a newer SSTable before an older one.
 *
 * <p>The merge keeps a heap of one head entry per live source, ordered by key and,
 * for equal keys, by source age so the newest is popped first. When several sources
 * hold the same key the newest one decides it and the older duplicates are dropped.
 * A key whose newest record is a tombstone is skipped entirely, so the iterator
 * emits only live cells. It holds one entry per source at a time, not the whole
 * store, which is the point of merging lazily rather than collecting first.
 */
final class MergingIterator implements Iterator<MergingIterator.Cell> {

    /** One merged record: a key with its value, or a tombstone carrying a null value. */
    static final class Cell {
        private final Bytes key;
        private final byte[] value;
        private final boolean tombstone;

        Cell(Bytes key, byte[] value, boolean tombstone) {
            this.key = key;
            this.value = value;
            this.tombstone = tombstone;
        }

        Bytes key() {
            return key;
        }

        byte[] value() {
            return value;
        }

        boolean tombstone() {
            return tombstone;
        }
    }

    /** A source and the entry it is currently parked on, or null once drained. */
    private static final class Head {
        final int priority; // 0 is the newest source
        final Iterator<Cell> source;
        Cell cell;

        Head(int priority, Iterator<Cell> source) {
            this.priority = priority;
            this.source = source;
            this.cell = source.hasNext() ? source.next() : null;
        }
    }

    private final PriorityQueue<Head> heap;
    private Cell next;

    MergingIterator(List<Iterator<Cell>> sources) {
        this.heap = new PriorityQueue<>((a, b) -> {
            int c = a.cell.key.compareTo(b.cell.key);
            return c != 0 ? c : Integer.compare(a.priority, b.priority);
        });
        int priority = 0;
        for (Iterator<Cell> source : sources) {
            Head head = new Head(priority++, source);
            if (head.cell != null) heap.add(head);
        }
        this.next = advance();
    }

    @Override
    public boolean hasNext() {
        return next != null;
    }

    @Override
    public Cell next() {
        if (next == null) throw new NoSuchElementException();
        Cell current = next;
        next = advance();
        return current;
    }

    /** The next live cell, resolving duplicate keys newest-wins and skipping tombstones. */
    private Cell advance() {
        while (!heap.isEmpty()) {
            Head top = heap.poll();
            Bytes key = top.cell.key;
            Cell winner = top.cell; // newest for this key: equal keys pop newest first
            refill(top);
            // Discard the same key from any older source; the newest has decided it.
            while (!heap.isEmpty() && heap.peek().cell.key.equals(key)) {
                refill(heap.poll());
            }
            if (!winner.tombstone) return winner;
            // A tombstone means the key is deleted: emit nothing and move on.
        }
        return null;
    }

    private void refill(Head head) {
        head.cell = head.source.hasNext() ? head.source.next() : null;
        if (head.cell != null) heap.add(head);
    }
}

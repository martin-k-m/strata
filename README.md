# strata

A log-structured key-value store in Java, built from the write path up.

`strata` is an [LSM-tree](https://en.wikipedia.org/wiki/Log-structured_merge-tree)
storage engine, the shape of RocksDB, LevelDB and the write path under most of
the databases you have used. It is written to be read: a small, dependency-free
core where every durability and ordering decision is visible rather than buried
in a framework.

The name is the data structure. An LSM tree keeps data in sorted **layers**:
a mutable one in memory over a stack of immutable ones on disk, and answers a
read by looking down through the strata until it finds the key.

## What it guarantees

- **Durability.** Every `put` and `delete` is appended to a write-ahead log and
  `fsync`ed before the call returns. A write that returned has survived; a crash
  can lose only writes still in flight.
- **Crash recovery.** A process killed at any instant, including mid-append,
  reopens to a consistent state. Recovery replays the log and truncates a torn
  trailing record, so a hard kill never leaves a store that refuses to open.
- **Ordering.** Keys are held in unsigned-lexicographic order, the same order
  RocksDB uses, which is what makes ordered scans and, later, compaction cheap.

## The write path

```
put(k, v)
   │
   ├─ 1. append {PUT, k, v} to the write-ahead log   ── length-prefixed, CRC-checked
   ├─ 2. fsync the log                                ── the write is now durable
   └─ 3. apply to the memtable                        ── a lock-free sorted map

get(k)  ── memtable, then SSTables newest to oldest, stopping at the first hit

open(dir) ── load the SSTables, then replay the log on top, truncating any torn tail
```

Logging *before* applying is the whole game: the durable record can never be
behind what a reader has already observed.

When the memtable crosses a size threshold it is flushed to an immutable sorted
file, an SSTable, and the log is rolled empty so both memory and log stay
bounded. A read checks the memtable first, then walks the SSTables newest to
oldest and stops at the first table that holds the key. Each table carries a
bloom filter, so a read skips a table that provably lacks the key without
touching its data, and a sparse index (one offset every sixteen keys) seeks
close before a short forward scan.

A delete does not erase the key. It writes a tombstone, a marker that outlives
the memtable so that after a flush it still shadows an older value the same key
may hold in an on-disk table. Tombstones are dropped only by a full compaction,
which merges every table into one and, with no older table left to shadow, can
finally discard them.

## Build and test

No JDK on your machine needed. It builds in a container:

```bash
docker run --rm -v "$PWD":/app -w /app gradle:8.10-jdk21 gradle test
```

With a local JDK 21 and Gradle:

```bash
gradle test
```

There is also a small throughput harness. It fills a store with N random keys and
prints puts, gets that hit, gets that miss and full scans as operations per second.
It is a timed loop, not JMH, so the numbers are rough magnitudes. Note that every
put fsyncs the log, so the put rate is bound by the disk, not the code.

```bash
gradle bench                 # N defaults to 100000
gradle bench -Pbench.n=200000
```

The tests are the interesting part. Beyond the round trips, `strata` is checked
against an in-memory `TreeMap` oracle over thousands of random operations, once
purely in memory and once with a flush threshold small enough that tables spill
to disk mid-run. A dedicated test corrupts the tail of the log to prove recovery
truncates it and the store stays writable, and others cover a tombstone
shadowing an older on-disk value, recovery from SSTables and a log together, and
a compaction that folds tables down and drops deleted keys.

## What is done, and what is next

Done, the durable write path over a memtable that now spills to disk:

- Write-ahead log with CRC-checked, length-prefixed records and torn-tail recovery
- Skip-list memtable with lock-free reads
- Replay-on-open, tombstone deletes, value-copy isolation
- **Flush.** A full memtable is written to an immutable, sorted SSTable and the
  log is rolled empty, so memory and log size stay bounded.
- **SSTables.** A documented block format with a sparse index and a bloom filter
  per table, so a read skips a table that cannot hold the key and seeks close to
  it in the table that can. Tombstones shadow older values across the boundary.
- **Compaction.** A full merge of every SSTable into one that keeps the newest
  value per key and discards tombstones once no older table survives them.
- **Ordered scans.** `scan(from, to)` returns the live pairs with key in
  `[from, to)` in ascending key order, `null` on either bound meaning open on that
  side. It is a k-way merge over one iterator per layer, the memtable and each
  SSTable, so it keeps the newest value per key, drops tombstoned keys and holds
  one entry per layer at a time rather than the whole store. A reversed or empty
  range returns nothing.

Not done yet:

- **Leveled or tiered compaction.** Compaction here is a single full merge, not a
  background, size-tiered one, so it does more work than it needs to and pauses
  the writer while it runs.
- **Block-level checksums and compression** inside an SSTable, and a block cache.
  A read seeks straight to bytes on disk with no cache and no per-block integrity
  check beyond the write-time fsync.

The `Store` interface above these does not change as they land.

## License

MIT

# strata

A log-structured key-value store in Java, built from the write path up.

`strata` is an [LSM-tree](https://en.wikipedia.org/wiki/Log-structured_merge-tree)
storage engine — the shape of RocksDB, LevelDB and the write path under most of
the databases you have used. It is written to be read: a small, dependency-free
core where every durability and ordering decision is visible rather than buried
in a framework.

The name is the data structure. An LSM tree keeps data in sorted **layers** —
a mutable one in memory over a stack of immutable ones on disk — and answers a
read by looking down through the strata until it finds the key.

## What it guarantees

- **Durability.** Every `put` and `delete` is appended to a write-ahead log and
  `fsync`ed before the call returns. A write that returned has survived; a crash
  can lose only writes still in flight.
- **Crash recovery.** A process killed at any instant — including mid-append —
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

get(k)  ── answer from the memtable

open(dir) ── replay the log to rebuild the memtable, truncating any torn tail
```

Logging *before* applying is the whole game: the durable record can never be
behind what a reader has already observed.

## Build and test

No JDK on your machine needed — it builds in a container:

```bash
docker run --rm -v "$PWD":/app -w /app gradle:8.10-jdk21 gradle test
```

With a local JDK 21 and Gradle:

```bash
gradle test
```

The tests are the interesting part. Beyond the round trips, `strata` is checked
against an in-memory `TreeMap` oracle over five thousand random operations, and a
dedicated test corrupts the tail of the log to prove recovery truncates it and
the store stays writable.

## What is done, and what is next

Done — the durable, recoverable write path and in-memory reads:

- Write-ahead log with CRC-checked, length-prefixed records and torn-tail recovery
- Skip-list memtable with lock-free reads
- Replay-on-open, tombstone deletes, value-copy isolation

Not done yet — the parts that take it from in-memory to on-disk at scale:

- **Flush.** Roll a full memtable out to an immutable, sorted on-disk table
  (an SSTable) and start a fresh log, so memory and log size stay bounded.
- **SSTables.** A block format with a sparse index and a bloom filter per table,
  so a read skips tables that cannot hold the key.
- **Compaction.** Merge overlapping SSTables in the background to reclaim space
  from overwritten and deleted keys and keep read amplification down.

The `Store` interface above these does not change as they land.

## License

MIT

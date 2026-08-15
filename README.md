# strata

[![CI](https://github.com/martin-k-m/strata/actions/workflows/ci.yml/badge.svg)](https://github.com/martin-k-m/strata/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/java-21+-ED8B00?logo=openjdk&logoColor=fff)](https://openjdk.org)
[![Dependencies](https://img.shields.io/badge/runtime%20dependencies-0-7C6CFF)](build.gradle.kts)
[![Nightly](https://github.com/martin-k-m/strata/actions/workflows/nightly.yml/badge.svg)](https://github.com/martin-k-m/strata/actions/workflows/nightly.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A log-structured key-value store in Java, built from the write path up.

`strata` is an [LSM-tree](https://en.wikipedia.org/wiki/Log-structured_merge-tree)
storage engine, the shape of RocksDB, LevelDB and the write path under most of
the databases you have used. It is written to be read: a small, dependency-free
core where every durability and ordering decision is visible rather than buried
in a framework. The library has no runtime dependencies at all; the only external
jar in the repository is RocksDB, used by the benchmark source set to compare
against, and it is not on the library's classpath.

The name is the data structure. An LSM tree keeps data in sorted **layers**:
a mutable one in memory over a stack of immutable ones on disk, and answers a
read by looking down through the strata until it finds the key.

## What it guarantees

Each of these names the test that demonstrates it. Where a guarantee has a hole,
the hole is named too.

- **Durability of the log.** Every `put` and `delete` is appended to a write-ahead
  log and `fsync`ed before the call returns. A write that returned has survived a
  power cut; a crash can lose only writes still in flight. Priced in
  [BENCHMARKS.md](docs/BENCHMARKS.md): the fsync is most of what a `put` costs.
- **Log recovery, over the whole failure space.** A process killed mid-append
  reopens to a valid prefix of its write history, and a corrupted log never
  surfaces a value that was never written.
  `StrataDurabilityPropertyTest.everyTruncationOfTheLogRecoversAValidPrefix`
  truncates the log at every byte offset in turn, and
  `anySingleByteCorruptionOfTheLogNeverSurfacesAWrongValue` flips every byte in
  turn. Neither ever produces a state that is not a write-history prefix, and
  neither ever leaves a store that refuses to open.
- **Ordering.** Keys are held in unsigned-lexicographic order, the same order
  RocksDB uses, which is what makes ordered scans and compaction cheap.
  `StrataDurabilityPropertyTest.scanOrderIsUnsignedLexicographicEvenForHighBytes`
  pins it across the high byte range, where a signed comparison silently reverses.
- **Reads alongside a writer.** Reads take no lock and run correctly while the
  single writer is flushing and compacting. `StrataConcurrencyPropertyTest` runs
  readers against a live writer and checks that an acknowledged key is always
  found with its own value, that a scan completes in order, and that a rewrite or
  a delete is not undone by an older table resurfacing. Writing those tests found
  a real bug; see [BUGS.md](docs/BUGS.md).

**Not guaranteed: a crash in the middle of a compaction.** The log recovers from
anything, but the set of table files does not. A compaction writes its output
tables and then deletes the tables it consumed, and there is no manifest making
those two steps atomic, so a crash in between leaves both on disk and a reopen can
return an overwritten value or a deleted key. This is reproduced, with the failing
assertion, as STRATA-2 in [BUGS.md](docs/BUGS.md), and the design choice that
causes it is written up in [DECISIONS.md](docs/DECISIONS.md).

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
may hold in an on-disk table. A tombstone is dropped only when a merge lands it in
the deepest populated level, where no older table survives for it to shadow.

## Levels

On-disk tables are organised into levels, which is what keeps a compaction from
rewriting the whole store. A flush drops a table into level 0. Level-0 tables come
straight from the memtable and may overlap each other in key range. Every level
below is a single run: its tables hold disjoint key ranges, so at most one table
per level can hold a given key. For any key a shallower level is newer than a
deeper one, because a key only reaches level L+1 by being merged down out of level
L, and the merge lets the shallower copy win.

Two triggers move data down. When level 0 reaches four tables it is merged into
level 1. When a deeper level exceeds its table budget, one of its tables is merged
into the level below, rewriting only the tables there that overlap it. Each level's
budget is four times the one above, so the number of levels stays logarithmic in
the data size and a read touches about one table per level plus the level-0 stack.
A merge keeps the newest value per key and, when it is landing in the deepest
populated level, discards tombstones.

The name and level of each file are the whole manifest. A table is
`sst-<level>-<sequence>.sst`, so `open` rebuilds the levels from the directory
listing alone: the level says which level a table belongs to, and the sequence
orders the level-0 tables newest first. No separate manifest file is kept.

This does less work per compaction than a single full merge, so it lowers write
amplification, and it bounds read amplification. It is an honest simplification of
a real engine, though. Level-0 tables usually span most of the key range, so an
L0-into-L1 merge still tends to rewrite much of level 1, and the store is still
single-writer and still pauses the writer for the length of a compaction rather
than running it in the background.

## Build and test

No JDK on your machine needed. It builds in a container:

```bash
docker run --rm -v "$PWD":/app -w /app gradle:8.10-jdk21 gradle test
```

With a local JDK 21 and Gradle:

```bash
gradle test
```

`gradle test` runs the fast suite. The slow ones, the crash fuzzing that walks
every truncation point and every byte flip of a log, the concurrency properties
that run readers against a live writer, and the multi-level oracle run, are tagged
`slow` and run nightly instead of on every push:

```bash
gradle slowTest
```

The tests are the interesting part. Beyond the round trips, `strata` is checked
against an in-memory `TreeMap` oracle over thousands of random operations, once
purely in memory and once with a flush threshold small enough that tables spill
to disk mid-run. Others cover a tombstone shadowing an older on-disk value,
recovery from SSTables and a log together, and a compaction that folds tables down
and drops deleted keys. The property, crash and concurrency suites are listed
under [what it guarantees](#what-it-guarantees), each next to the claim it backs.

## Benchmarks

[docs/BENCHMARKS.md](docs/BENCHMARKS.md) has the measurements: write throughput,
point read latency split by where the key lives, scan throughput, write and space
amplification, read latency during a compaction against at rest, what the fsync
costs, and the same workload run against RocksDB on the same machine. It names the
machine, the JDK and the exact commands, and it reports medians and p99s rather
than means.

The harness is in [bench/](bench). It needs no JDK, no Gradle and no Maven on the
machine; `bench/bootstrap.ps1` fetches a JDK and the RocksDB jar into
`bench/.toolchain` and `bench/run.ps1` compiles and runs a scenario. Through
Gradle, if you have it:

```bash
gradle bench                                   # every scenario, n = 50000
gradle bench -Pbench.scenario=write-amp
gradle benchRocks                              # the RocksDB comparison
```

Every put fsyncs the log, so the put rate is bound by the disk rather than by the
code, and a large `n` is minutes rather than seconds.

## Command line

There is a small CLI over the store, so it is usable from a shell and not only as
a library. It runs through the `application` plugin:

```bash
gradle run --args="put mydir foo bar"
gradle run --args="get mydir foo"
gradle run --args="scan mydir"
```

The first argument is the command, the second is the store directory:

```
put <dir> <key> <value>   store the pair, print ok
get <dir> <key>           print the value, exit 0 if present, exit 1 if absent
delete <dir> <key>        remove the key
scan <dir> [from] [to]    print key\tvalue lines in key order over [from, to)
compact <dir>             run a full compaction
info <dir>                print the live key count and a short summary
```

Missing scan bounds mean open on that side, so `scan mydir b` runs from `b` to
the end and `scan mydir "" c` runs from the start up to but not including `c`.
An unknown command or a wrong argument count prints a short usage to stderr and
exits 2.

Keys and values are UTF-8 text, encoded to bytes for the store. Values with a
tab or a newline are out of scope, because `scan` prints one `key\tvalue` pair
per line, so keep keys and values to plain single-line text. Each invocation
opens the store, does its work and closes it, so run one strata command against
a directory at a time; the store is single-writer.

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
- **Block checksums.** The data is written in blocks of sixteen keys, each prefixed
  with a CRC32 over its bytes, and the sparse index points at block headers. A read
  reads and verifies one whole block. A mismatch raises a `ChecksumException` naming
  the table and offset rather than returning bytes that no longer match what was
  written. This is integrity on top of the write-time fsync, not a defence against a
  malicious rewrite: the checksum lives in the same file, so it catches bit rot and
  torn writes, not a deliberate edit of both the block and its CRC. The format marker
  is bumped for the block layout, and `open` rejects an unrecognized one.
- **Block cache.** A bounded, in-heap LRU of decoded blocks, keyed by table identity
  plus offset, so a repeated read of a hot key is served from memory without a second
  disk read or checksum check. It defaults to 1024 blocks and is configurable through
  the third argument to `open`. The bound is a block count, not a byte budget, so a
  cache of large blocks costs more memory than one of small blocks. Correctness does
  not lean on it: tables are immutable and a compaction replaces a table rather than
  mutating it, so the key never names stale bytes, and a retired table's blocks are
  dropped when it closes.
- **Leveled compaction.** Tables are organised into levels, level 0 straight from
  flushes and each level below a disjoint run whose budget is four times the one
  above. Level 0 merges into level 1 at four tables; a deeper level over its budget
  sends one table down into the overlapping tables below it. A merge keeps the
  newest value per key and drops a tombstone once it reaches the deepest populated
  level. It does less work per compaction than a full merge, so it lowers write
  amplification, and the levels persist through the file names so a reopen rebuilds
  them.
- **Ordered scans.** `scan(from, to)` returns the live pairs with key in
  `[from, to)` in ascending key order, `null` on either bound meaning open on that
  side. It is a k-way merge over one iterator per layer, the memtable and each
  SSTable, so it keeps the newest value per key, drops tombstoned keys and holds
  one entry per layer at a time rather than the whole store. A reversed or empty
  range returns nothing.

Not done yet:

- **A manifest.** The file names are the whole manifest, so the set of table files
  is the level structure and there is nothing that changes that set atomically. A
  clean shutdown is fine; a crash in the middle of a compaction is not, and can
  return an overwritten value or a deleted key. This is the one place where the
  store is knowingly not crash safe. Reproduced as STRATA-2 in
  [BUGS.md](docs/BUGS.md), and the trade that led here is in
  [DECISIONS.md](docs/DECISIONS.md).
- **Background compaction.** Compaction is leveled now, so it does far less work
  per run, but it still happens on the writer's thread and pauses it while it runs
  rather than moving to a background thread. That is what the gap between p50 and
  max in the write rows of [BENCHMARKS.md](docs/BENCHMARKS.md) is. Level-0 tables
  also tend to span the whole key range, so an L0-into-L1 merge still rewrites much
  of level 1. The reference counting that makes reads safe across a compaction is
  in place, which was the hard half.
- **Block compression** inside an SSTable. Blocks are checksummed and cached now,
  but they are stored uncompressed, so the on-disk size is the raw key and value
  bytes with no attempt to shrink them.
- **A byte-budgeted memtable.** The flush threshold is an entry count, so the store
  does not actually know how much memory the memtable is using.

The `Store` interface above these does not change as they land.

## License

MIT

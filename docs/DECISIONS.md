# Decisions

Design choices that had a real alternative, and why the alternative lost. Where a
choice costs something, the cost is stated rather than left out.

## Leveled compaction, not size-tiered

On-disk tables are organised into levels, where every level below zero is a
disjoint run. The alternative was size-tiered compaction, which groups tables of
similar size and merges a whole group at once, and which is what the store did in
its first on-disk form: a single full merge of every table into one.

Size-tiered writes less. It merges each table roughly once per tier rather than
once per level, so the same data crosses the disk fewer times. It loses on the two
things a reader cares about. Read amplification is unbounded, because several
tables in a tier can each hold the key and all of them have to be checked, whereas
a disjoint run means at most one table per level can hold it. Space amplification
is worse for the same reason: an overwritten value survives until its whole tier
is merged, so in the worst case the store holds two full copies of the data.

Leveled trades write amplification for both of those, and the trade is visible in
the numbers: see the write-amplification table in
[BENCHMARKS.md](BENCHMARKS.md). The cost is that a compaction rewrites the
overlapping tables in the level below every time, and level 0 makes that worse
here, because level-0 tables come straight from the memtable and usually span most
of the key range, so an L0-into-L1 merge still rewrites much of level 1. A real
engine limits that with partitioned level-0 tables or a sub-compaction split;
strata does not.

## ConcurrentSkipListMap for the memtable

The memtable is a `java.util.concurrent.ConcurrentSkipListMap`. The alternative
seriously considered was a `TreeMap` behind the same lock that already serialises
writers, which would be faster per operation and would allocate less.

The lock lost because it would have made reads take a lock, and reads running
without one is the entire concurrency story of this store. A single writer already
serialises itself through `synchronized` on `put` and `delete`, so the concurrent
map buys nothing on the write side; what it buys is that a reader can walk the
memtable while a writer is mutating it and see a consistent view without
coordinating. That is also what lets a flush publish its table before clearing the
memtable and have a key be findable in one place or the other throughout.

The cost is memory. A skip-list node per entry, plus a `Bytes` wrapper per key and
a `byte[]` per value, is a lot of object header for a hundred-byte value, and it
is why the flush threshold is an entry count rather than a byte budget: the store
does not actually know how much memory the memtable is using. A real engine
allocates memtable entries out of an arena and flushes on bytes. That is a known
gap, not an oversight.

## Ten bits per key of bloom filter, k = 7

Every table carries a bloom filter sized at ten bits per key with seven hash
positions, derived by double hashing rather than seven separate hashes. Ten bits
per key puts the false-positive rate near one percent, and seven is close to the
optimal number of hashes at that density.

The alternative was to size the filter per level. A filter is only useful on a
table that does not hold the key, and the deeper the level, the more tables a read
has already ruled out, so the standard refinement is to spend more bits in the
shallow levels and fewer in the deep ones, or to drop the filter from the deepest
level entirely, where a read that got that far will usually hit. That lost on
complexity for a store this size: the sizing would have to be decided when a table
is written, but a table's level changes when it is compacted downward, so the
filter would either be wrong after a move or the move would have to rewrite it.

The cost is that the deepest level pays for a filter it rarely benefits from. At
ten bits per key that is 1.25 bytes per key of the on-disk table and of the
process heap, since a table's filter is loaded into memory when it is opened. The
[miss row in BENCHMARKS.md](BENCHMARKS.md) is the return on that spend.

## A length-prefixed, CRC-checked WAL with no block framing

Each log record is `[payloadLen][crc32][payload]`, appended back to back with no
alignment. The alternative was RocksDB's and LevelDB's format, which cuts the log
into fixed 32 KiB blocks and splits a record across them with first/middle/last
fragment markers.

Block framing exists so a reader can resynchronise. If a record in the middle of
the log is damaged, a block-framed reader can skip to the next block boundary and
carry on, recovering everything after the damage. The flat format cannot: it has
no way to find where the next record starts, so recovery stops at the first bad
record and truncates everything from there.

That is a real loss, and it is acceptable here for a specific reason. Damage in
the middle of the log means the storage lied about a write it had already
acknowledged, and a store that resynchronises past that is silently serving a
history with a hole in it. Damage at the tail, on the other hand, is the ordinary
case: the process died mid-append, and truncating to the last whole record is
exactly right. The flat format handles the ordinary case correctly and refuses to
guess about the other one.
`StrataDurabilityPropertyTest.anySingleByteCorruptionOfTheLogNeverSurfacesAWrongValue`
pins that behaviour over every single-byte corruption of the log.

## fsync on every write

`put` and `delete` append to the log and `fsync` before returning, so a call that
returned has survived a power cut. The alternative is group commit: let writes
accumulate for a millisecond or a batch and sync once for all of them, which is
what every production engine does by default.

Group commit lost because the guarantee at the top of the README is the reason
this store exists, and a guarantee with a window in it is a different guarantee
that is much harder to state precisely. It is also the wrong first thing to build:
the correctness of the durable path is easier to reason about and to test when
there is no batching in it.

The cost is enormous and it is the single largest number in the benchmarks.
Measured on this machine, the fsync is over ninety-eight percent of the time a
`put` takes; see the fsync table in [BENCHMARKS.md](BENCHMARKS.md) for the exact
figures. The write throughput of this store is a property of the disk, not of the
code, and no amount of optimisation elsewhere will move it while this policy
stands. `StrataStore.openWithoutSync` exists only so the benchmark can price it,
and is documented as not durable.

## A manifest file, rather than the file names being the manifest

The store used to have no manifest. A table is `sst-<level>-<seq>.sst`, the name
carries the level and the sequence, and that is genuinely enough to rebuild a
level structure from a directory listing. It made the on-disk state completely
legible, with no second source of truth to fall out of step with the first, and
it is the design this project ran with for as long as it was only ever shut down
cleanly.

It was wrong, and the way it was wrong is worth keeping. The names describe the
*shape* of the structure and say nothing about *membership*. A compaction is two
durable steps, write the outputs and then delete the inputs, and a crash in
between leaves both sets on disk under names that all parse. Recovery then builds
a level that is supposed to be a disjoint run out of two tables covering the same
key, one stale and one fresh, and picks between them with a sort on first key
that knows nothing about which is newer. That is STRATA-2 in [BUGS.md](BUGS.md),
and it returned overwritten values and resurrected deleted keys.

The fix is a `manifest` file holding the set of live table names, replaced by
writing a temp file, fsyncing it, and renaming it over the old one. The rename is
the commit. Everything else follows from putting it in the right place: a
compaction commits after its outputs are written and before its inputs are
deleted, a flush commits before the log is reset, and a file the manifest does
not name is not part of the store no matter how complete it is.

The alternative considered was LevelDB's `MANIFEST` plus `CURRENT`, a log of
edits to the level structure with a pointer to the live one. It lost on size. A
log of edits earns its keep when the structure is large enough that rewriting the
whole set per commit costs something, and when you want the edit history for
other reasons. Here the set is a few dozen names, so writing all of them is
cheaper than the machinery for writing the difference, and a whole-set file has
the property that it cannot drift: there is no replay to get wrong.

The cost, stated plainly: the directory is no longer self-describing. `ls` used
to tell you what the store had, and now it tells you what files exist, of which
some may be orphans awaiting the next open. The manifest is a second thing that
can be lost, and losing it is unrecoverable in the sense that the store will not
guess. A damaged manifest fails the open rather than falling back to the listing,
because falling back is precisely the behaviour that had the bug.

## Compaction runs on the writer's thread

`flush` and `compact` are called from inside `put`, on whichever thread is
writing. The alternative, a background compaction thread, is what a real engine
does and is listed in the README as not done.

It has not been built yet because the reference counting that makes reads safe
across a compaction is the hard part of it, and that is now in place and tested:
a reader takes a reference on each table it reads, compaction drops the store's
reference rather than closing the file, and the last one out closes and deletes
it. Moving the compaction to its own thread is the smaller remaining half.

The cost until then is the writer's tail latency. A `put` that triggers a flush
pays for the flush, and a `put` that triggers a compaction pays for the whole
merge, which shows up directly as the gap between p50 and max in the write rows of
[BENCHMARKS.md](BENCHMARKS.md). Readers are unaffected, because they never take
the writer's lock.

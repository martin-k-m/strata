# Exercises: answers

Answers to [EXERCISES.md](EXERCISES.md). Sources named so every claim is
checkable.

---

## Part 1

**1. Key order barely matters.**

An LSM turns every write into an append to the log regardless of key, and the sort
happens in memory in the `ConcurrentSkipListMap`. Nothing is placed on disk by key
position at write time. A B-tree has to find and modify the page the key belongs
in, so random inserts scatter page writes across the file; strata never does that.
That property is what the whole design exists to buy, and the 7% gap is the
measurement of it.

The absolute figure, about 1,700 puts per second, is not a statement about this
code. It is a statement about how many times per second this NVMe will acknowledge
an fsync.

**2. A miss is cheaper than a hit.**

A miss touches no table's data at all. Every bloom filter answers "definitely
absent", so the read returns without a single disk read, block decode or checksum
check. A hit has to locate the block through the sparse index, read it, verify its
CRC and decode it. At p50 the miss (700 ns) even beats the memtable hit (800 ns).

It is the return on ten bits per key of bloom filter at k = 7, which puts the false
positive rate near one percent. Those bits are paid once per key at write time,
cost 1.25 bytes per key on disk and in heap, and are refunded on every negative
lookup forever.

Caveat from `BENCHMARKS.md`: this is the row to trust least in the RocksDB
comparison, because 50,000 keys is 1.5 MB living in one or two tables at one or two
levels. Scale the dataset until strata has five or six levels and this inverts.

**3. The compaction tax.**

`flush` and `compact` are called from inside `put`, on whichever thread is writing
(`DECISIONS.md`). A put that triggers a flush pays for the flush; a put that
triggers a compaction pays for the whole merge. That is the 68x gap between p50 and
max.

The decision that puts it there is "compaction runs on the writer's thread". It
lands on the writer and not on readers, because readers never take the writer's
lock: they snapshot the level structure, take a reference on each table, and read.
That is why reads during compaction degrade only 1.5x at p99 (21.0 us to 31.7 us)
and 16% in throughput rather than stalling.

**4. Parity on writes, loss on reads.**

Durable writes are at parity because neither engine's code is in the critical path.
Both append to a log and wait for the disk. Of the 535 us a durable put takes at
p50, 2.2 us is strata and 533 us is the disk. That row is a measurement of the NVMe
drive taken twice.

The on-disk read has no fsync in it, so it is all code: block format, index layout,
iterator. RocksDB has years of tuning there and strata does not, so RocksDB wins by
2.5x. Along with the full scan (2.2x), those are the two rows that measure real
work and the two to take seriously.

The rows strata wins need explaining rather than celebrating. Unsynced writes:
strata wins 2.5x because it does less, since RocksDB's write path carries sequence
numbers for MVCC, write batches, column-family routing and snapshot bookkeeping.
That is a smaller feature set, not a better implementation. The miss: see question
2.

**5. The two floors.**

WAL is a constant 1.15x and flush a constant 1.10x at every threshold, for a floor
of about 2.25x.

The WAL 1.15x is record framing: 13 bytes of length, CRC, type and two length
fields against a 116-byte record. Every value is written to the log once before it
is ever flushed. That is the price of the durability guarantee.

The flush 1.10x is SSTable format overhead. Every value is written again when the
memtable spills. That is the price of having an on-disk sorted table at all.

Neither is compaction, so no compaction strategy can touch either. Both are
independent of the flush threshold, which is what the table shows by holding them
constant down all three rows.

**6. Smaller memtable, much more compaction.**

Smaller memtables mean more, smaller tables. Level 0 hits its four-table trigger
more often, and each of those merges rewrites the overlapping tables in level 1
again. So the same data crosses the disk more times: the merge granularity got
finer while the total volume stayed the same. Halving the memtable does not halve
the work, it multiplies it.

The trade in one sentence: memory spent on the memtable is disk writes not spent
on compaction.

Worth adding that strata makes this worse than a production engine. Level-0 tables
come straight from the memtable and usually span most of the key range, so an
L0-into-L1 merge rewrites much of level 1 whatever the keys are. A real engine
limits that with partitioned level-0 tables or a sub-compaction split; strata does
neither.

**7. Space amplification.**

The 8% is format overhead, not garbage: roughly 8 bytes per entry of key and value
length prefixes, 1.75 bytes per key of sparse index, 1.25 bytes per key of bloom
filter, and half a byte per key of block header. About 11.5 bytes against a
116-byte record, a little under 10%. The measured 8% is that and nothing else,
which is why `compact()` cannot improve it.

No stale data for two separate reasons. The memtable is a map, so three of every
four writes to a key (50,000 writes over 12,500 distinct keys) collapse before they
ever reach disk. And the level-0 trigger of four tables fires early enough that the
overwrites which do reach disk are merged away quickly.

What it does not establish: an upper bound. The harness never catches the store in
a state with much garbage in it, so 1.08x is the steady state only. A workload that
overwrote a large working set between compactions would look worse and this does
not measure it.

**8. The concurrent-read defect (STRATA-1).**

`get` and `scan` read the `levels` field once to take a snapshot, then walk the
tables in that snapshot. Compaction, on the writer's thread, built the new level
structure, published it by assigning to `levels`, and then immediately closed and
deleted the tables it had consumed.

The interleaving: a reader takes its snapshot, compaction publishes and then closes
a consumed table's `FileChannel`, and the reader's next block read fails on a closed
channel. The store reports a read failure for a key that never went anywhere. The
failure text named tables at levels 0, 1 and 2 that a compaction had just consumed,
which is the mechanism visible in the symptom.

Publishing first is necessary: any read starting *after* the assignment sees the new
structure and never finds the retired tables. It is not sufficient because it says
nothing about the read that started *before* the assignment and is part way through
a table the compaction is about to close. Ordering the publish correctly closes the
window for future readers and does nothing for in-flight ones. Only a lifetime
mechanism does that, which is what reference counting is: a reader takes a reference
on every table it reads, compaction drops the store's own reference rather than
closing the file, and whichever leaves last closes the channel and deletes the file.
A reader that arrives just too late to take a reference retakes its snapshot, and
that terminates rather than spinning precisely because compaction always publishes
before retiring.

Two things worth remembering. The interface had admitted the bug: `Store.scan`'s
javadoc carried a caveat about concurrent compaction that directly contradicted
`StrataStore`'s class-level claim that reads run alongside a writer without locking.
One of the two was wrong, and the caveat was describing the bug. And every test in
the durability suite was single-threaded, so the concurrency claim had been
documentation rather than a tested property until a test ran two threads at once.

**9. (design) The manifest.**

A good answer covers these.

*Record contents.* A log of level-structure *edits*, not snapshots: each record
names the tables added and the tables removed, with their levels and sequence
numbers, plus the next sequence number to allocate. Length-prefixed and CRC-checked
like the WAL, so the same torn-tail discipline applies. A `CURRENT` pointer names
the live manifest, as in LevelDB and RocksDB.

*Ordering.* Write and fsync all output tables and rename them into place first.
Then append and fsync one manifest record that removes the inputs and adds the
outputs. Only then delete the input files. The manifest record is the commit point:
before it lands, the outputs are orphans nothing references; after it lands, the
inputs are orphans nothing references. The set changes atomically because one
durable write decides it.

*Torn manifest tail.* Same rule as the WAL: replay in order and stop at the first
record that is not whole and correct. A half-written edit is not applied, so the
store reopens on the previous level structure, and the outputs of the interrupted
compaction are orphans to be collected. That is correct rather than merely safe:
the compaction simply did not happen.

*Reconciling the file set with the manifest.* The manifest becomes the single source
of truth and the directory listing stops being the state. `open` builds levels from
the manifest alone. A file present on disk but absent from the manifest is an
orphan. A file named by the manifest but absent from disk is corruption and should
be a loud failure, because the manifest says it committed.

*Garbage collection.* At the end of `open`, list the directory, subtract the set the
manifest references, and delete the remainder. Doing it at open is enough and avoids
racing live readers; doing it during operation needs the same reference counting
that already exists for compaction, since an orphan may be a table a reader is
holding.

*Why the cheap fixes lost.* Resolving overlaps by sequence number at open is right
whenever the compaction finished writing all of its output. It is wrong when the
crash landed in the middle of `writeRun`, which writes a merged run as several
tables one after another: only part of the output exists, and discarding an input
because it overlaps a partial output drops the keys the partial output does not
cover. That turns a wrong answer into a lost write, which is worse. Refusing to open
on an overlap trades silent corruption for a loud failure, which is the right
direction, but it contradicts the property log recovery is built around, that a hard
kill never leaves a store that will not open, and it leaves the operator with no way
forward.

*Does the manifest design have the same problem?* No, and the reason is the crux.
Both cheap fixes try to infer intent from the file set, and the file set cannot
record intent. A partial `writeRun` is indistinguishable from a complete one by
inspection. The manifest records the intent explicitly and atomically, so a partial
`writeRun` never has a manifest record and is simply never adopted.

**10. (design) Background compaction.**

*What the thread owns.* The level structure below the memtable, and the exclusive
right to publish a new `levels`. `put` continues to own the memtable and the log.

*Synchronisation when the memtable is full.* The clean split is an immutable
memtable: `put` swaps the active memtable for a fresh one, hands the old one to the
background thread, and returns. Reads consult active, then immutable, then levels,
in that order. Nothing blocks in the common case.

*Bounding the writer.* Two backpressure points. If an immutable memtable is already
awaiting flush, `put` blocks until it is taken. And a level-0 stack above some
threshold slows or stalls writes, which is what RocksDB's slowdown and stop triggers
are. Without a stall a fast writer outruns the compactor, level 0 grows without
bound, and read amplification grows with it, since level 0 is not a disjoint run and
every table in it must be checked.

*`close()` mid-compaction.* The compaction must either complete or be abandoned
before its manifest record is written. Abandoning is cheaper and correct: stop
before the commit point, leave the outputs as orphans, and let the next `open`
collect them. `close()` must join the thread rather than interrupt it mid-rename.

*The crash window.* It does not get worse; it gets more frequent, which is the
point worth making. The window between "outputs written" and "inputs retired" is
now hit by a crash at any moment rather than only while a writer is inside `put`.
That makes STRATA-2 more likely to be observed, so the manifest from question 9 is
a prerequisite for this work rather than a follow-up.

*What would still pass while the design was wrong.* Every single-threaded test in
the durability suite, and `restartsInterleavedWithCompactionMatchTheOracle`, because
none of them have two threads racing on the level structure. That is exactly the gap
STRATA-1 lived in. The concurrency property tests would catch a lifetime bug because
of the reference counting, but nothing currently asserts *progress*: that the
compactor keeps up, that level 0 stays bounded, or that a writer stalled by
backpressure is eventually released. Those are the new properties. The cleanest one
to assert is that under a sustained writer for N seconds, the level-0 table count
never exceeds the stall threshold and total writes complete, which fails on both a
deadlocked compactor and an unbounded one.

---

## Part 2

**Scenario A: crash mid-compaction.**

The overwritten key reads back as its **old** value and the deleted key reads back
as **present**. Both are reproduced by `StrataCrashConsistencyTest`: a key last
written as `new-0` comes back as `old-0` (the assertion fails on byte 110 against
111, `n` against `o`).

The reason `open` cannot tell the sets apart is that **the file names are the entire
manifest**. A table is `sst-<level>-<seq>.sst`, and `open` rebuilds the level
structure from the directory listing alone. The crash leaves both the inputs and
the outputs sitting there under valid names, and nothing records that one set
supersedes the other.

What that produces is a level below zero that is no longer a disjoint run. Every
level below zero is supposed to hold tables with non-overlapping key ranges, which
is what lets a lookup take the first table whose range covers the key and stop.
After the crash two tables cover the same key, and the code that picks between them
sorts the level by first key. That sort knows about key order and nothing about
which table is newer, so the stale one can come first and answer the lookup.

The atomic rename per file guarantees that **each table file is individually
whole**: present and complete, or absent. It says nothing about the set of files
being consistent, and the set is the state. That distinction is the whole of
STRATA-2.

Status: reproduced, not fixed. The two tests are `@Disabled` with a reason pointing
at `BUGS.md`, so the suite stays green while the defect stays visible, and they are
the specification of the fix.

**Scenario B: an unclosed scan.**

The tables that scan referenced stay open and stay on disk, forever, or until the
process exits.

`scan` reads lazily, so it holds its references until the stream is closed.
Compaction drops the store's own reference rather than closing the file, and the
last holder closes the channel and deletes it. An unclosed scan is a reference that
is never given back, so the file is never deleted.

It is a **resource leak**, not a correctness bug, and it is documented behaviour
rather than an accident. The reference counting that fixed STRATA-1 changed the
contract: closing a scan went from tidy to required. The deferred delete is
deliberately deferred, and `aTableHeldOpenAcrossACompactionIsDeletedWhenTheReaderLetsGo`
exists specifically because deferring a delete must not mean forgetting it. It holds
a scan open across a compaction, checks the file is still there and still readable,
and checks it is gone once the reader lets go. Without that test, the fix for a
crash would have been a disk leak.

**Scenario C: crash between the table rename and the log reset.**

Nothing is lost and nothing is duplicated. Recovery replays the log in write order
on top of a store that already contains the flushed table, and a replay in write
order is **idempotent**: every replayed record writes the same key the same value
it already has, and the memtable shadows the table for those keys until the next
flush collapses them again.

The ordering doing the work is inside `flush`: **the table is made durable before
the log is cleared**. So the crash window leaves the data in both places rather
than in neither.

Reverse it, clear the log and then write the table, and the window leaves the data
in neither place. A crash there is straightforward data loss of acknowledged
writes, which is the one thing the store's headline guarantee forbids.

`StrataCrashConsistencyTest.aCrashBetweenAFlushAndTheLogResetLosesNothing` pins
this, and it is one of the two tests in that class that pass and are not disabled.

---

## Part 3: the WAL reimplementation

Notes for whoever does it.

**The format is deliberately flat.** `[payloadLen][crc32][payload]`, back to back,
no alignment, no block framing. The alternative is LevelDB's and RocksDB's 32 KiB
blocks with first/middle/last fragment markers.

**Why flat, and why the corruption test is the specification.** Block framing exists
so a reader can resynchronise past damage in the middle of the log. The flat format
cannot: it has no way to find where the next record starts, so recovery stops at the
first bad record and truncates everything from there. That is a real loss and it is
the correct trade here. Damage in the middle of the log means the storage lied about
a write it had already acknowledged, and a store that resynchronises past that is
silently serving a history with a hole in it. Damage at the tail is the ordinary
case: the process died mid-append, and truncating to the last whole record is
exactly right. The flat format handles the ordinary case correctly and refuses to
guess about the other one.

So if `anySingleByteCorruptionOfTheLogNeverSurfacesAWrongValue` fails, the likely
cause is a reimplementation that tried to be clever and skip forward. Do not make it
pass by loosening the assertion.

**fsync before return is the contract**, not a tuning choice. A `put` that returned
has survived a power cut. `StrataStore.openWithoutSync` exists only so the benchmark
can price it, and is documented as not durable. That is where the 249x and the 99.6%
come from.

**The CRC covers the payload only**, and the length is read before it can be
validated, so a corrupted length is a case the recovery has to survive without
reading past the end of the file. The truncation quantification will find that
immediately if you get it wrong.

**Reset after flush** is a truncation to zero length, and question 3 of part 2 is
why it happens *after* the table is durable and not before.

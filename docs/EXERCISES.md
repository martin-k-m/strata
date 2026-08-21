# Exercises

Questions I should be able to answer cold about strata, without opening the
source. Ordered easy to hard. Answers are in
[EXERCISES-ANSWERS.md](EXERCISES-ANSWERS.md), in a separate file so this one can
be handed to someone else.

Every question is grounded in code that exists and in numbers from
[BENCHMARKS.md](BENCHMARKS.md), [BUGS.md](BUGS.md) or
[DECISIONS.md](DECISIONS.md).

---

## Part 1: ten questions

**1.** Sequential and random key writes are within 7% of each other (1,717 ops/s
against 1,608). On a B-tree that gap would be large. Explain why an LSM tree
barely notices key order on the write path.

**2.** A miss costs 700 ns at p50 and an on-disk hit costs 8.8 us. Explain why
asking for a key that does not exist is more than ten times cheaper than asking
for one that does, and say what that result is the return on.

**3.** p50 for a `put` is 515 us and the worst put in the run took 34.8 ms, about
68 times longer. Name the mechanism, and say which design decision makes it show
up in the writer's latency rather than somewhere else.

**4.** The fsync is 249x the throughput and 99.6% of the latency of a `put`.
Given that, explain why strata's durable write throughput comes out at parity
with RocksDB (1,620 against 1,595) while RocksDB wins the on-disk read by 2.5x.

**5.** Write amplification decomposes into a WAL term, a flush term and a
compaction term. State the constant value of the first two, explain what each of
them is physically, and say why no compaction strategy can lower either.

**6.** Dropping the flush threshold from 100,000 to 1,250, a factor of 80, took
the compaction term from 1.10x to 8.17x, a factor of 7.4. Explain the mechanism
in terms of the level-0 trigger, and state the trade in one sentence.

**7.** Space amplification measures 1.08x with the flush threshold above the
level-0 trigger, where `compact()` changes nothing, and 2.65x with it below,
where `compact()` takes it back to 1.08x. Account for the 8% that never goes
away, explain why the first case has no stale data to reclaim and the second
does, and say what neither measurement establishes.

**8.** Before the reference-counting fix, a `get` for a key written long ago and
never touched could throw. Describe the exact interleaving, and explain why
"publish the new level structure before retiring the old tables" was necessary
but not sufficient.

**9. (design)** STRATA-2 is open: a crash between a compaction writing its outputs
and deleting its inputs resurrects overwritten and deleted keys. Design the
manifest that fixes it. Say what a record contains, when it is written relative to
the output tables and the input deletions, what `open` does with a manifest whose
tail is torn, how a stale manifest and a stale set of files are reconciled, and
how you would garbage-collect table files that no manifest record references. Then
explain why the two cheaper fixes (resolve overlaps by sequence number at open, or
refuse to open on overlap) were rejected, and say whether your design has the same
problem.

**10. (design)** Move compaction off the writer's thread. Say what the background
thread owns, what synchronisation `put` needs when the memtable is full and the
flush has not finished, how you bound the level-0 stack so a fast writer cannot
outrun the compactor, what happens on `close()` mid-compaction, and how the crash
window changes. Then say which existing tests would still pass while the design
was wrong, and what new property you would assert.

---

## Part 2: predict the failure

For each scenario, say what the system does, and why. "Why" means the mechanism.

**Scenario A: the process is killed in the middle of a compaction, and the store
is reopened.**

The compaction had already written and fsynced its output tables, atomically
renamed them into place, and had not yet deleted the tables it consumed. Some of
the keys in those tables were overwritten before the compaction, and one was
deleted.

What does a `get` return for an overwritten key, and for the deleted one? Name the
exact reason `open` cannot tell the two sets of files apart, and say what the
atomic rename per file *does* guarantee.

**Scenario B: a reader opens a scan, iterates 10 entries, and then stops
consuming without closing the stream. A writer keeps writing, and several
compactions run.**

What happens to the tables that scan is holding? What happens on disk? Is this a
correctness bug, a resource leak, or documented behaviour, and what changed to
make it so?

**Scenario C: the process is killed between a flush's table rename and the log
reset, and the store is reopened.**

The memtable had spilled, the SSTable is on disk and complete, and the write-ahead
log has not yet been truncated, so it still contains every record that was just
flushed.

What does recovery do with the duplicated records? Is anything lost, and is
anything duplicated? Which ordering inside `flush` is doing the work here, and what
would break if it were reversed?

---

## Part 3: delete it and write it again

**Component: `WriteAheadLog.java`.**

Delete it and reimplement it from scratch. Keep the tests. It is one class, the
format is three fields, and its behaviour is quantified over exhaustively rather
than sampled, which makes it the best-specified thing in the repository.

You are reimplementing:

- The record format: `[payloadLen][crc32][payload]`, appended back to back with no
  alignment and no block framing.
- Append plus `fsync` before return, so a call that returned has survived a power
  cut.
- Recovery: replay in write order, stopping at the first record that is not whole
  and correct, and truncating from there.
- Reset after a flush.

**Verification.** A correct reimplementation passes:

```sh
gradle test --tests 'dev.martinkm.strata.StrataDurabilityPropertyTest'
```

That suite quantifies over every truncation point of the log and every single-byte
corruption of it, so it is the specification rather than a sample. In particular
`anySingleByteCorruptionOfTheLogNeverSurfacesAWrongValue` is the one that will fail
if you reach for block framing and resynchronisation, and you should be able to say
why that is the correct behaviour rather than a limitation before you make it pass.

Then run the full suite, because the log is not the only thing that touches it:

```sh
gradle test
```

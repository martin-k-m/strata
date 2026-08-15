# Bugs

Real defects in strata, each with the mechanism rather than the symptom, and the
test that caught it. I write these up because the interesting part of building a
storage engine is not that it works, it is the specific ways it did not.

Both entries here are about the same moment: the instant a compaction swaps one
set of tables for another. That is not a coincidence. The write path is a log and
a sorted map and is hard to get wrong; compaction is the only place where the
store's persistent state changes shape while something else may be looking at it.

---

## STRATA-1: a compaction could break a concurrent read

**Status:** fixed in [`489c170`](https://github.com/martin-k-m/strata/commit/489c170).

### Symptom

A `get` for a key that was written long before, never deleted, and never touched
since, would occasionally throw instead of returning it. The key was present the
whole time. The failure was timing dependent and only ever happened when a reader
was running at the same time as a writer.

Checking out the commit before the fix and running the current concurrency suite
against it reproduces this, and all four tests fail the same way:

```
$ git worktree add /tmp/prefix a66b455          # the commit before the fix
$ cp src/test/java/dev/martinkm/strata/StrataConcurrencyPropertyTest.java /tmp/prefix/src/test/...
$ javac ... && java -jar junit.jar execute --select-class=...StrataConcurrencyPropertyTest

aKeyWrittenBeforeAReadIsAlwaysFoundWhileFlushingAndCompacting
  => a worker failed ==> expected: <[]> but was:
     <[java.io.UncheckedIOException: sstable read failed .../sst-00-0000000023.sst]>
aScanRunsToCompletionAlongsideAWriter
  => a worker failed ==> expected: <[]> but was:
     <[java.io.UncheckedIOException: sstable read failed .../sst-00-0000000091.sst,
        java.io.UncheckedIOException: sstable read failed .../sst-00-0000000088.sst, ...]>
aRewriteAndADeleteAreVisibleToAConcurrentReader
  => a worker failed ==> expected: <[]> but was:
     <[java.io.UncheckedIOException: sstable read failed .../sst-01-0000000069.sst]>
aTableHeldOpenAcrossACompactionIsDeletedWhenTheReaderLetsGo
  => java.io.UncheckedIOException: sstable read failed .../sst-02-0000000160.sst

[         0 tests successful      ]
[         4 tests failed          ]
```

The named tables are the ones a compaction had just consumed, at levels 0, 1 and
2, which is the mechanism visible in the failure text: the file the reader was
inside is the file the compaction had closed.

### Root cause

`get` and `scan` were lock-free by design. They read the `levels` field once to
take a snapshot of the level structure, then walked the tables in that snapshot
looking for the key. Compaction, running on the writer's thread, did the mirror
image: it built the new level structure, published it by assigning to `levels`,
and then immediately closed and deleted the tables it had consumed.

Publishing first is correct, and it is what the class documentation said it
relied on: any read starting after the assignment sees the new structure and
never finds the retired tables. The hole is the read that started *before* the
assignment. It is already holding a reference to a consumed table and is part way
through reading it when compaction closes that table's `FileChannel` out from
under it. The next block read fails on a closed channel, and the store reports a
read failure for a key that never went anywhere.

The interface had actually admitted this. `Store.scan`'s javadoc carried a caveat
saying a concurrent compaction could disturb a scan, which directly contradicted
`StrataStore`'s class-level claim that reads run alongside a writer without
locking. One of the two was wrong; the caveat was describing the bug.

### How it was caught

`StrataConcurrencyPropertyTest.aKeyWrittenBeforeAReadIsAlwaysFoundWhileFlushingAndCompacting`,
which was written to test the concurrency claim rather than to hunt for this. It
runs several reader threads against one writer with a flush threshold of 64, so
level 0 reaches its compaction trigger over and over during the run. The writer
publishes its progress only after `put` returns, so every key a reader asks for
was acknowledged before the read began.

That last point is the most valuable line in the test:

```java
store.put(key(i), value(i));
// Only now is key i safe to ask for.
written.set(i);
```

It is what makes a failure unambiguous. There is no window in which an
acknowledged key is legitimately missing or unreadable, so the test does not have
to reason about races to decide whether an answer was allowed. Absent means lost,
and an exception means broken, and neither needs a judgement call.

Every test in the durability suite was single-threaded, which is why this survived
until a test ran two threads at once. The concurrency claim had been documentation,
not a tested property.

### Fix

Reference counting on `SSTable`. A reader takes a reference on every table it is
about to read and gives it back when it is done; compaction drops the store's own
reference rather than closing the file; whichever of them leaves last closes the
channel and deletes the file. A reader that arrives just too late to take a
reference retakes its snapshot instead, and finds the same keys in the tables the
compaction wrote. That terminates rather than spinning, because compaction always
publishes the new structure before it retires the old tables.

One consequence is worth stating because it changed the contract. `scan` reads
lazily, so it holds its references until the stream is closed, which makes closing
a scan *required* rather than tidy: an unclosed scan keeps a compacted file on
disk forever.

### Regression tests

- `StrataConcurrencyPropertyTest.aKeyWrittenBeforeAReadIsAlwaysFoundWhileFlushingAndCompacting`,
  the one that found it.
- `aScanRunsToCompletionAlongsideAWriter`, the same property for the lazy path.
- `aRewriteAndADeleteAreVisibleToAConcurrentReader`, so the fix cannot be a stale
  snapshot that merely avoids the exception.
- `aTableHeldOpenAcrossACompactionIsDeletedWhenTheReaderLetsGo`, because deferring
  a delete must not mean forgetting it. It holds a scan open across a compaction,
  checks the file is still there and still readable, and checks it is gone once
  the reader lets go. Without this, the fix for a crash would have been a disk
  leak.

---

## STRATA-2: a crash during a compaction resurrects overwritten and deleted keys

**Status:** fixed in [`39786a4`](https://github.com/martin-k-m/strata/commit/39786a4). A `manifest` file now records the set of live
tables and is replaced by an atomic rename, so the set changes in one step. See
[DECISIONS.md](DECISIONS.md) for why the store went without one for as long as it
did, and what it cost.

### Symptom

Kill the process in the middle of a compaction and reopen the store. A key whose
newest value was `new-0` reads back as `old-0`. A key that was deleted reads back
as present.

### Root cause

The file names are the entire manifest. A table is `sst-<level>-<seq>.sst`, and
`open` rebuilds the level structure from the directory listing alone: the level
says which level a table belongs to, the sequence orders the level-0 stack. There
is no manifest file, and so nothing that changes the *set* of table files
atomically.

A compaction is two separate durable steps. First it writes its output tables,
each fsynced and atomically renamed into place, so each individual file is either
absent or complete. Then it retires the tables it consumed, whose files are
deleted once the last reader leaves. A crash is not a reader leaving. The process
dies with both the inputs and the outputs sitting in the directory under valid
names, and `open` has no way to tell that one set supersedes the other.

What that produces is a level below zero that is no longer a disjoint run. Every
level below zero is supposed to hold tables with non-overlapping key ranges, which
is exactly what lets a lookup take the first table whose range covers the key and
stop. After this crash there are two tables covering the same key, one stale and
one fresh, and the code that picks between them sorts the level by first key. That
sort knows about key order and nothing about which table is newer, so the stale
one can come first and answer the lookup.

The atomic rename per file is doing real work here and it is worth being precise
about what it does not do. It guarantees each table file is individually whole. It
says nothing about the set of files being consistent, and the set is the state.

### How it was caught

`StrataCrashConsistencyTest`, written specifically for the gap the existing crash
tests leave. `StrataDurabilityPropertyTest` is thorough about the log, quantifying
over every truncation point and every single-byte corruption, but it deliberately
keeps every write under the flush threshold so that no SSTable ever exists. That
is a sound way to isolate the log, and it means the other half of the persistent
state, the table files, had never been crash tested at all.

The tests simulate the crash by reconstructing the directory rather than killing a
process, which is what makes them deterministic. Snapshot the table files before a
compaction, run the compaction, snapshot again, then build a third directory
holding the union of the two sets. That union is precisely what a crash between
"outputs written" and "inputs deleted" leaves behind. There is one assertion that
keeps the test honest about whether it is simulating anything at all:

```java
assertTrue(union.size() > after.size(),
        "the compaction must have deleted some table, or this simulates nothing");
```

Without it, a change to the compaction triggers could quietly turn the test into a
tautology that passes because no table was ever consumed.

Reopening on that union fails:

```
StrataCrashConsistencyTest.aCrashBetweenCompactionOutputAndInputDeletionKeepsTheNewestValue
  => a crash during compaction resurrected the pre-compaction value for key 0
     ==> array contents differ at index [0], expected: <110> but was: <111>

StrataCrashConsistencyTest.aCrashDuringCompactionDoesNotResurrectADeletedKey
  => a crash during compaction resurrected deleted key 0
     ==> expected: <true> but was: <false>
```

110 is `n` and 111 is `o`: the store returned `old-0` for a key last written as
`new-0`.

### Fix

Not written. The correct fix is a manifest: a log of level-structure edits, where
the record that swaps a compaction's inputs for its outputs is a single durable
write, so a reopen sees either the old set or the new one and never both. LevelDB
and RocksDB both do this, with a `MANIFEST` file and a `CURRENT` pointer.

I looked at two cheaper fixes and rejected both, which is worth recording because
they both look like they work.

The first is to resolve the overlap at open time: within a level, if two tables
overlap, keep the one with the higher sequence number, since compaction output
always gets a sequence above its inputs. That is right whenever the compaction
finished writing all of its output. It is wrong when the crash landed in the
middle of `writeRun`, which writes a merged run as several tables one after
another. Then only part of the output exists, and discarding an input because it
overlaps a partial output drops the keys the partial output does not cover. It
turns a wrong answer into a lost write, which is worse.

The second is to make `open` refuse to start when it finds an overlap in a level
below zero. That trades silent corruption for a loud failure, which is the right
direction, but it contradicts the property the log recovery is built around, that
a hard kill never leaves a store that will not open, and it leaves the operator
with no way forward.

Both point at the same conclusion: the information needed to resolve this is not
in the directory listing, because the directory listing cannot record intent. That
is the actual cost of having no manifest, and it is now written up as such in
[DECISIONS.md](DECISIONS.md) rather than left as an implied simplification.

### Fix

`Manifest`, a file holding the set of live table names, written as a temp file,
fsynced, and renamed over the old one. The rename is the commit. `compactionStep`
commits after writing its outputs and before retiring its inputs, `flush` commits
before resetting the log, and `open` takes the manifest as the truth: a table file
the manifest does not name is an orphan from a crash on one side or the other of
a commit, and it is ignored and deleted. A store with no manifest is one written
before this existed, so its listing is adopted as the set and committed.

The manifest also carries the sequence counter. Deleting orphans already prevents
a stale file colliding with a fresh one, but a delete that fails should not be
able to hand out a sequence number twice, so the counter is taken from the
manifest rather than from the highest name found on disk.

A damaged manifest fails the open. It does not fall back to the directory
listing, because the listing is what had the bug.

### Regression tests

`StrataCrashConsistencyTest`, the two tests that were `@Disabled` as the
specification of this fix and are now enabled:
`aCrashBetweenCompactionOutputAndInputDeletionKeepsTheNewestValue` and
`aCrashDuringCompactionDoesNotResurrectADeletedKey`. Both build the crashed
directory by hand, holding every table file from both sides plus the manifest as
it stood before the compaction committed.

A third was added with the fix, `aCrashAfterTheCompactionCommittedKeepsTheNew
StructureAndClearsTheOrphans`, because the two above can be satisfied by the
wrong thing. The directory is byte for byte the same in both cases and only the
manifest differs, so a fix that preferred the newest sequence number would pass
those two and fail this one for the mirror image of the original reason. It also
pins the orphan cleanup, without which a crashed compaction's output accumulates
for the life of the store.

The three were checked against a mutant rather than assumed to bite: commenting
out the commit in `compactionStep` and rerunning the class fails 4 of its 5
tests.

The other two tests in that class pass and are not disabled:

- `aCrashBetweenAFlushAndTheLogResetLosesNothing` puts back the pre-flush log
  after a flush has reset it, which is what a crash between the table rename and
  the log reset leaves. Nothing is lost and nothing is duplicated, because a
  replay in write order is idempotent. This is the ordering in `flush` paying off:
  the table is durable before the log is cleared, so the crash window leaves the
  data in both places rather than in neither.
- `restartsInterleavedWithCompactionMatchTheOracle` runs 6,000 operations with a
  flush every 32 and a clean restart every 250, against a `TreeMap` oracle, so the
  level structure is rebuilt from file names dozens of times over a workload that
  is compacting throughout.

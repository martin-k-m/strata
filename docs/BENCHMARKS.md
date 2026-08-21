# Benchmarks

Every number here came out of a run of the harness in [bench/](../bench) on the
machine described below. The command that produced each table is printed above it.
Nothing is estimated, extrapolated or copied from anywhere else.

## Read this first: the timing tables predate background compaction

These tables were taken with compaction running on the writer's thread. Background
compaction landed later, in `61a7c37`, and the timing tables here have not been
retaken. The byte-accounting tables have, and they still hold exactly.

I re-ran the whole harness to check. What that established:

- **Write amplification and space amplification reproduce byte for byte**, every
  row, on two separate runs. They are counters rather than clocks, so they do not
  care about machine load or about which thread compacts. Trust these.
- **Two timing rows do not reproduce, and one of them is this document's own
  headline.** They are called out in place below. Trust the shape of the timing
  tables, not the digits.

The timing numbers are not being rewritten here, because the originals were real
on an idle machine at the commit they were taken on, and a re-measurement taken
under different load at a different commit is not a correction of them. It is a
second observation, and it is recorded as one.

## Environment

| | |
|---|---|
| CPU | Intel Core Ultra 9 285H, 16 cores, 16 logical processors, 2.9 GHz base |
| RAM | 15.43 GiB |
| Storage | NVMe Timetec 35TT2280GEN4P-2TB, SSD, 1907.7 GB, single disk (`C:`) |
| OS | Windows 11 Pro, build 26200 (10.0.26200) |
| JDK | Temurin 21.0.12+8, OpenJDK 64-Bit Server VM, Eclipse Adoptium |
| Heap | 3952 MiB max (JVM default for this machine) |
| Store directory | `%TEMP%\strata-bench`, on the same NVMe disk |

Workload shape, the same for every scenario: 16-byte keys, 100-byte values,
n = 50,000 unless stated. The store directory is deleted and recreated per
scenario.

### Machine load during the run: not idle

The machine was **not** idle. A benchmark belonging to an unrelated project was
running for most of this session, and I sampled the process table every ten
seconds throughout to record exactly how much. Of 43 samples, 33 had a second JVM
running alongside the harness and 10 had only the harness.

I am reporting this rather than quietly re-running until I got a good number,
because it changes how the tables should be read:

- **The amplification tables are unaffected.** They are byte counters, not timings.
  The write-amplification figures below are byte-for-byte identical to an earlier
  run taken under different load, which is the check that says so.
- **Every latency and throughput row is pessimistic**, by an amount I cannot
  quantify without a clean machine. Treat them as a floor on what the code can do,
  not as its best.
- **The strata and RocksDB rows are still comparable to each other**, because both
  ran in the same session under the same neighbour, back to back on the same disk.

### This is not JMH

I tried JMH and did not use it. The harness is a timed loop with an explicit
warmup phase, and the gap between that and JMH matters:

- **Warmup is explicit but coarse.** Every read scenario runs a warmup pass of
  `probes / 10` operations before the measured pass. Every write scenario fills
  and discards a store one tenth the size first. That is enough to get the write
  path compiled; it is not JMH's steady-state detection.
- **No forking.** One JVM per scenario, so there is no fork-to-fork variance in
  these numbers, only within-process variance. A single unlucky JIT decision would
  not show up as noise, it would show up as the answer.
- **No dead-code elimination guards.** There are no blackholes. Each read
  scenario asserts on its result, which is what keeps the loop alive, but that is a
  weaker guarantee than JMH's.
- **Timer granularity is a real limit on one row.** `System.nanoTime()` costs tens
  of nanoseconds to call, and the memtable read p50 below is 800 ns. That row is
  measured with an instrument within two orders of magnitude of the thing it is
  measuring, so read it as a ceiling on speed rather than a precise latency.

Medians and p99s are reported throughout, never a mean alone. In an LSM tree the
mean is the least interesting statistic, because the whole shape of the thing is
that most operations are cheap and some land on a flush or a compaction.

## Reproducing

No JDK, Gradle or Maven needed on the machine:

```powershell
pwsh bench/bootstrap.ps1          # fetches Temurin 21 and the RocksDB jar
pwsh bench/run.ps1 all 50000      # every strata scenario
pwsh bench/run.ps1 rocksdb 50000  # the RocksDB comparison
```

On Linux or macOS, with a JDK 21 on `PATH`, `bench/run.sh` takes the same
arguments. Through Gradle: `gradle bench`, `gradle bench -Pbench.scenario=write-amp`,
`gradle benchRocks`.

---

## Write throughput

```powershell
pwsh bench/run.ps1 write-seq 50000
pwsh bench/run.ps1 write-rand 50000
```

| Scenario | ops/s | payload | p50 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|---:|
| Sequential keys | 1,717 | 0.19 MiB/s | 515 µs | 1.38 ms | 3.20 ms | 34.8 ms |
| Random keys | 1,608 | 0.18 MiB/s | 514 µs | 1.86 ms | 4.24 ms | 29.4 ms |

Sequential and random are within 7% of each other, which is the first thing worth
noticing. On a B-tree that gap would be large, because random inserts scatter page
writes. An LSM tree turns every write into an append to the log regardless of key,
and the sort happens in memory, so key order barely matters to the write path.
That is the property the whole design exists to buy.

The absolute number, about 1,700 puts per second, is not a statement about this
code. It is a statement about how many times per second this NVMe disk will
acknowledge an fsync. See the fsync table below.

The `max` column is the compaction tax. p50 is 515 µs and the worst put in the run
took 34.8 ms, roughly 68 times longer, because compaction runs on the writer's
thread and the put that triggers it pays for the whole merge.

## Point read latency, split three ways

```powershell
pwsh bench/run.ps1 read-split 50000
```

This split is the point of the document. A point read costs three entirely
different amounts depending on where the key lives, and quoting one "read latency"
figure for an LSM tree hides that.

| Where the key is | ops/s | p50 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|
| Hit, in the memtable | 736,662 | 800 ns | 3.0 µs | 8.3 µs | 48.6 µs |
| Miss, ruled out by bloom filters | 646,282 | 700 ns | 2.1 µs | 30.6 µs | 308 µs |
| Hit, on disk, 1024-block cache | 124,104 | 8.8 µs | 20.3 µs | 59.7 µs | 2.25 ms |
| Hit, on disk, 1-block cache | 91,659 | 9.1 µs | 26.6 µs | 69.9 µs | 1.37 ms |

Three things fall out of this.

**A miss is cheaper than a hit.** 700 ns against 8.8 µs, and it beats the memtable
hit at p50. That is the bloom filters working exactly as intended: a miss touches
no table's data at all, because every filter says "definitely absent" and the read
returns without a single disk read or checksum check. This is the return on the
ten bits per key that every table spends; see [DECISIONS.md](DECISIONS.md).

**The memtable is an order of magnitude faster than disk**, 800 ns against 8.8 µs.
This is why the flush threshold is a tuning knob and not an implementation detail.

**Shrinking the block cache from 1024 blocks to 1 costs only 26%**, 8.8 µs to
9.1 µs at p50. That is smaller than it looks like it should be, and the reason is
that neither figure is a disk seek. The whole 1.5 MB store is in the operating
system's page cache, so the 1-block run is measuring the cost of re-decoding and
re-checksumming a block, not the cost of reaching the platter. On a dataset larger
than RAM the gap would be far wider. This harness does not measure that case, and
I am not going to claim it does.

## Range scans

```powershell
pwsh bench/run.ps1 scan 50000
```

| Scan | Rate | p50 | p99 | p99.9 |
|---|---:|---:|---:|---:|
| Full scan, 50,000 entries | 1,779,042 entries/s | | | |
| Short scan, 100 entries | | 10.5 µs | 68.8 µs | 213 µs |

The full-scan rate is the k-way merge running at streaming speed, about 560 ns per
entry. The short scan is where an LSM range read looks worst: 10.5 µs to return
100 entries is 105 ns each, but the fixed cost of standing up one iterator per
layer and priming the merge heap is paid whether the range holds 100 entries or
50,000.

## Write amplification

```powershell
pwsh bench/run.ps1 write-amp 50000
```

Bytes this store put on disk, over bytes the caller handed it. This is the number
that says whether the level structure is earning its keep. 50,000 puts of scattered
keys, 5,800,000 logical bytes in every row.

| Flush threshold | WAL | Flush | Compaction | Physical | **Write amplification** |
|---|---:|---:|---:|---:|---:|
| 100,000 (1 flush) | 6,650,000 (1.15×) | 6,375,052 (1.10×) | 6,375,052 (1.10×) | 19,400,104 | **3.34×** |
| 5,000 (10 flushes) | 6,650,000 (1.15×) | 6,375,720 (1.10×) | 17,852,016 (3.08×) | 30,877,736 | **5.32×** |
| 1,250 (40 flushes) | 6,650,000 (1.15×) | 6,378,400 (1.10×) | 47,359,620 (8.17×) | 60,388,020 | **10.41×** |

The decomposition is the interesting part, not the total.

**The WAL and the flush terms are constant at 1.15× and 1.10× and cannot go
lower.** Every value is written to the log once before it is ever flushed, and
written again when the memtable spills. That is a floor of about 2.25× that no
compaction strategy can touch: it is the price of the durability guarantee plus
the price of having an on-disk sorted table at all. The 1.15× on the WAL is record
framing, 13 bytes of length, CRC, type and two length fields per 116-byte record.
The 1.10× on flush is the SSTable format overhead.

**Everything above 2.25× is compaction, and it is entirely a function of the flush
threshold.** Halving the memtable does not halve the work; it multiplies it.
Dropping the threshold from 100,000 to 1,250, a factor of 80, took compaction from
1.10× to 8.17×, a factor of 7.4. Smaller memtables mean more, smaller tables, which
means level 0 hits its four-table trigger more often, and each of those merges
rewrites the overlapping tables in level 1 again.

This is the central trade of a leveled LSM stated in bytes: memory spent on the
memtable is disk writes not spent on compaction. The default threshold of 100,000
entries is the 3.34× row.

## Space amplification

```powershell
pwsh bench/run.ps1 space-amp 50000
```

Bytes resident on disk over the bytes of the live keys and values. 50,000 writes
over 12,500 distinct keys, so each key is written four times on average, and
1,450,000 bytes are live at the end. SSTable bytes only; the log is empty after a
flush.

| Flush threshold | As it lies | After `compact()` |
|---|---:|---:|
| 16,666, three flushes, below the level-0 trigger | 1,564,892 (1.08×) | 1,564,892 (1.08×) |
| 5,000, ten flushes, level triggers have run | 3,842,836 (2.65×) | 1,565,040 (1.08×) |

The two rows say different things and the difference is the whole point.

**Below the level-0 trigger, 1.08× is the floor and `compact()` cannot improve
it.** Nothing has been merged yet, but nothing needs to be: the memtable is a
map, so three of every four writes to a key are collapsed before they ever reach
disk. What is left is not stale data, it is format overhead: roughly 8 bytes per
entry of key and value length prefixes, 1.75 bytes per key of sparse index, 1.25
bytes per key of bloom filter and half a byte per key of block header, which
comes to about 11.5 bytes against a 116-byte record, or a little under 10%. The
measured 8% is that, and nothing else.

**Once the level triggers have run, the store sits at 2.65× until something
compacts it.** Ten flushes produce overlapping tables across level 0 and level 1,
and the same key is resident in several of them at once. That is real stale data
and `compact()` reclaims all of it, 3,842,836 bytes down to 1,565,040, landing on
the same 1.08× floor as the first row. So the honest summary is that the floor is
1.08× and the steady state under a live write load is up to 2.65×, not that space
amplification is 1.08× everywhere.

The honest limitation: this harness never catches the store in a state with much
garbage in it, so it does not establish an upper bound on space amplification, only
that the steady state is tight. A workload that overwrote a large working set
between compactions would look worse and this does not measure it.

## Read latency during a compaction

```powershell
pwsh bench/run.ps1 compaction-tail 50000
```

Point reads at rest, against the same reads while a writer thread is putting
continuously and therefore flushing and compacting throughout.

| | ops/s | p50 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|
| At rest | 117,302 | 9.4 µs | 21.0 µs | 59.8 µs | 1.45 ms |
| During compaction | 98,854 | 10.4 µs | 31.7 µs | 84.1 µs | 1.79 ms |

There is a p99 spike and it is 1.5×, 21.0 µs to 31.7 µs. Throughput drops 16%.

> **Does not reproduce: the direction is no longer stable.** One re-run of
> `bench/run.ps1 compaction-tail 50000` gave 89,030 ops/s at rest against 67,524
> during compaction, p99 65.3 µs against 123.6 µs, which is a larger spike than the
> table claims. Three further re-runs in another session had the during-compaction
> arm *faster* than at rest in one case and slower in two, with no consistent
> margin. Moving the merge off the writer's thread appears to have turned this into
> a measurement of whatever else the machine is doing. There is no reproducible 16%
> drop or 1.5× spike any more, in either direction, and I am not going to quote a
> number for an effect I cannot re-observe.

What is notable is how small that is. A reader never blocks on the writer, because
reads take no lock at all: they snapshot the level structure, take a reference on
each table and read. The degradation here is contention for disk bandwidth and for
the block cache, not a stall. This is the reference counting from
[BUGS.md](BUGS.md) paying off in a way beyond mere correctness. Before that fix,
this scenario did not produce a slower read, it produced an exception.

## What the fsync costs

```powershell
pwsh bench/run.ps1 fsync 50000
```

The same put loop with the log fsynced on every write, and with the fsync removed.
The second configuration is **not durable** and exists only to price the first.

| | ops/s | p50 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|
| fsync per write (durable) | 1,620 | 535 µs | 1.65 ms | 4.01 ms | 40.2 ms |
| no fsync (NOT durable) | 403,804 | 2.2 µs | 5.0 µs | 35.3 µs | 241 µs |

**The fsync is 249× the throughput and 99.6% of the latency of a `put`.** Of the
535 µs a durable put takes at p50, 2.2 µs is strata and 533 µs is waiting for the
disk to say the bytes are safe.

> **Does not reproduce: the 249× does not survive background compaction.** Re-running
> `bench/run.ps1 fsync 50000` on the same machine gives 781 ops/s durable against
> 54,960 unsynced, which is **70×**, not 249×. A separate re-run in another session
> put the unsynced path at roughly 200,000 ops/s, so that arm is heavily
> load-sensitive and neither re-measurement gets near 403,804. The durable arm is
> stable; the unsynced arm is what moved. The likely mechanism is
> `awaitCompactionHeadroom()` in `StrataStore`, which gates every put on compactor
> headroom and can only bite when the fsync is not already the bottleneck, so it
> would cost the unsynced arm and be invisible in the durable one. That makes this
> a candidate performance regression from `61a7c37` rather than only doc drift, and
> it is not yet bisected. **The conclusion the paragraph draws still holds** at any
> of these multipliers: the durable p50 is over 99% fsync either way.

This is the single most important number in this document, because it says the
write throughput of this store is not a property of this code. Every optimisation
in the write path is competing for the remaining 0.4%. The way to move this number
is group commit, batching writes and syncing once for all of them, which trades a
precisely stated guarantee for a windowed one. That trade is written up in
[DECISIONS.md](DECISIONS.md).

## Against RocksDB

```powershell
pwsh bench/run.ps1 rocksdb 50000
```

RocksDB 9.7.3 via `org.rocksdb:rocksdbjni`, same machine, same disk, same session,
same workload: 16-byte keys, 100-byte values, n = 50,000. Configured to match what
strata does rather than to be fast: one column family, no block compression, and
`WriteOptions.setSync(true)` for the durable run.

| | strata | RocksDB | |
|---|---:|---:|---|
| put, durable (fsync per write) | 1,620 ops/s | 1,595 ops/s | parity |
| put, no fsync | 403,804 ops/s | 159,628 ops/s | strata 2.5× faster |
| get, hit | 124,104 ops/s | 308,562 ops/s | **RocksDB 2.5× faster** |
| get, miss | 646,282 ops/s | 263,959 ops/s | strata 2.4× faster |
| scan, full | 1,779,042 entries/s | 3,853,297 entries/s | **RocksDB 2.2× faster** |

I expected to lose everywhere and did not, so the rows where strata comes out ahead
need explaining rather than celebrating.

**Durable writes are at parity, 1,620 against 1,595, and that is the least
surprising row here.** Both engines are doing the same thing: appending to a log
and waiting for the disk. Neither one's code is in the critical path. This row is a
measurement of the NVMe drive, taken twice.

**strata wins the unsynced write because it does less.** RocksDB's write path
carries sequence numbers for MVCC, write batches, column-family routing and
snapshot bookkeeping. strata's is an append to a `ConcurrentSkipListMap`. That is
not a better implementation, it is a smaller feature set, and the missing features
are ones a real database needs.

**strata wins the miss because the dataset is tiny.** 50,000 keys is 1.5 MB, which
lives in one or two tables at one or two levels, so a miss checks a couple of bloom
filters and stops. RocksDB is paying its fixed per-lookup costs across more
machinery. Scale the dataset until strata has five or six levels and this row
inverts. **This is the row I would trust least**, and it is a fair criticism of the
whole comparison that 50,000 keys is too small to stress either engine.

**RocksDB wins the two rows that measure real work**, the on-disk hit and the full
scan, both by more than 2×. Those are the ones to take seriously. Its block format,
its index layout and its iterator are years of tuning that strata has not done, and
the gap would widen with a dataset large enough to force real disk reads.

The comparison is honest but small. What it establishes is that strata is in the
right order of magnitude and that its durable write path is bounded by the same
physics as a production engine's. It does not establish that it would hold up at a
scale where any of this mattered.

## What I could not measure

- **A clean machine.** Recorded above: another JVM benchmark was running for 33 of
  43 samples. Every timing row is a floor, not a best case.
- **JMH.** The jars fetch and the harness works, but this is a timed loop and not a
  JMH benchmark, with the specific gaps listed at the top. The numbers are honest
  about their own precision rather than dressed up as more than they are.
- **A dataset larger than RAM.** Every scenario here fits in the page cache, so no
  row measures an actual disk seek on the read path. The block-cache comparison and
  the RocksDB read rows both understate how much this matters, and a working set
  above 15 GiB is the obvious next thing to measure.
- **An upper bound on space amplification.** The compacted floor is 1.08× and the
  measured steady state under a live write load is 2.65×; the worst case is not
  established, because the harness never catches the store with a large
  overwritten working set sitting uncompacted.

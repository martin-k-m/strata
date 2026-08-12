# Contributing to strata

Thanks for taking a look. strata is written to be *read*: a small,
dependency-free LSM-tree core where every durability and ordering decision is
visible rather than buried in a framework. A change should keep that true.

## Setup

You need a JDK (the CI uses Java 21) and Gradle (the wrapper is fine).

```bash
git clone https://github.com/martin-k-m/strata
cd strata
gradle test --no-daemon --console=plain
```

## Ground rules

- **Zero runtime dependencies.** The core is the standard library only. The
  point is that a reader can follow the write path, the flush, and compaction
  without chasing into a third-party jar.
- **Durability decisions are explicit.** The write-ahead log, the flush from the
  memtable to an SSTable, the CRC32 block checksums, and crash recovery are the
  reason this project exists. A change to any of them states what ordering or
  fsync guarantee it relies on, in a comment and in a test.
- **Ordering holds across the layers.** A range scan merges the mutable memtable
  over the immutable SSTables and must stay sorted and deduplicated. Leveled
  compaction preserves that. Tests pin it.
- **A behaviour change comes with a test.** Prefer a test that would fail
  against the old behaviour and pass against the new.

## Before you open a pull request

```bash
gradle test --no-daemon --console=plain
```

Keep pull requests focused on one thing. A crash-recovery or compaction change
is easier to review with a test that demonstrates the exact scenario it fixes.

## Reporting bugs

Open an issue with the sequence of operations that reproduces the problem
(the CLI is convenient for this), what you expected to read back, and what you
got. A failing JUnit case is the most useful report there is.

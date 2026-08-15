# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims
to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Property and crash-fuzz tests (`StrataDurabilityPropertyTest`) that quantify
  the durability guarantees over the whole failure space: recovery yields a valid
  write-history prefix after truncation at *every* byte offset and after *every*
  single-byte corruption of the log, scan order is verified unsigned-lexicographic
  across the high byte range, empty values round-trip distinctly from deletes, and
  recovery stays correct across many mid-stream restarts. JUnit only, no new
  dependency.
- Contribution, security, and changelog documentation, and status badges in the
  README.
- Concurrency tests (`StrataConcurrencyPropertyTest`) covering the guarantee the
  durability suite does not touch, since every test in it is single-threaded:
  readers running against a writer that is flushing and compacting throughout. A
  key acknowledged before a read must be returned by that read with its own
  value, a scan must complete in order alongside a writer, and a rewrite or a
  delete must not be undone by an older table resurfacing. They found the bug
  below.

### Fixed
- **A compaction could break a concurrent read.** `get` and `scan` snapshot the
  level structure and then read through the tables they found, while compaction
  published the new structure and immediately closed and deleted the tables it
  had consumed. A reader already inside one got `UncheckedIOException: sstable
  read failed` for a key that was present the entire time, which contradicted the
  documented guarantee that reads run alongside a writer without locking.

  Tables are now reference counted. A reader takes a reference on each table it is
  about to read, compaction drops the store's reference instead of closing the
  file, and the last one out closes and deletes it. A reader that arrives just
  after a table is retired retakes its snapshot and finds the same keys in the
  tables the compaction wrote, which terminates because compaction publishes
  before it retires.

  `scan` holds its references until the returned stream is closed, so closing it
  is now required rather than tidy: an unclosed scan keeps a compacted file on
  disk. Every call site in this repository already used try-with-resources. This
  also removes the caveat on `Store.scan` that a scan should not run alongside a
  compaction, which had contradicted the store's own concurrency claim.

## [0.1.0]

### Added
- Durable write path with a write-ahead log and crash recovery.
- SSTable flush from the in-memory memtable, the read path across layers, and
  compaction.
- Leveled compaction, replacing the initial full-merge strategy.
- Ordered range scans that merge the mutable and immutable layers.
- Block-level CRC32 checksums and an LRU block cache for SSTables.
- A command-line interface over the store.
- MIT license and continuous integration.

[Unreleased]: https://github.com/martin-k-m/strata/compare/main...HEAD
[0.1.0]: https://github.com/martin-k-m/strata/releases/tag/v0.1.0

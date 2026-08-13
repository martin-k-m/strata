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

# Security Policy

## Supported versions

Fixes land on the latest released version; there are no long-lived maintenance
branches.

## Reporting a vulnerability

Please report suspected vulnerabilities privately rather than in a public
issue. Use GitHub's [private vulnerability reporting](https://github.com/martin-k-m/strata/security/advisories/new)
for this repository, or email martinkmuskov@gmail.com.

Include the sequence of operations and, where relevant, the on-disk state that
reproduces the problem. You can expect an acknowledgement within a few days.

## Scope

strata is an embedded storage engine: it reads and writes its own data files on
local disk and makes no network requests. There is no runtime dependency tree,
so the supply-chain surface is the JDK and the build toolchain alone.

The classes of issue most worth reporting are ones that touch the durability or
integrity promise: a crafted or truncated log or SSTable that causes recovery
to read back the wrong data rather than fail cleanly, a CRC32 check that can be
bypassed, or an input that drives unbounded memory. strata trusts the data
files it wrote; point it only at directories you control, as with any embedded
database.

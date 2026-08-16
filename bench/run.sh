#!/usr/bin/env bash
# The Linux and macOS equivalent of run.ps1. The numbers recorded in
# docs/BENCHMARKS.md were taken on Windows with run.ps1; this exists so the same
# harness runs elsewhere, not so the two are compared to each other.
#
#   bench/run.sh                    # every scenario, n = 50000
#   bench/run.sh write-amp 50000
#   bench/run.sh rocksdb 50000      # needs bench/.toolchain/rocksdbjni-9.7.3.jar
#
# It uses whatever java and javac are on PATH, which must be 21 or newer. There is
# no bootstrap here: on Linux and macOS a JDK is one package-manager command away,
# which is not true of the Windows machine bootstrap.ps1 was written for. For the
# RocksDB comparison, fetch the jar once:
#
#   mkdir -p bench/.toolchain && curl -L -o bench/.toolchain/rocksdbjni-9.7.3.jar \
#     https://repo1.maven.org/maven2/org/rocksdb/rocksdbjni/9.7.3/rocksdbjni-9.7.3.jar

set -euo pipefail

scenario="${1:-all}"
n="${2:-50000}"
data_dir="${3:-${TMPDIR:-/tmp}/strata-bench}"

bench_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(dirname "$bench_dir")"
tool="$bench_dir/.toolchain"
out="$tool/classes"
rocks_jar="$tool/rocksdbjni-9.7.3.jar"

command -v javac >/dev/null || { echo "no javac on PATH; JDK 21 or newer is required" >&2; exit 1; }
mkdir -p "$out/main" "$out/bench" "$data_dir"

echo "compiling strata"
find "$repo/src/main/java" -name '*.java' -print0 | xargs -0 javac -d "$out/main"

if [ "$scenario" = "rocksdb" ]; then
    [ -f "$rocks_jar" ] || { echo "no $rocks_jar; see the header of this script" >&2; exit 1; }
    echo "compiling the RocksDB comparison"
    javac -cp "$rocks_jar" -d "$out/bench" \
        "$bench_dir/java/dev/martinkm/strata/bench/RocksComparison.java"
    exec java -cp "$out/bench:$rocks_jar" \
        dev.martinkm.strata.bench.RocksComparison "$n" "$data_dir/rocksdb"
fi

echo "compiling the harness"
javac -cp "$out/main" -d "$out/bench" "$bench_dir/java/dev/martinkm/strata/bench/Bench.java"
exec java -cp "$out/main:$out/bench" dev.martinkm.strata.bench.Bench "$scenario" "$n" "$data_dir"

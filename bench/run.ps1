# Compiles strata and the benchmark harness with the bootstrapped JDK and runs a
# scenario. Every number in docs/BENCHMARKS.md came out of this script.
#
#   pwsh bench/bootstrap.ps1          # once, fetches the JDK and the RocksDB jar
#   pwsh bench/run.ps1 all 50000      # every strata scenario
#   pwsh bench/run.ps1 write-amp 50000
#   pwsh bench/run.ps1 rocksdb 50000  # the RocksDB comparison
#
# Scenarios: write-seq write-rand read-split scan write-amp space-amp
#            compaction-tail fsync all rocksdb
#
# The store directories are written under $env:TEMP\strata-bench by default, on
# whatever disk that resolves to. Pass a third argument to put them elsewhere;
# the disk matters more than anything else in these numbers, because every put
# fsyncs.

param(
    [string]$Scenario = "all",
    [int]$N = 50000,
    [string]$DataDir = (Join-Path $env:TEMP "strata-bench")
)

$ErrorActionPreference = "Stop"
$bench = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $bench
$tool = Join-Path $bench ".toolchain"
$jdk = Join-Path $tool "jdk"
$rocksJar = Join-Path $tool "rocksdbjni-9.7.3.jar"

if (-not (Test-Path (Join-Path $jdk "bin\javac.exe"))) {
    throw "no JDK in $jdk. Run bench/bootstrap.ps1 first."
}

$out = Join-Path $tool "classes"
$mainOut = Join-Path $out "main"
$benchOut = Join-Path $out "bench"
New-Item -ItemType Directory -Force $mainOut, $benchOut, $DataDir | Out-Null

$javac = Join-Path $jdk "bin\javac.exe"
$java = Join-Path $jdk "bin\java.exe"

Write-Host "compiling strata"
& $javac -d $mainOut (Get-ChildItem -Recurse (Join-Path $repo "src\main\java") -Filter *.java).FullName
if ($LASTEXITCODE -ne 0) { throw "strata did not compile" }

if ($Scenario -eq "rocksdb") {
    if (-not (Test-Path $rocksJar)) { throw "no $rocksJar. Run bench/bootstrap.ps1 first." }
    Write-Host "compiling the RocksDB comparison"
    & $javac -cp $rocksJar -d $benchOut `
        (Join-Path $bench "java\dev\martinkm\strata\bench\RocksComparison.java")
    if ($LASTEXITCODE -ne 0) { throw "the RocksDB comparison did not compile" }
    & $java -cp "$benchOut;$rocksJar" dev.martinkm.strata.bench.RocksComparison $N `
        (Join-Path $DataDir "rocksdb")
    exit $LASTEXITCODE
}

Write-Host "compiling the harness"
& $javac -cp $mainOut -d $benchOut `
    (Join-Path $bench "java\dev\martinkm\strata\bench\Bench.java")
if ($LASTEXITCODE -ne 0) { throw "the harness did not compile" }

& $java -cp "$mainOut;$benchOut" dev.martinkm.strata.bench.Bench $Scenario $N $DataDir
exit $LASTEXITCODE

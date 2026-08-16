# Fetches everything the benchmarks need into bench/.toolchain, so they can be run
# on a machine with no JDK, no Gradle and no Maven installed. Everything lands
# under bench/.toolchain, which is gitignored; delete that directory to start over.
#
#   pwsh bench/bootstrap.ps1
#
# Downloads, all from official sources over HTTPS:
#   Temurin JDK 21 (api.adoptium.net)
#   rocksdbjni 9.7.3 (Maven Central), for the comparison run
#
# The RocksDB jar is ~70 MB because it carries a native library for every
# platform, including librocksdbjni-win64.dll, which is why the comparison needs
# no toolchain of its own.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$tool = Join-Path $root ".toolchain"
New-Item -ItemType Directory -Force $tool | Out-Null

$jdkHome = Join-Path $tool "jdk"
if (-not (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) {
    Write-Host "downloading Temurin JDK 21"
    $zip = Join-Path $tool "jdk.zip"
    $url = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    $staging = Join-Path $tool "jdk-staging"
    Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
    Expand-Archive -Path $zip -DestinationPath $staging -Force
    # The archive holds a single versioned directory; flatten it to bench/.toolchain/jdk.
    $inner = (Get-ChildItem $staging -Directory | Select-Object -First 1).FullName
    Move-Item $inner $jdkHome
    Remove-Item -Recurse -Force $staging
    Remove-Item -Force $zip
}
& (Join-Path $jdkHome "bin\java.exe") -version

$rocks = Join-Path $tool "rocksdbjni-9.7.3.jar"
if (-not (Test-Path $rocks)) {
    Write-Host "downloading rocksdbjni 9.7.3"
    Invoke-WebRequest -UseBasicParsing -OutFile $rocks `
        -Uri "https://repo1.maven.org/maven2/org/rocksdb/rocksdbjni/9.7.3/rocksdbjni-9.7.3.jar"
}

Write-Host "toolchain ready in $tool"

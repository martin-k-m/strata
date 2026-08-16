plugins {
    `java-library`
    application
}

group = "dev.martinkm"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// The command-line front end: gradle run --args="get mydir foo".
application {
    mainClass = "dev.martinkm.strata.Cli"
}

repositories {
    mavenCentral()
}

// The benchmark harness lives outside src/main on purpose. It is not part of the
// library, and it is the only thing in the repository that depends on anything
// external, so keeping it in its own source set is what lets the library keep its
// zero-runtime-dependency claim while the RocksDB comparison still builds.
sourceSets {
    create("bench") {
        java.srcDir("bench/java")
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Benchmark only. The jar carries a native library per platform, including
    // librocksdbjni-win64.dll, so the comparison needs no toolchain of its own.
    "benchImplementation"("org.rocksdb:rocksdbjni:9.7.3")
}

// The fast suite: everything not tagged "slow". This is what runs on every push.
tasks.test {
    useJUnitPlatform {
        excludeTags("slow")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// The slow suite: crash fuzzing over every truncation point and every byte flip,
// the concurrency properties, and the oracle runs that build several levels. These
// are minutes rather than seconds, so they run nightly rather than on every push.
// See .github/workflows/nightly.yml.
tasks.register<Test>("slowTest") {
    group = "verification"
    description = "Runs the slow crash, concurrency and model suites."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("slow")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// gradle check runs the fast suite only; the slow one is the nightly job's.
tasks.named("check") {
    dependsOn(tasks.test)
}

// Runs the measurement harness behind docs/BENCHMARKS.md:
//   gradle bench                                       every scenario, n = 50000
//   gradle bench -Pbench.scenario=write-amp
//   gradle bench -Pbench.scenario=read-split -Pbench.n=200000
//
// Scenarios: write-seq write-rand read-split scan write-amp space-amp
//            compaction-tail fsync all
//
// Every put fsyncs, so a large n is minutes rather than seconds. The numbers
// recorded in docs/BENCHMARKS.md were taken on a named machine with nothing else
// running; a number from a busy machine is a number about the busy machine.
tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Runs the strata benchmark harness."
    mainClass = "dev.martinkm.strata.bench.Bench"
    classpath = sourceSets["bench"].runtimeClasspath
    args = listOf(
        (project.findProperty("bench.scenario") as String?) ?: "all",
        (project.findProperty("bench.n") as String?) ?: "50000",
    )
}

// The RocksDB comparison, same machine and same workload: gradle benchRocks.
tasks.register<JavaExec>("benchRocks") {
    group = "verification"
    description = "Runs the same workload against RocksDB, for comparison."
    mainClass = "dev.martinkm.strata.bench.RocksComparison"
    classpath = sourceSets["bench"].runtimeClasspath
    args = listOf((project.findProperty("bench.n") as String?) ?: "50000")
}

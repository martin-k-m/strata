plugins {
    `java-library`
}

group = "dev.martinkm"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Runs the throughput harness: gradle bench, or -Pbench.n=200000 to size it.
tasks.register<JavaExec>("bench") {
    group = "application"
    description = "Runs the strata throughput benchmark."
    mainClass = "dev.martinkm.strata.Benchmark"
    classpath = sourceSets["main"].runtimeClasspath
    (project.findProperty("bench.n") as String?)?.let { args = listOf(it) }
}

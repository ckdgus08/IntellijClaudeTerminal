plugins {
    id("org.jetbrains.intellij") version "1.17.4"
    kotlin("jvm") version "1.9.25"
}

group = "com.claudetabs"
version = "1.0.14"

repositories {
    mavenCentral()
    // Remote Robot (UI test library) is hosted at JetBrains' public Maven repo.
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

// Target IntelliJ Platform 2024.3 for wide compatibility.
// Tested on Rider 2026.1. Build number 243 = 2024.3; higher-numbered builds stay compatible
// since untilBuild is left empty (see patchPluginXml).
intellij {
    version.set("2024.3")
    type.set("IC")
    plugins.set(listOf("terminal"))
}

dependencies {
    // JUnit 4 — IntelliJ platform brings its own version, but declaring it here makes the
    // intent clear and lets plain unit tests run without needing the full platform harness.
    testImplementation("junit:junit:4.13.2")

    // Remote Robot — spins up a real IDE and drives the UI over RMI (Layer 3b e2e tests).
    testImplementation("com.intellij.remoterobot:remote-robot:0.11.23")
    testImplementation("com.intellij.remoterobot:remote-fixtures:0.11.23")
}

// IntelliJ Platform 2024.3 requires JVM target 17. Pin Java + Kotlin to 17 so the build
// works regardless of the local JDK (matters when the only available JDK on the build
// machine is JDK 21+, which would otherwise default `compileJava` to 21 and fail the
// gradle-intellij-plugin's targetCompatibility verifier).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks {
    patchPluginXml {
        sinceBuild.set("243")
        // Empty untilBuild = forward-compatible with future IntelliJ versions.
        // plugin.xml declares the same via <idea-version since-build="243"/>.
        untilBuild.set("")
    }

    buildSearchableOptions {
        enabled = false
    }

    // No Java sources in this project (Kotlin only) — instrumentCode has nothing to
    // process, and on IntelliJ Platform 2024.3+ with gradle-intellij-plugin 1.x it can
    // fail looking for a non-existent `<JDK>\Packages` directory. Skip it.
    instrumentCode {
        enabled = false
    }
    instrumentTestCode {
        enabled = false
    }

    compileKotlin {
        kotlinOptions.jvmTarget = "17"
    }

    compileTestKotlin {
        kotlinOptions.jvmTarget = "17"
    }

    // Shared test logging — print test names + pass/fail + summary so you don't
    // have to go hunt through build/reports/tests/... after every run.
    val applyTestLogging: Test.() -> Unit = {
        val taskName = name
        // Always run — Gradle's up-to-date check otherwise skips the task when nothing
        // changed, which means no test summary prints. We want feedback every time.
        outputs.upToDateWhen { false }
        testLogging {
            events(
                org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
                org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
                org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED,
            )
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
        // Always print a summary at the end, even when nothing ran (UP-TO-DATE case).
        afterSuite(KotlinClosure2<TestDescriptor, TestResult, Void>({ desc, result ->
            if (desc.parent == null) { // root suite
                val total = result.testCount
                val passed = result.successfulTestCount
                val failed = result.failedTestCount
                val skipped = result.skippedTestCount
                val duration = (result.endTime - result.startTime) / 1000.0
                println("")
                println("── Test summary ($taskName) ──")
                println("  $total total | $passed passed | $failed failed | $skipped skipped | ${"%.2f".format(duration)}s")
                if (failed > 0) println("  Full report: build/reports/tests/$taskName/index.html")
                println("")
            }
            null
        }))
    }

    test {
        useJUnit()
        // Keep Layer 3b (Remote Robot UI tests) out of the default test run — they need a
        // running IDE and are slow. Run them via `./gradlew uiTest` instead.
        exclude("**/ui/**")
        applyTestLogging()
    }

    // Dedicated task for Remote Robot UI tests. Launches the IDE in a sandbox via runIdeForUiTests
    // (provided by org.jetbrains.intellij plugin) on a well-known port, then runs the `ui/` tests.
    register<Test>("uiTest") {
        description = "Run Remote Robot UI tests. Requires runIdeForUiTests to be running."
        group = "verification"
        useJUnit()
        include("**/ui/**")
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        shouldRunAfter("test")
        applyTestLogging()
    }

    runIdeForUiTests {
        systemProperty("robot-server.port", "8082")
        systemProperty("ide.mac.message.dialogs.as.sheets", "false")
        systemProperty("jb.privacy.policy.text", "<!--999.999-->")
        systemProperty("jb.consents.confirmation.enabled", "false")
    }
}

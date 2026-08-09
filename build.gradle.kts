import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.intellij.platform") version "2.18.1"
    kotlin("jvm") version "2.3.21"
}

group = "io.github.ckdgus08"
version = "1.1.1"

repositories {
    mavenCentral()
    // Remote Robot (UI test library) is hosted at JetBrains' public Maven repo.
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")

    intellijPlatform {
        defaultRepositories()
        releases()
        snapshots()
    }
}

// IntelliJ Platform 2026.1 minimum. The plugin uses createNewSession(...) for terminal
// creation, which replaces the deprecated createShellWidget(...) — older builds don't
// have the modernised API. Per JetBrains docs, 2025.3+ unified the Community/Ultimate
// helper into intellijIdea() (intellijIdeaCommunity is legacy-only).
dependencies {
    intellijPlatform {
        intellijIdea("2026.1.3")
        bundledPlugin("org.jetbrains.plugins.terminal")

        // `verifyPlugin` needs the standalone IntelliJ Plugin Verifier CLI.
        pluginVerifier()
    }

    // JUnit 4 — IntelliJ platform brings its own version, but declaring it here makes the
    // intent clear and lets plain unit tests run without needing the full platform harness.
    testImplementation("junit:junit:4.13.2")

    // Remote Robot — spins up a real IDE and drives the UI over RMI (Layer 3b e2e tests).
    testImplementation("com.intellij.remoterobot:remote-robot:0.11.23")
    testImplementation("com.intellij.remoterobot:remote-fixtures:0.11.23")
}

// IntelliJ Platform 2026.1 runs on JDK 21 bytecode. Pin the compile JDK via a toolchain so
// the build is identical regardless of the JDK on PATH (developer machines now commonly
// carry 24/25/26, which the Kotlin compiler and the platform verifier both reject).
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            // Forward-compatible with future IntelliJ versions. Setting the property to
            // a provider that resolves to null tells IPGP 2.x to omit the <until-build>
            // attribute entirely (rather than emitting an empty value, which fails the
            // Marketplace plugin descriptor validator with "does not match the multi-part
            // build number format").
            untilBuild = provider { null }
        }
    }
    instrumentCode = false

    // `./gradlew verifyPlugin` checks the built plugin against the IDE builds it claims to
    // support. Pinned to the 2026.1 line the plugin is compiled against; `recommended()`
    // would also drag in Rider/older releases the descriptor's since-build already excludes.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1.3")
        }
    }
}

tasks {
    // Skip searchable-options indexing — the plugin has no settings UI worth indexing
    // and the task spins up a full IDE which slows builds significantly.
    named("buildSearchableOptions") {
        enabled = false
    }

    compileKotlin {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    compileTestKotlin {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
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

    // Dedicated task for Remote Robot UI tests. Run via `./gradlew uiTest` after spinning
    // up an IDE sandbox separately (or use the platform 2.x runIdeForUiTests task).
    register<Test>("uiTest") {
        description = "Run Remote Robot UI tests. Requires an IDE sandbox to be running."
        group = "verification"
        useJUnit()
        include("**/ui/**")
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        shouldRunAfter("test")
        applyTestLogging()
    }
}

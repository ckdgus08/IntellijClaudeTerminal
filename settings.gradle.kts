// Lets Gradle download the JDK the build asks for (see the `java.toolchain` block in
// build.gradle.kts) instead of failing on whatever JDK happens to be on PATH.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "intellij-claude-terminal"

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // NOTE: kotlin-stdlib ships in the zip (transitively via kotlinx-serialization,
    // and the IntelliJ Platform Gradle Plugin bundles it regardless of
    // kotlin.stdlib.default.dependency=false). Harmless - plugin classloaders are
    // self-first - just adds ~1.9MB.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Pure platform APIs only -> works across all JetBrains IDEs.
        // No bundled plugin dependencies on purpose (works in PyCharm/WebStorm/GoLand/...).
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.lihanghang.whatisthis"
        name = "WhatIsThis"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    publishing {
        token = providers.gradleProperty("jetbrainsToken")
            .orElse(providers.environmentVariable("JETBRAINS_TOKEN"))
            .orElse(providers.provider { "" })
    }
}

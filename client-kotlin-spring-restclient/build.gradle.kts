plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

// Version can be overridden via -Pversion=X (used for snapshots)
// Otherwise, calculated from OpenAPI spec version + patch version
val calculatedVersion: String = run {
    val specFile = file("$rootDir/../epistola-api.yaml")
    val apiVersion: String = if (specFile.exists()) {
        val versionRegex = Regex("""^\s*version:\s*["']?(\d+\.\d+)\.\d+["']?\s*$""", RegexOption.MULTILINE)
        val match = versionRegex.find(specFile.readText())
        match?.groupValues?.get(1) ?: "0.0"
    } else {
        "0.0"
    }
    val patchVersion: String = findProperty("patchVersion")?.toString() ?: "0"
    "$apiVersion.$patchVersion"
}

group = "app.epistola.contract"
version = findProperty("version")?.toString()?.takeIf { it != "unspecified" } ?: calculatedVersion
description = "Epistola API Client for Kotlin using Spring RestClient"

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict")
        }
    }
}

dependencies {
    kover(project(":client"))
}

kover {
    reports {
        total {
            xml {
                onCheck = false
            }
            html {
                onCheck = false
            }
        }
    }
}

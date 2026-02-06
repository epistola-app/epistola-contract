plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

// Read API version from OpenAPI spec (e.g., "1.0.0" -> "1.0")
val specFile = file("$rootDir/../epistola-api.yaml")
val apiVersion: String = if (specFile.exists()) {
    val versionRegex = Regex("""^\s*version:\s*["']?(\d+\.\d+)\.\d+["']?\s*$""", RegexOption.MULTILINE)
    val match = versionRegex.find(specFile.readText())
    match?.groupValues?.get(1) ?: "0.0"
} else {
    "0.0"
}

// Patch version: defaults to 0 for local builds, CI passes actual value via -PpatchVersion=X
val patchVersion: String = findProperty("patchVersion")?.toString() ?: "0"

group = "app.epistola.contract"
version = "$apiVersion.$patchVersion"
description = "Epistola API Server Interfaces for Kotlin/Spring"

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
    kover(project(":server"))
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

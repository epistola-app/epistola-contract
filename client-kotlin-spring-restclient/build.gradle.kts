plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

// Sets group + version from the OpenAPI spec (shared across builds)
apply(from = "$rootDir/../gradle/contract-version.gradle.kts")

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

// Captured outside subprojects {} — typed catalog accessors don't resolve inside that closure
val javaToolchainVersion = libs.versions.java.toolchain.get().toInt()

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
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

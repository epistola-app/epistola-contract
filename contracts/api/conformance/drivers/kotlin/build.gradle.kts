// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.toolchain.get().toInt()))
    }
}

// The harness runs `java -cp @classpath.txt` once per scenario, so it needs no Gradle daemon per
// scenario — eight scenarios would otherwise mean eight Gradle invocations.
val conformanceDriverClasspath by tasks.registering {
    description = "Compiles the driver and writes its runtime classpath for the conformance harness"
    group = "verification"

    val runtimeClasspath = sourceSets["main"].runtimeClasspath
    val outFile = layout.buildDirectory.file("conformance/classpath.txt")
    dependsOn(tasks.named("classes"))
    outputs.file(outFile)

    doLast {
        val file = outFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(runtimeClasspath.files.joinToString(File.pathSeparator) { it.absolutePath })
    }
}

dependencies {
    // Substituted from source by the includeBuild in ../settings.gradle.kts. The client's Spring
    // and Jackson dependencies are `api`, so they come along, which is exactly what a consumer gets.
    implementation("app.epistola.contract:client-kotlin-spring-restclient")
}

kotlin {
    compilerOptions {
        // protocol-java's package is @NullMarked; without this Kotlin reads those signatures as
        // platform types. The clients set the same flag, and the driver compiles against them.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjspecify-annotations=strict")
    }
}

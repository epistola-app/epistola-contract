// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

plugins {
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
    // inputs.files, not just dependsOn(classes): the classpath carries the client, and without
    // declaring it Gradle never builds the included build's jar for this task. The harness then
    // runs against whatever jar happened to be lying in the client's build directory — which it
    // did, silently testing a stale client after a regeneration.
    inputs.files(runtimeClasspath)
    dependsOn(tasks.named("classes"))
    outputs.file(outFile)

    doLast {
        val file = outFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(runtimeClasspath.files.joinToString(File.pathSeparator) { it.absolutePath })
    }
}

dependencies {
    // Substituted from source by the includeBuild in ../settings.gradle.kts.
    implementation("app.epistola.contract:client-jakarta")

    // The client bundles no JAX-RS or MicroProfile implementation on purpose — a container supplies
    // them. The driver is not a container, so it brings the same RESTEasy stack the client's own
    // tests run on. Declaring these here rather than getting them transitively is the point: it is
    // what a real Jakarta EE consumer's runtime provides.
    runtimeOnly(libs.resteasy.client)
    runtimeOnly(libs.resteasy.json.binding.provider)
    runtimeOnly(libs.resteasy.microprofile.rest.client)
    runtimeOnly(libs.yasson)
    runtimeOnly(libs.smallrye.config)

    implementation(libs.jakarta.ws.rs.api)
    implementation(libs.jakarta.json.api)
    implementation(libs.jakarta.json.bind.api)
    implementation(libs.microprofile.rest.client.api)
    implementation(libs.microprofile.config.api)
    runtimeOnly(libs.parsson)
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

// Shared version calculation for the contract-derived Gradle builds
// (the Kotlin API client and server stub projects).
//
// Version can be overridden via -Pversion=X (used for snapshots).
// Otherwise it is calculated from the OpenAPI spec's major.minor plus the
// -PpatchVersion property (default 0).
//
// Applied from each build's root build.gradle.kts via
//   apply(from = "$rootDir/../../build-logic/contract-version.gradle.kts")

val calculatedVersion: String = run {
    val specFile = rootDir.resolve("../../openapi.yaml")
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

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

// Found by walking up from the build's root rather than by a fixed relative path: the builds that
// apply this sit at different depths under contracts/, and a hardcoded "../../" silently resolves
// to a non-existent file (and a 0.0 version) for any module at another level.
val specFile: java.io.File? = generateSequence(rootDir) { it.parentFile }
    .map { it.resolve("contracts/api/openapi.yaml") }
    .firstOrNull { it.exists() }

val calculatedVersion: String = run {
    val apiVersion: String = if (specFile != null) {
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

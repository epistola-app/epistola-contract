// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

rootProject.name = "server-kotlin-springboot4"

// The generated Spring interfaces expose catalog Kotlin types in their public
// signatures. Use the source build while developing and publishing this
// repository; the published POM still records the normal Maven dependency.
includeBuild("../../../catalog")

// The shared wire-protocol logic: this module parses the User-Agent the clients format, and builds
// the problem type URIs they read slugs out of.
includeBuild("../../protocol-java")

// All three builds in this repo share the root version catalog.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../../../../gradle/libs.versions.toml"))
        }
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

rootProject.name = "client-jakarta"

// The shared wire-protocol logic. Use the source build while developing and publishing this
// repository; the published POM still records the normal Maven dependency.
includeBuild("../../protocol-java")

// All Gradle builds in this repo share the root version catalog.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../../../../gradle/libs.versions.toml"))
        }
    }
}

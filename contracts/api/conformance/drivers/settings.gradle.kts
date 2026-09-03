// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

// The JVM conformance drivers, as their own build.
//
// They depend on the clients; the clients know nothing about them. That direction matters: when the
// drivers lived in the clients' own builds, `./gradlew build` on the Kotlin client compiled and
// ktlinted harness code, so a style violation in a test driver could fail the build gate for a
// published artifact — and it did, once. `includeBuild` gives the same wiring the .NET driver gets
// from a ProjectReference: substituted from source, one way only.
rootProject.name = "conformance-drivers"

// Named with the -driver suffix because an included build and a project of the main build may not
// share a name, and the Jakarta client's build is itself called "jakarta".
include("kotlin-driver", "jakarta-driver")
project(":kotlin-driver").projectDir = file("kotlin")
project(":jakarta-driver").projectDir = file("jakarta")

includeBuild("../../clients/kotlin-spring-restclient")
includeBuild("../../clients/jakarta")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../../../../gradle/libs.versions.toml"))
        }
    }
}

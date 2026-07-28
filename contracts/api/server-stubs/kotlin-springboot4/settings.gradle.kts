rootProject.name = "server-kotlin-springboot4"

// All three builds in this repo share the root version catalog.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../../../../gradle/libs.versions.toml"))
        }
    }
}

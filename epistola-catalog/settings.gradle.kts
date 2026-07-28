rootProject.name = "epistola-catalog"

// All three builds in this repo share the root version catalog.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

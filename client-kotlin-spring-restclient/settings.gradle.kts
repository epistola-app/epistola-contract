rootProject.name = "client-kotlin-spring-restclient"

include(":client")

// All three builds in this repo share the root version catalog.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

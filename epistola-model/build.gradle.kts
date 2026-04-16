import net.pwall.json.kotlin.codegen.gradle.JSONSchemaCodegen
import net.pwall.json.kotlin.codegen.gradle.JSONSchemaCodegenPlugin

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("net.pwall.json:json-kotlin-gradle:0.121")
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    alias(libs.plugins.maven.publish)
    `java-library`
}

apply<JSONSchemaCodegenPlugin>()

group = "app.epistola.contract"
version = findProperty("version")?.toString()?.takeIf { it != "unspecified" } ?: "0.1.0-SNAPSHOT"
description = "Epistola Editor Model — shared document, theme, and component types"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val generatedSrcDir = layout.buildDirectory.dir("generated-sources/kotlin")

configure<JSONSchemaCodegen> {
    configFile.set(file("src/main/resources/codegen-config.json"))
    packageName.set("app.epistola.template.model")
    generatorComment.set("Generated from JSON Schema — do not edit manually")
    outputDir.set(generatedSrcDir.map { it.asFile })

    inputs {
        // template-shared.schema.json has only $defs, no root type
        inputComposite {
            file.set(file("schemas/template-shared.schema.json"))
            pointer.set("/\$defs")
            // Exclude string-only types that are just aliases, not useful as generated classes
            exclude.set(listOf("NodeId", "SlotId"))
        }

        // Root-level schemas with their own $defs
        inputFile(file("schemas/template-document.schema.json"))
        inputFile(file("schemas/theme.schema.json"))
        inputFile(file("schemas/component-manifest.schema.json"))
        inputFile(file("schemas/style-registry.schema.json"))
    }
}

// Remove generated types that need manual definitions:
// - DocumentStyles: open map type (Map<String, Any>) which the codegen can't express
// - Expression: needs a default value for `language` (jsonata) which the codegen can't express
// - TemplateDocument: codegen produces empty inner classes for nodes/slots maps, not Map<String, Node>
// - ThemeRef: codegen names subtypes A/B instead of Inherit/Override
// - PageSettings: needs default values for format/orientation which the codegen can't express
val removeGeneratedOverrides by tasks.registering(Delete::class) {
    delete(generatedSrcDir.map { it.file("app/epistola/template/model/DocumentStyles.kt") })
    delete(generatedSrcDir.map { it.file("app/epistola/template/model/Expression.kt") })
    delete(generatedSrcDir.map { it.file("app/epistola/template/model/TemplateDocument.kt") })
    delete(generatedSrcDir.map { it.file("app/epistola/template/model/ThemeRef.kt") })
    delete(generatedSrcDir.map { it.file("app/epistola/template/model/PageSettings.kt") })
}

tasks.named("generate") {
    finalizedBy(removeGeneratedOverrides)
    // json-kotlin-gradle uses Task.project at execution time — not config-cache compatible
    notCompatibleWithConfigurationCache("json-kotlin-gradle accesses Task.project at execution time")
}

sourceSets.main {
    kotlin.srcDirs(generatedSrcDir)
}

tasks.named("compileKotlin") {
    dependsOn("generate")
}

dependencies {
    // Jackson 2 annotations — compatible with both Jackson 2 (plugin) and Jackson 3 (suite) runtimes
    api("com.fasterxml.jackson.core:jackson-annotations:2.21")
}

tasks.test {
    useJUnitPlatform()
}

// Configure ktlint to exclude generated sources
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

kover {
    reports {
        total {
            xml {
                onCheck = false
            }
            html {
                onCheck = false
            }
        }
    }
}

// Configure vanniktech plugin's jar tasks to depend on generate since sources are generated
tasks.matching { it.name == "plainJavadocJar" || it.name == "sourcesJar" }.configureEach {
    dependsOn("generate", removeGeneratedOverrides)
}

// GitHub Packages repository for snapshot publishing (standard Gradle publishing plugin)
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/epistola-app/epistola-contract")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    // Only sign when GPG credentials are available (CI or release builds)
    if (project.findProperty("signing.keyId") != null || System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null) {
        signAllPublications()
    }

    coordinates(group.toString(), "epistola-model", version.toString())

    pom {
        name.set("Epistola Editor Model")
        description.set("Shared document, theme, and component types for the Epistola editor")
        url.set("https://github.com/epistola-app/epistola-contract")

        licenses {
            license {
                name.set("EUPL-1.2")
                url.set("https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12")
            }
        }

        developers {
            developer {
                id.set("sdegroot")
                name.set("Sander de Groot")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/epistola-app/epistola-contract.git")
            developerConnection.set("scm:git:ssh://github.com/epistola-app/epistola-contract.git")
            url.set("https://github.com/epistola-app/epistola-contract")
        }
    }
}

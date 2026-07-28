// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import java.util.jar.JarFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.maven.publish)
    `java-library`
}

// Sets group + version from the OpenAPI spec (shared across builds)
apply(from = "$rootDir/../../build-logic/contract-version.gradle.kts")

description = "Epistola API Server Interfaces for Kotlin/Spring"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.toolchain.get().toInt()))
    }
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to "server-kotlin-springboot4",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

val verifyJarManifest by tasks.registering {
    val jarTask = tasks.named<Jar>("jar")
    val jarFile = jarTask.flatMap { it.archiveFile }

    dependsOn(jarTask)
    inputs.file(jarFile)

    doLast {
        val implementationVersion =
            JarFile(jarFile.get().asFile).use { jar ->
                jar.manifest.mainAttributes.getValue("Implementation-Version")
            }

        check(implementationVersion == project.version.toString()) {
            "Expected server-kotlin-springboot4 JAR Implementation-Version=${project.version}, " +
                "but found $implementationVersion"
        }
    }
}

val generatedPom = layout.buildDirectory.file("publications/maven/pom-default.xml")
val verifyCatalogPomDependency by tasks.registering {
    dependsOn("generatePomFileForMavenPublication")
    inputs.file(generatedPom)

    doLast {
        val pom = generatedPom.get().asFile.readText()
        val expectedDependency =
            """
            <groupId>app.epistola.contract</groupId>
                  <artifactId>epistola-catalog</artifactId>
                  <version>${project.version}</version>
                  <scope>compile</scope>
            """.trimIndent()

        check(pom.contains(expectedDependency)) {
            "Published server POM must expose epistola-catalog:${project.version} as a compile dependency"
        }
    }
}

tasks.check {
    dependsOn(verifyJarManifest, verifyCatalogPomDependency)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

val generatedDir = layout.buildDirectory.dir("generated")
val bundledSpec = file("$rootDir/../../build/openapi.yaml")

// Fail early if bundled spec doesn't exist
if (!bundledSpec.exists()) {
    throw GradleException(
        """
        Bundled OpenAPI spec not found at: ${bundledSpec.absolutePath}

        Run from the repository root:
            make bundle

        Or use: make bundle
        """.trimIndent(),
    )
}

openApiGenerate {
    generatorName.set("kotlin-spring")
    inputSpec.set(bundledSpec.absolutePath)
    outputDir.set(generatedDir.map { it.asFile.absolutePath })

    apiPackage.set("app.epistola.api")
    modelPackage.set("app.epistola.api.model")
    invokerPackage.set("app.epistola.api")

    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useSpringBoot3" to "true",
            "useBeanValidation" to "true",
            "useTags" to "true",
            "dateLibrary" to "java8-localdatetime",
            "serializationLibrary" to "jackson",
            "enumPropertyNaming" to "UPPERCASE",
            "skipDefaultInterface" to "true",
            "exceptionHandler" to "false",
            "gradleBuildFile" to "false",
            "documentationProvider" to "none",
            "useJakartaEe" to "true",
        ),
    )

    // Suppresses models that schemaMappings alone doesn't (see the ignore file's comments)
    ignoreFileOverride.set("$projectDir/.openapi-generator-ignore")

    // Use ObjectNode for generic objects to properly handle null values
    importMappings.set(
        mapOf(
            "ObjectNode" to "tools.jackson.databind.node.ObjectNode",
        ),
    )

    typeMappings.set(
        mapOf(
            "object" to "ObjectNode",
        ),
    )

    // Reuse the portable catalog's Kotlin data classes in server signatures.
    // Other generated language clients still receive native models from the
    // same OpenAPI schemas; this mapping applies only to the JVM server artifact.
    //
    // Reuse Spring's native RFC 9457 type instead of generating a parallel DTO.
    // `org.springframework.http.ProblemDetail` serializes to `application/problem+json`
    // out of the box and is produced by `ResponseEntityExceptionHandler`. The `errors`
    // extension member becomes a dynamic property (set via `setProperty`).
    // schemaMappings substitutes the schema with the FQN AND skips generating the model.
    schemaMappings.set(
        mapOf(
            "TemplateDocument" to "app.epistola.template.model.TemplateDocument",
            "Node" to "app.epistola.template.model.Node",
            "Slot" to "app.epistola.template.model.Slot",
            "ThemeRef" to "app.epistola.template.model.ThemeRef",
            "PageSettings" to "app.epistola.template.model.PageSettings",
            "Margins" to "app.epistola.template.model.Margins",
            "PageFormat" to "app.epistola.template.model.PageFormat",
            "Orientation" to "app.epistola.template.model.Orientation",
            "DocumentStyles" to "app.epistola.template.model.DocumentStyles",
            "BlockStylePreset" to "app.epistola.template.model.BlockStylePreset",
            "ProblemDetail" to "org.springframework.http.ProblemDetail",
            "ValidationProblemDetail" to "org.springframework.http.ProblemDetail",
            "DataModelValidationProblemDetail" to "org.springframework.http.ProblemDetail",
        ),
    )

    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "supportingFiles" to "",
        ),
    )
}

tasks.openApiGenerate {
    doLast {
        // OpenAPI Generator derives Spring `produces` from the union of a method's response
        // media types. Bodyless 204 success responses contribute none, so for those operations
        // the only media type left is `application/problem+json` from the error responses —
        // which the controller never actually returns on success. We normalize the generated
        // mappings to also accept the success media type.
        //
        // NOTE: there is no clean spec- or generator-config-level fix:
        //   - adding a fake `content` to the 204 lies about the bodyless response and corrupts
        //     the published spec, the rendered docs, and the mock server;
        //   - no kotlin-spring config option excludes error responses from `produces` derivation;
        //   - the generator honours no per-operation produces-override vendor extension;
        //   - a forked api.mustache template is more fragile than this localized rewrite.
        // So we post-process here. The `replaced == 0` guard below logs a warning if a generator
        // upgrade changes the emitted string and the rewrite silently stops matching.
        var replaced = 0
        fileTree(generatedDir.get().dir("src/main/kotlin/app/epistola/api")) {
            include("**/*Api.kt")
        }.forEach { apiFile ->
            val source = apiFile.readText()
            val normalized = source.replace(
                Regex("""produces\s*=\s*\[\s*"application/problem\+json"\s*]"""),
                """produces = ["application/vnd.epistola.v1+json", "application/problem+json"]""",
            )
            if (normalized != source) {
                apiFile.writeText(normalized)
                replaced++
            }
        }
        if (replaced == 0) {
            throw GradleException(
                "produces normalization matched no generated Api.kt files — an OpenAPI Generator " +
                    "upgrade likely changed the emitted `produces = [...]` string; update the regex above",
            )
        }
    }
}

sourceSets {
    main {
        kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
        kotlin.srcDir("src/main/kotlin")
        resources.srcDir(layout.buildDirectory.dir("openapi-resource"))
    }
}

val copyOpenApiSpec by tasks.registering(Copy::class) {
    from(bundledSpec)
    rename { "epistola-contract.yaml" }
    into(layout.buildDirectory.dir("openapi-resource/openapi"))
}

tasks.processResources {
    dependsOn(copyOpenApiSpec)
}

tasks.compileKotlin {
    dependsOn(tasks.openApiGenerate)
}

dependencies {
    api("app.epistola.contract:epistola-catalog:${project.version}")

    implementation(libs.spring.boot4.starter.web)
    implementation(libs.spring.boot4.starter.validation)
    implementation(libs.jakarta.validation.api)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.databind)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Exclude generated build files from ktlint
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask> {
    dependsOn(tasks.openApiGenerate)
}

tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask> {
    dependsOn(tasks.openApiGenerate)
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

// Configure vanniktech plugin's jar tasks to depend on openApiGenerate since sources are generated
tasks.matching { it.name == "plainJavadocJar" || it.name == "sourcesJar" }.configureEach {
    dependsOn(tasks.openApiGenerate, copyOpenApiSpec)
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

    coordinates(group.toString(), "server-kotlin-springboot4", version.toString())

    pom {
        name.set("Epistola Kotlin Server")
        description.set("Kotlin Spring server interfaces for Epistola API")
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

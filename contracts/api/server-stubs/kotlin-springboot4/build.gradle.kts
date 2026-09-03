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
        // protocol-java is Java, and its package is @NullMarked. Without the jspecify flag Kotlin
        // reads its signatures as platform types and silently drops null-safety exactly where null
        // carries meaning — a User-Agent product that is absent, a type URI with no Epistola slug.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjspecify-annotations=strict")
    }
}

// The shared wire-protocol logic is compiled in rather than depended on: it is not published, so
// consumers see no extra coordinate and nothing to resolve. Its own build
// (contracts/api/protocol-java) keeps it under test in isolation.
val epistolaProtocolSources = file("$rootDir/../../protocol-java/src/main/java")

require(epistolaProtocolSources.isDirectory) {
    "shared protocol sources not found at $epistolaProtocolSources"
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

// --- Problem-slug constants generated from the spec's x-problem-types registry ---
//
// The same generation the two clients use, from the same shared reading of the spec: the server
// and the clients sit on opposite sides of one error contract, so the slugs they switch on must
// come from one place. Before this, the server's copy was hand-written and only a guard test
// stood between a new problem type and a silently stale constant.
apply(from = "$rootDir/../../build-logic/contract-spec-model.gradle.kts")

@Suppress("UNCHECKED_CAST")
val specModel = extra["epistolaSpecModel"] as (Map<String, Any>) -> Map<String, Any>

@Suppress("UNCHECKED_CAST")
fun readSpec(spec: File): Map<String, Any> = specModel(org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(spec.readText()))

val generatedProblemSlugsDir = layout.buildDirectory.dir("generated-problem-slugs/src/main/kotlin")

val generateProblemSlugs by tasks.registering {
    description = "Generates KnownProblemSlugs from the spec's x-problem-types extension"

    inputs.file(bundledSpec)
    outputs.dir(generatedProblemSlugsDir)

    @Suppress("UNCHECKED_CAST")
    doLast {
        val model = readSpec(bundledSpec)
        val base = model["problemTypeBase"] as String
        val types = model["problemTypes"] as List<Map<String, Any?>>

        val constants = types.joinToString("\n\n") { entry ->
            "    /** ${entry["status"]} — ${entry["description"]} */\n" +
                "    const val ${entry["constantName"]}: String = \"${entry["slug"]}\""
        }

        val extensionMembers = (model["problemExtensionMembers"] as Map<String, List<String>>)
            .entries
            .sortedBy { it.key }
            .flatMap { (schema, members) -> members.map { schema to it } }
            .joinToString("\n\n") { (schema, member) ->
                "    /** The `$member` extension member of `$schema`. */\n" +
                    "    const val ${member.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()}: String = \"$member\""
            }

        val outFile = generatedProblemSlugsDir.get()
            .file("app/epistola/api/error/KnownProblemSlugs.kt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            """
            |// Generated from the bundled OpenAPI spec's x-problem-types extension — do not edit.
            |package app.epistola.api.error
            |
            |/** Base URI from the spec's x-problem-types registry; the source of [ProblemDetails.TYPE_BASE]. */
            |const val GENERATED_PROBLEM_TYPE_BASE: String = "$base"
            |
            |/**
            | * The canonical problem `type` slugs the Epistola API emits, from the contract's
            | * error-type registry (the spec's `x-problem-types` extension / `docs/error-types.md`).
            | *
            | * Generated, so it cannot drift from the contract. The published clients generate the
            | * same constants from the same registry, which is what makes a `when (e.typeSlug)` on
            | * the client line up with what a server built on these interfaces emits.
            | */
            |object KnownProblemSlugs {
            |$constants
            |}
            |
            |/**
            | * The names of the members Epistola problem bodies carry on top of the RFC 9457 base,
            | * derived from the problem schemas the registry names.
            | *
            | * This server writes them and the published clients read them back out of the raw body by
            | * name, so both generate the names from the contract: a rename would otherwise make the
            | * extension silently vanish rather than fail.
            | */
            |object ProblemExtensionMembers {
            |$extensionMembers
            |}
            """.trimMargin() + "\n",
        )
        logger.lifecycle("Generated KnownProblemSlugs with ${types.size} slug(s) → ${outFile.relativeTo(project.projectDir)}")
    }
}

// --- Client-identity constants generated from the spec's x-client-identity extension ---
//
// This server parses the headers the clients write. Generated on both sides, so the two halves of
// one wire contract cannot drift apart.
val generatedIdentityDir = layout.buildDirectory.dir("generated-identity/src/main/kotlin")

val generateClientIdentityConstants by tasks.registering {
    description = "Generates the client-identity constants from the spec's x-client-identity extension"

    inputs.file(bundledSpec)
    outputs.dir(generatedIdentityDir)

    @Suppress("UNCHECKED_CAST")
    doLast {
        val identity = readSpec(bundledSpec)["clientIdentity"] as Map<String, String>

        val outFile = generatedIdentityDir.get()
            .file("app/epistola/api/identity/ContractIdentity.kt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            """
            |// Generated from the bundled OpenAPI spec's x-client-identity extension — do not edit.
            |package app.epistola.api.identity
            |
            |/**
            | * The client-identity wire contract, from the spec's `x-client-identity` extension.
            | *
            | * The Epistola clients write these headers and this module parses them; both generate
            | * from this one registry, so the two halves cannot drift apart.
            | */
            |internal object ContractIdentity {
            |    /** Header carrying the caller's node identifier. */
            |    const val NODE_ID_HEADER: String = "${identity["nodeIdHeader"]}"
            |
            |    /** The product token every Epistola client's `User-Agent` leads with. */
            |    const val CONTRACT_PRODUCT: String = "${identity["contractProduct"]}"
            |
            |    /** Separator between `User-Agent` product tokens. */
            |    const val PRODUCT_SEPARATOR: String = "${identity["userAgentProductSeparator"]}"
            |
            |    /** Separator between a product name and its version. */
            |    const val VERSION_SEPARATOR: String = "${identity["userAgentVersionSeparator"]}"
            |}
            """.trimMargin() + "\n",
        )
        logger.lifecycle("Generated ContractIdentity → ${outFile.relativeTo(project.projectDir)}")
    }
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
    // Read outside doLast: the vendor media type carries the API major version, so it comes from
    // the spec rather than a literal that a version bump would leave behind.
    val vendorJson = (readSpec(bundledSpec)["vendorMediaTypes"] as Map<String, String>)["json"]

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
                """produces = ["$vendorJson", "application/problem+json"]""",
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
        kotlin.srcDir(generatedProblemSlugsDir)
        kotlin.srcDir(generatedIdentityDir)
        java.srcDir(epistolaProtocolSources)
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
    dependsOn(tasks.openApiGenerate, generateProblemSlugs, generateClientIdentityConstants)
}

dependencies {
    api("app.epistola.contract:epistola-catalog:${project.version}")

    // Needed to compile the shared protocol sources, whose package is @NullMarked.
    compileOnly(libs.jspecify)

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
    dependsOn(tasks.openApiGenerate, generateProblemSlugs, generateClientIdentityConstants)
}

tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask> {
    dependsOn(tasks.openApiGenerate, generateProblemSlugs, generateClientIdentityConstants)
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
    dependsOn(tasks.openApiGenerate, generateProblemSlugs, generateClientIdentityConstants, copyOpenApiSpec)
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

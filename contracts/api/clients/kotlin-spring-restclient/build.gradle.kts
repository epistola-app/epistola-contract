// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

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

description = "Epistola API Client for Kotlin using Spring RestClient"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.toolchain.get().toInt()))
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

// The shared wire-protocol logic is compiled in rather than depended on: it is not published, so
// consumers see no extra coordinate and nothing to resolve. Its own build (contracts/api/protocol-java)
// keeps it under test in isolation.
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

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(bundledSpec.absolutePath)
    outputDir.set(generatedDir.map { it.asFile.absolutePath })
    packageName.set("app.epistola.client")
    apiPackage.set("app.epistola.client.api")
    modelPackage.set("app.epistola.client.model")
    configOptions.set(
        mapOf(
            "library" to "jvm-spring-restclient",
            "serializationLibrary" to "jackson",
            "dateLibrary" to "java8",
            "omitGradleWrapper" to "true",
            "omitGradlePluginVersions" to "true",
            "enumPropertyNaming" to "UPPERCASE",
            "useSpringBoot3" to "true",
        ),
    )
}

// The spec-walking these three tasks share with the Jakarta client lives in build-logic; only the
// emitted syntax differs, so only the emitted syntax is here.
apply(from = "$rootDir/../../build-logic/contract-spec-model.gradle.kts")

@Suppress("UNCHECKED_CAST")
val specModel = extra["epistolaSpecModel"] as (Map<String, Any>) -> Map<String, Any>

@Suppress("UNCHECKED_CAST")
fun readSpec(spec: File): Map<String, Any> = specModel(org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(spec.readText()))

// --- Client-side validation extension generation ---
val generatedValidationDir = layout.buildDirectory.dir("generated-validation/src/main/kotlin")

val generateValidation by tasks.registering {
    description = "Generates .validate() extension functions from OpenAPI schema constraints"
    dependsOn(tasks.openApiGenerate)

    inputs.file(bundledSpec)
    outputs.dir(generatedValidationDir)

    @Suppress("UNCHECKED_CAST")
    doLast {
        fun escapeKotlin(s: String): String = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\$", "\\\$")

        fun check(property: String, constraint: Map<String, Any?>): String = when (constraint["kind"]) {
            "length" -> {
                val min = constraint["min"] as Int?
                val max = constraint["max"] as Int?
                when {
                    min != null && max != null ->
                        """require(it.length in $min..$max) { "$property: length must be between $min and $max" }"""
                    min != null ->
                        """require(it.length >= $min) { "$property: length must be at least $min" }"""
                    else ->
                        """require(it.length <= $max) { "$property: length must be at most $max" }"""
                }
            }

            "pattern" -> {
                val esc = escapeKotlin(constraint["pattern"] as String)
                """require(it.matches(Regex("$esc"))) { "$property: must match pattern $esc" }"""
            }

            "range" -> {
                val min = (constraint["min"] as Long?)?.toInt()
                val max = (constraint["max"] as Long?)?.toInt()
                when {
                    min != null && max != null ->
                        """require(it in $min..$max) { "$property: must be between $min and $max" }"""
                    min != null ->
                        """require(it >= $min) { "$property: must be at least $min" }"""
                    else ->
                        """require(it <= $max) { "$property: must be at most $max" }"""
                }
            }

            "minItems" -> {
                val min = constraint["min"] as Int
                """require(it.size >= $min) { "$property: must have at least $min item(s)" }"""
            }

            else -> throw GradleException("unhandled constraint kind: ${constraint["kind"]}")
        }

        val validations = (readSpec(bundledSpec)["constrainedSchemas"] as List<Map<String, Any?>>).map { schema ->
            val schemaName = schema["name"] as String
            val body = (schema["fields"] as List<Map<String, Any?>>).joinToString("\n") { field ->
                val property = field["property"] as String
                val indent = "        "
                val checksBlock = (field["constraints"] as List<Map<String, Any?>>)
                    .joinToString("\n") { "$indent${check(property, it)}" }
                if (field["nullable"] as Boolean) {
                    "    $property?.let {\n$checksBlock\n    }"
                } else {
                    "    $property.let {\n$checksBlock\n    }"
                }
            }
            "fun $schemaName.validate(): $schemaName {\n$body\n    return this\n}"
        }

        val outFile = generatedValidationDir.get()
            .file("app/epistola/client/validation/ModelValidation.kt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            buildString {
                appendLine("package app.epistola.client.validation")
                appendLine()
                appendLine("import app.epistola.client.model.*")
                appendLine()
                append(validations.joinToString("\n\n"))
                appendLine()
            },
        )
        logger.lifecycle("Generated validation for ${validations.size} model(s) → ${outFile.relativeTo(project.projectDir)}")
    }
}

// --- Problem-slug constants generated from the spec's x-problem-types registry ---
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
            .file("app/epistola/client/error/KnownProblemSlugs.kt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            """
            |// Generated from the bundled OpenAPI spec's x-problem-types extension — do not edit.
            |package app.epistola.client.error
            |
            |/** Base URI from the spec's x-problem-types registry; must equal [ProblemTypes.TYPE_BASE]. */
            |internal const val GENERATED_PROBLEM_TYPE_BASE: String = "$base"
            |
            |/**
            | * The canonical problem `type` slugs the Epistola API emits, from the contract's
            | * error-type registry (the spec's `x-problem-types` extension / `docs/error-types.md`).
            | *
            | * These are convenience constants for `when (e.typeSlug)` switches. `typeSlug` is deliberately a
            | * plain `String?` (not an enum) so the API can introduce new problem types without forcing a
            | * client release — always keep an `else` branch.
            | */
            |object KnownProblemSlugs {
            |$constants
            |}
            |
            |/**
            | * The names of the members Epistola problem bodies carry on top of the RFC 9457 base,
            | * derived from the problem schemas the registry names.
            | *
            | * The server writes them and this client reads them back out of the raw body by name, so
            | * both generate the names from the contract: a rename would otherwise make the extension
            | * silently vanish rather than fail.
            | */
            |internal object ProblemExtensionMembers {
            |$extensionMembers
            |}
            """.trimMargin() + "\n",
        )
        logger.lifecycle("Generated KnownProblemSlugs with ${types.size} slug(s) → ${outFile.relativeTo(project.projectDir)}")
    }
}

// --- Client-identity constants generated from the spec's x-client-identity extension ---
//
// The header name and the User-Agent product token are the wire contract this client writes and
// the server module parses. Generated on both sides, so they cannot disagree.
val generatedIdentityDir = layout.buildDirectory.dir("generated-identity/src/main/kotlin")

val generateClientIdentityConstants by tasks.registering {
    description = "Generates the client-identity constants from the spec's x-client-identity extension"

    inputs.file(bundledSpec)
    outputs.dir(generatedIdentityDir)

    @Suppress("UNCHECKED_CAST")
    doLast {
        val model = readSpec(bundledSpec)
        val identity = model["clientIdentity"] as Map<String, String>
        val mediaTypes = model["vendorMediaTypes"] as Map<String, String>

        val outFile = generatedIdentityDir.get()
            .file("app/epistola/client/identity/ContractIdentity.kt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            """
            |// Generated from the bundled OpenAPI spec's x-client-identity extension — do not edit.
            |package app.epistola.client.identity
            |
            |/**
            | * The client-identity wire contract, from the spec's `x-client-identity` extension.
            | *
            | * This client writes these headers and the Epistola server module parses them; both
            | * generate from this one registry, so the two halves cannot drift apart.
            | */
            |internal object ContractIdentity {
            |    /** Header carrying the caller's node identifier. */
            |    const val NODE_ID_HEADER: String = "${identity["nodeIdHeader"]}"
            |
            |    /** The product token every Epistola client's `User-Agent` must lead with. */
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
        val mediaTypeFile = generatedIdentityDir.get()
            .file("app/epistola/client/ContractMediaTypes.kt").asFile
        mediaTypeFile.parentFile.mkdirs()
        mediaTypeFile.writeText(
            """
            |// Generated from the media types the bundled OpenAPI spec declares — do not edit.
            |package app.epistola.client
            |
            |/**
            | * The versioned vendor media types this API speaks.
            | *
            | * Generated because they carry the API major version: hand-writing them in the request
            | * paths the generator does not cover would leave those paths behind at the next bump.
            | * Public because a consumer building its own request needs the same values.
            | */
            |object ContractMediaTypes {
            |    /** Request and response bodies. */
            |    const val VENDOR_JSON: String = "${mediaTypes["json"]}"
            |
            |    /** Streamed NDJSON responses, as used by result collection. */
            |    const val VENDOR_NDJSON: String = "${mediaTypes["ndjson"]}"
            |}
            """.trimMargin() + "\n",
        )
        logger.lifecycle("Generated ContractIdentity + ContractMediaTypes → ${outFile.relativeTo(project.projectDir)}")
    }
}

// Generate a resource file with the contract version so ClientIdentity can read it at runtime.
// Uses the version from the spec (which is updated to the full version on each release).
val generateContractVersionResource by tasks.registering {
    description = "Writes the contract version to a resource file for ClientIdentity"
    inputs.file(bundledSpec)
    val outputDir = layout.buildDirectory.dir("generated-resources")
    outputs.dir(outputDir)

    doLast {
        val version = readSpec(bundledSpec)["version"] as String
        val outFile = outputDir.get().file("epistola-contract-version.txt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(version)
        logger.lifecycle("Wrote contract version $version → ${outFile.relativeTo(project.projectDir)}")
    }
}

sourceSets {
    main {
        kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
        kotlin.srcDir(generatedValidationDir)
        kotlin.srcDir(generatedProblemSlugsDir)
        kotlin.srcDir(generatedIdentityDir)
        java.srcDir(epistolaProtocolSources)
        resources.srcDir(layout.buildDirectory.dir("generated-resources"))
    }
}

tasks.processResources {
    dependsOn(generateContractVersionResource)
}

tasks.compileKotlin {
    dependsOn(generateValidation, generateProblemSlugs, generateClientIdentityConstants)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        // -Xjsr305=strict for the Spring annotations; -Xjspecify for protocol-java, whose package
        // is @NullMarked — without it Kotlin reads those signatures as platform types and silently
        // drops null-safety exactly where null carries meaning.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjspecify-annotations=strict")
    }
}

dependencies {
    // Needed to compile the shared protocol sources, whose package is @NullMarked. compileOnly:
    // nothing needs the annotation classes at runtime.
    compileOnly(libs.jspecify)

    // `api`, not `implementation`: RestClient.Builder, RestClientResponseException and
    // ClientHttpRequestInterceptor all appear in this client's public signatures, so a consumer
    // catching ProblemDetailException has to compile against them.
    //
    // spring-web rather than spring-boot-starter-web: this library calls HTTP, it does not serve
    // it. The starter resolved 33 artifacts including embedded Tomcat and Spring MVC, all of which
    // landed on every consumer's classpath.
    api(libs.spring.web)
    api(libs.jackson2.module.kotlin)
    api(libs.jackson2.datatype.jsr310)
    compileOnly(libs.json.schema.validator)

    // Kept for tests only: JwtSignerTest parses and verifies the tokens with a real, third-party
    // JOSE library, which is a stronger check of the hand-rolled signer than verifying it with
    // our own code would be.
    testImplementation(libs.nimbus.jose.jwt)
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.json.schema.validator)
}

// --- Cross-client conformance driver ---
//
// The driver lives with the other three under contracts/api/conformance/drivers, so the four are
// read side by side; it compiles here because that is where the client and its generated API are.
// It is its own source set, so nothing it needs reaches the published jar.
val conformanceDriverSources = file("$rootDir/../../conformance/drivers/kotlin/src/main/kotlin")

sourceSets {
    register("conformanceDriver") {
        kotlin.srcDir(conformanceDriverSources)
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

configurations {
    named("conformanceDriverImplementation") { extendsFrom(configurations.implementation.get(), configurations.api.get()) }
    named("conformanceDriverRuntimeOnly") { extendsFrom(configurations.runtimeOnly.get()) }
}

// The harness runs `java -cp @classpath.txt`, so it needs no Gradle daemon per scenario — eight
// scenarios would otherwise mean eight Gradle invocations.
val conformanceDriverClasspath by tasks.registering {
    description = "Compiles the conformance driver and writes its runtime classpath for the harness"
    group = "verification"

    // The source set's own runtimeClasspath, which already carries main's output and every
    // dependency it inherits — assembling it from the configuration alone leaves the client itself
    // off, which fails only at run time.
    val driverRuntime = sourceSets.named("conformanceDriver").map { it.runtimeClasspath }
    val outFile = layout.buildDirectory.file("conformance/classpath.txt")
    dependsOn(tasks.named("conformanceDriverClasses"))
    outputs.file(outFile)

    doLast {
        val entries = driverRuntime.get().files
        val file = outFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(entries.joinToString(File.pathSeparator) { it.absolutePath })
    }
}

// --- Dependency hygiene ---
//
// This is a library for *calling* HTTP. It declared spring-boot-starter-web for a long time, which
// put embedded Tomcat and Spring MVC on every consumer's classpath. The report below is what
// DependencyHygieneTest asserts on, so that cannot come back unnoticed.
val dependencyReportDir = layout.buildDirectory.dir("generated-test-resources")

val generateDependencyReport by tasks.registering {
    description = "Records the resolved runtime classpath for DependencyHygieneTest"
    group = "verification"

    val runtimeClasspath = configurations.named("runtimeClasspath")
    val outDir = dependencyReportDir
    outputs.dir(outDir)

    doLast {
        val outFile = outDir.get().file("runtime-dependencies.txt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            buildString {
                appendLine("# Generated by the generateDependencyReport task — do not edit.")
                runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
                    .map { with(it.moduleVersion.id) { "$group:$name" } }
                    .sorted()
                    .distinct()
                    .forEach { appendLine(it) }
            },
        )
    }
}

sourceSets {
    test {
        resources.srcDir(dependencyReportDir)
    }
}

tasks.processTestResources {
    dependsOn(generateDependencyReport)
}

tasks.test {
    useJUnitPlatform()
}

// Exclude generated build files from ktlint
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask> {
    dependsOn(generateValidation, generateProblemSlugs, generateClientIdentityConstants)
}

tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask> {
    dependsOn(generateValidation, generateProblemSlugs, generateClientIdentityConstants)
}

// Configure ktlint to exclude generated sources
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

// Configure vanniktech plugin's jar tasks to depend on code generation since sources are generated
tasks.matching { it.name == "plainJavadocJar" || it.name == "sourcesJar" }.configureEach {
    dependsOn(
        generateValidation,
        generateProblemSlugs,
        generateClientIdentityConstants,
        generateContractVersionResource,
    )
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

    coordinates(rootProject.group.toString(), "client-spring3-restclient", rootProject.version.toString())

    pom {
        name.set("Epistola Kotlin Client")
        description.set("Kotlin client library for Epistola API using Spring RestClient")
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

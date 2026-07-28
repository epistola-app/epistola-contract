// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.maven.publish)
    `java-library`
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

// --- Client-side validation extension generation ---
val generatedValidationDir = layout.buildDirectory.dir("generated-validation/src/main/kotlin")

val generateValidation by tasks.registering {
    description = "Generates .validate() extension functions from OpenAPI schema constraints"
    dependsOn(tasks.openApiGenerate)

    inputs.file(bundledSpec)
    outputs.dir(generatedValidationDir)

    @Suppress("UNCHECKED_CAST")
    doLast {
        val yaml = org.yaml.snakeyaml.Yaml()
        val spec = yaml.load<Map<String, Any>>(bundledSpec.readText()) as Map<String, Any>
        val schemas = ((spec["components"] as Map<String, Any>)["schemas"] as Map<String, Any>)

        fun escapeKotlin(s: String): String = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\$", "\\\$")

        data class FieldValidation(
            val name: String,
            val isNullable: Boolean,
            val checks: List<String>,
        )

        val validations = mutableListOf<String>()

        for ((schemaName, schemaDef) in schemas) {
            val schema = schemaDef as? Map<String, Any> ?: continue
            if (schema["type"] != "object") continue

            val required = (schema["required"] as? List<String>) ?: emptyList()
            val properties = (schema["properties"] as? Map<String, Any>) ?: continue

            val fields = mutableListOf<FieldValidation>()

            for ((propName, propDef) in properties) {
                val prop = propDef as? Map<String, Any> ?: continue
                // Skip $ref properties — those types have their own .validate()
                if (prop.containsKey("\$ref")) continue

                val type = prop["type"]
                val isNullableType = type is List<*> && type.contains("null")
                val baseType = when (type) {
                    is String -> type
                    is List<*> -> type.firstOrNull { it != "null" }?.toString()
                    else -> null
                } ?: continue
                val isNullable = propName !in required || isNullableType

                val checks = mutableListOf<String>()

                when (baseType) {
                    "string" -> {
                        val pattern = prop["pattern"] as? String
                        val minLen = (prop["minLength"] as? Number)?.toInt()
                        val maxLen = (prop["maxLength"] as? Number)?.toInt()

                        if (minLen != null && maxLen != null) {
                            checks.add("""require(it.length in $minLen..$maxLen) { "$propName: length must be between $minLen and $maxLen" }""")
                        } else if (minLen != null) {
                            checks.add("""require(it.length >= $minLen) { "$propName: length must be at least $minLen" }""")
                        } else if (maxLen != null) {
                            checks.add("""require(it.length <= $maxLen) { "$propName: length must be at most $maxLen" }""")
                        }

                        if (pattern != null) {
                            val esc = escapeKotlin(pattern)
                            checks.add("""require(it.matches(Regex("$esc"))) { "$propName: must match pattern $esc" }""")
                        }
                    }
                    "integer" -> {
                        val min = (prop["minimum"] as? Number)?.toInt()
                        val max = (prop["maximum"] as? Number)?.toInt()

                        if (min != null && max != null) {
                            checks.add("""require(it in $min..$max) { "$propName: must be between $min and $max" }""")
                        } else if (min != null) {
                            checks.add("""require(it >= $min) { "$propName: must be at least $min" }""")
                        } else if (max != null) {
                            checks.add("""require(it <= $max) { "$propName: must be at most $max" }""")
                        }
                    }
                    "array" -> {
                        val minItems = (prop["minItems"] as? Number)?.toInt()
                        if (minItems != null) {
                            checks.add("""require(it.size >= $minItems) { "$propName: must have at least $minItems item(s)" }""")
                        }
                    }
                }

                if (checks.isNotEmpty()) {
                    fields.add(FieldValidation(propName, isNullable, checks))
                }
            }

            if (fields.isNotEmpty()) {
                val body = fields.joinToString("\n") { f ->
                    val indent = "        "
                    val checksBlock = f.checks.joinToString("\n") { "$indent$it" }
                    if (f.isNullable) {
                        "    ${f.name}?.let {\n$checksBlock\n    }"
                    } else {
                        "    ${f.name}.let {\n$checksBlock\n    }"
                    }
                }
                validations.add("fun $schemaName.validate(): $schemaName {\n$body\n    return this\n}")
            }
        }

        if (validations.isEmpty()) {
            throw GradleException(
                "generateValidation produced no validators — either the bundled spec lost all its " +
                    "constraints or the schema-walking code above no longer matches the spec structure",
            )
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
        val yaml = org.yaml.snakeyaml.Yaml()
        val spec = yaml.load<Map<String, Any>>(bundledSpec.readText()) as Map<String, Any>
        val registry = spec["x-problem-types"] as? Map<String, Any>
            ?: throw GradleException(
                "bundled spec has no x-problem-types extension — KnownProblemSlugs cannot be generated",
            )
        val base = registry["base"] as? String
            ?: throw GradleException("x-problem-types.base is missing from the bundled spec")
        val types = (registry["types"] as? List<Map<String, Any>>).orEmpty()
        if (types.size < 8) {
            throw GradleException(
                "x-problem-types lists only ${types.size} problem types (expected at least 8) — " +
                    "was the registry truncated?",
            )
        }

        val constants = types.joinToString("\n\n") { entry ->
            val slug = entry["slug"] as String
            val status = entry["status"]
            val description = (entry["description"] as? String).orEmpty().replace(Regex("\\s+"), " ").trim()
            val constName = slug.uppercase().replace('-', '_')
            "    /** $status — $description */\n" +
                "    const val $constName: String = \"$slug\""
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
            """.trimMargin() + "\n",
        )
        logger.lifecycle("Generated KnownProblemSlugs with ${types.size} slug(s) → ${outFile.relativeTo(project.projectDir)}")
    }
}

// Generate a resource file with the contract version so ClientIdentity can read it at runtime.
// Uses the version from the spec (which is updated to the full version on each release).
val generateContractVersionResource by tasks.registering {
    description = "Writes the contract version to a resource file for ClientIdentity"
    inputs.file(bundledSpec)
    val outputDir = layout.buildDirectory.dir("generated-resources")
    outputs.dir(outputDir)

    @Suppress("UNCHECKED_CAST")
    doLast {
        val yaml = org.yaml.snakeyaml.Yaml()
        val spec = yaml.load<Map<String, Any>>(bundledSpec.readText()) as Map<String, Any>
        val version = (spec["info"] as Map<String, Any>)["version"] as String
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
        resources.srcDir(layout.buildDirectory.dir("generated-resources"))
    }
}

tasks.processResources {
    dependsOn(generateContractVersionResource)
}

tasks.compileKotlin {
    dependsOn(generateValidation, generateProblemSlugs)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.spring.boot3.starter.web)
    implementation(libs.jackson2.module.kotlin)
    implementation(libs.jackson2.datatype.jsr310)
    implementation(libs.nimbus.jose.jwt)
    compileOnly(libs.json.schema.validator)

    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.json.schema.validator)
}

tasks.test {
    useJUnitPlatform()
}

// Exclude generated build files from ktlint
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask> {
    dependsOn(generateValidation, generateProblemSlugs)
}

tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask> {
    dependsOn(generateValidation, generateProblemSlugs)
}

// Configure ktlint to exclude generated sources
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

// Configure vanniktech plugin's jar tasks to depend on code generation since sources are generated
tasks.matching { it.name == "plainJavadocJar" || it.name == "sourcesJar" }.configureEach {
    dependsOn(generateValidation, generateProblemSlugs, generateContractVersionResource)
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

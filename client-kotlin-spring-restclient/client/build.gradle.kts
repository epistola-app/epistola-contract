plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.maven.publish)
    `java-library`
}

val generatedDir = layout.buildDirectory.dir("generated")
val bundledSpec = file("$rootDir/../openapi.yaml")

// Fail early if bundled spec doesn't exist
if (!bundledSpec.exists()) {
    throw GradleException(
        """
        Bundled OpenAPI spec not found at: ${bundledSpec.absolutePath}

        Run from the repository root:
            npx @redocly/cli bundle epistola-api.yaml -o openapi.yaml

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

        if (validations.isNotEmpty()) {
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
}

sourceSets {
    main {
        kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
        kotlin.srcDir(generatedValidationDir)
    }
}

tasks.compileKotlin {
    dependsOn(generateValidation)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Exclude generated build files from ktlint
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask> {
    dependsOn(generateValidation)
}

tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask> {
    dependsOn(generateValidation)
}

// Configure ktlint to exclude generated sources
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

// Configure vanniktech plugin's jar tasks to depend on code generation since sources are generated
tasks.matching { it.name == "plainJavadocJar" || it.name == "sourcesJar" }.configureEach {
    dependsOn(generateValidation)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

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

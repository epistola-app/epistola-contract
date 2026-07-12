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
apply(from = "$rootDir/../gradle/contract-version.gradle.kts")

description = "Epistola API Server Interfaces for Kotlin/Spring"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.toolchain.get().toInt()))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
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

    // Reuse Spring's native RFC 9457 type instead of generating a parallel DTO.
    // `org.springframework.http.ProblemDetail` serializes to `application/problem+json`
    // out of the box and is produced by `ResponseEntityExceptionHandler`. The `errors`
    // extension member becomes a dynamic property (set via `setProperty`).
    // schemaMappings substitutes the schema with the FQN AND skips generating the model.
    schemaMappings.set(
        mapOf(
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
        resources.srcDir(layout.buildDirectory.dir("generated-resources"))
    }
}

val copyOpenApiSpec by tasks.registering(Copy::class) {
    from(bundledSpec)
    rename { "epistola-contract.yaml" }
    into(layout.buildDirectory.dir("openapi-resource/openapi"))
}

// Writes the contract identity resources (from the bundled spec's info block) so
// ServerContractInfo can report them at runtime — mirrors the client build so both
// sides expose the same values the same way:
//   - epistola-contract-version.txt        ← info.version
//   - epistola-contract-min-compatible.txt ← info.x-min-compatible-version (the
//     compatibility floor; falls back to info.version if the extension is absent,
//     which yields a point range for specs that predate the floor).
val generateContractInfoResources by tasks.registering {
    description = "Writes the contract version + compatibility floor to resource files for ServerContractInfo"
    inputs.file(bundledSpec)
    val outputDir = layout.buildDirectory.dir("generated-resources")
    outputs.dir(outputDir)

    @Suppress("UNCHECKED_CAST")
    doLast {
        val yaml = org.yaml.snakeyaml.Yaml()
        val spec = yaml.load<Map<String, Any>>(bundledSpec.readText()) as Map<String, Any>
        val info = spec["info"] as Map<String, Any>
        val version = info["version"] as String
        val minCompatible = (info["x-min-compatible-version"] as String?) ?: version
        val dir = outputDir.get()
        fun write(name: String, value: String) {
            val outFile = dir.file(name).asFile
            outFile.parentFile.mkdirs()
            outFile.writeText(value)
            logger.lifecycle("Wrote $value → ${outFile.relativeTo(project.projectDir)}")
        }
        write("epistola-contract-version.txt", version)
        write("epistola-contract-min-compatible.txt", minCompatible)
    }
}

tasks.processResources {
    dependsOn(copyOpenApiSpec, generateContractInfoResources)
}

tasks.compileKotlin {
    dependsOn(tasks.openApiGenerate)
}

dependencies {
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
    dependsOn(tasks.openApiGenerate, copyOpenApiSpec, generateContractInfoResources)
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

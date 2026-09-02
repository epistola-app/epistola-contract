// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.maven.publish)
    `java-library`
}

// Sets group + version from the OpenAPI spec (shared across builds)
apply(from = "$rootDir/../../build-logic/contract-version.gradle.kts")

description = "Epistola API Client for Jakarta EE application servers (MicroProfile Rest Client)"

repositories {
    mavenCentral()
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
        """.trimIndent(),
    )
}

// --- Generated API + model sources (MicroProfile Rest Client interfaces, JSON-B models) ---
openApiGenerate {
    generatorName.set("java")
    inputSpec.set(bundledSpec.absolutePath)
    outputDir.set(generatedDir.map { it.asFile.absolutePath })
    // Wipe first: the generator only ever adds files, so a schema removed from the spec would
    // otherwise keep compiling from a stale generated source.
    cleanupOutput.set(true)
    packageName.set("app.epistola.client.jakarta")
    apiPackage.set("app.epistola.client.jakarta.api")
    modelPackage.set("app.epistola.client.jakarta.model")
    // Generate the APIs, the models and exactly one supporting file. Maven scaffolding, docs
    // and the generator's placeholder tests are replaced by this Gradle build and the
    // hand-written tests. ApiExceptionMapper is deliberately absent: src/main/java owns it,
    // because it parses RFC 9457 problem bodies instead of wrapping the raw Response.
    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "supportingFiles" to "ApiException.java",
            "apiTests" to "false",
            "modelTests" to "false",
            "apiDocs" to "false",
            "modelDocs" to "false",
        ),
    )
    configOptions.set(
        mapOf(
            "library" to "microprofile",
            "serializationLibrary" to "jsonb",
            "useJakartaEe" to "true",
            "dateLibrary" to "java8",
            // ApiException extends RuntimeException, matching the other clients' unchecked
            // failure model. The interfaces still declare it for documentation value.
            "useRuntimeException" to "true",
            // The generator wants the *spec* level ("3.0"), not the artifact version ("3.0.1"),
            // so derive it from the catalog rather than pinning a second number that can drift.
            "microprofileRestClientVersion" to libs.versions.microprofile.rest.client.get()
                .split(".").take(2).joinToString("."),
            // Every generated interface carries @RegisterProvider(ApiExceptionMapper.class), so
            // the hand-written mapper applies in CDI and programmatic use alike, with nothing
            // for the consumer to register.
            "microprofileRegisterExceptionMapper" to "true",
            // ...but it must not become a @Provider: a global mapper would also intercept the
            // consumer's own JAX-RS clients and resources.
            "microprofileGlobalExceptionMapper" to "false",
            "hideGenerationTimestamp" to "true",
            "openApiNullable" to "false",
        ),
    )
}

// The spec-walking these three tasks share with the Kotlin client lives in build-logic; only the
// emitted syntax differs, so only the emitted syntax is here.
apply(from = "$rootDir/../../build-logic/contract-spec-model.gradle.kts")

@Suppress("UNCHECKED_CAST")
val specModel = extra["epistolaSpecModel"] as (Map<String, Any>) -> Map<String, Any>

@Suppress("UNCHECKED_CAST")
fun readSpec(spec: File): Map<String, Any> =
    specModel(org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(spec.readText()))

// --- Problem-slug constants generated from the spec's x-problem-types registry ---
val generatedProblemSlugsDir = layout.buildDirectory.dir("generated-problem-slugs/src/main/java")

val generateProblemSlugs by tasks.registering {
    description = "Generates KnownProblemSlugs from the spec's x-problem-types extension"
    group = "openapi tools"

    inputs.file(bundledSpec)
    outputs.dir(generatedProblemSlugsDir)

    val outDir = generatedProblemSlugsDir
    val spec = bundledSpec

    @Suppress("UNCHECKED_CAST")
    doLast {
        val model = readSpec(spec)
        val base = model["problemTypeBase"] as String
        val types = model["problemTypes"] as List<Map<String, Any?>>

        val constants = types.joinToString("\n\n") { entry ->
            "    /** ${entry["status"]} — ${entry["description"]} */\n" +
                "    public static final String ${entry["constantName"]} = \"${entry["slug"]}\";"
        }
        val allSlugs = types.joinToString(",\n") { "            ${it["constantName"]}" }

        val extensionMembers = (model["problemExtensionMembers"] as Map<String, List<String>>)
            .entries
            .sortedBy { it.key }
            .flatMap { (schema, members) -> members.map { schema to it } }
            .joinToString("\n\n") { (schema, member) ->
                "    /** The {@code $member} extension member of {@code $schema}. */\n" +
                    "    static final String ${member.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()} = \"$member\";"
            }

        val outFile = outDir.get().file("app/epistola/client/jakarta/error/KnownProblemSlugs.java").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            """
            |// Generated from the bundled OpenAPI spec's x-problem-types extension — do not edit.
            |package app.epistola.client.jakarta.error;
            |
            |import java.util.Arrays;
            |import java.util.Collections;
            |import java.util.List;
            |
            |/**
            | * The canonical problem {@code type} slugs the Epistola API emits, from the contract's
            | * error-type registry (the spec's {@code x-problem-types} extension /
            | * {@code docs/error-types.md}).
            | *
            | * <p>These are convenience constants for {@code switch (e.getTypeSlug())}. The slug is
            | * deliberately a plain {@code String} (not an enum) so the API can introduce new problem
            | * types without forcing a client release — always keep a {@code default} branch.
            | */
            |public final class KnownProblemSlugs {
            |
            |    /** Base URI from the spec's x-problem-types registry; must equal {@link ProblemTypes#TYPE_BASE}. */
            |    public static final String GENERATED_PROBLEM_TYPE_BASE = "$base";
            |
            |$constants
            |
            |    /** Every slug in the registry, in declaration order. */
            |    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            |$allSlugs
            |    ));
            |
            |    private KnownProblemSlugs() {
            |    }
            |}
            """.trimMargin() + "\n",
        )

        val membersFile = outDir.get()
            .file("app/epistola/client/jakarta/error/ProblemExtensionMembers.java").asFile
        membersFile.writeText(
            """
            |// Generated from the bundled OpenAPI spec's problem schemas — do not edit.
            |package app.epistola.client.jakarta.error;
            |
            |/**
            | * The names of the members Epistola problem bodies carry on top of the RFC 9457 base,
            | * derived from the problem schemas the registry names.
            | *
            | * <p>The server writes them and this client reads them back out of the raw body by name,
            | * so both generate the names from the contract: a rename would otherwise make the
            | * extension silently vanish rather than fail.
            | */
            |final class ProblemExtensionMembers {
            |
            |$extensionMembers
            |
            |    private ProblemExtensionMembers() {
            |    }
            |}
            """.trimMargin() + "\n",
        )
        logger.lifecycle("Generated KnownProblemSlugs with ${types.size} slug(s) → ${outFile.relativeTo(project.projectDir)}")
    }
}

// --- Client-side validation helpers generated from the spec's schema constraints ---
val generatedValidationDir = layout.buildDirectory.dir("generated-validation/src/main/java")

val generateValidation by tasks.registering {
    description = "Generates ModelValidation.validate(...) helpers from OpenAPI schema constraints"
    group = "openapi tools"
    dependsOn(tasks.openApiGenerate)

    inputs.file(bundledSpec)
    outputs.dir(generatedValidationDir)

    val outDir = generatedValidationDir
    val spec = bundledSpec
    val modelDir = generatedDir.map { it.dir("src/main/java/app/epistola/client/jakarta/model") }

    @Suppress("UNCHECKED_CAST")
    doLast {
        val modelSources = modelDir.get().asFile

        fun escapeJava(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

        fun check(property: String, constraint: Map<String, Any?>): String = when (constraint["kind"]) {
            "length" -> {
                val min = constraint["min"] as Int?
                val max = constraint["max"] as Int?
                when {
                    min != null && max != null ->
                        """require(value.length() >= $min && value.length() <= $max, "$property: length must be between $min and $max");"""
                    min != null ->
                        """require(value.length() >= $min, "$property: length must be at least $min");"""
                    else ->
                        """require(value.length() <= $max, "$property: length must be at most $max");"""
                }
            }

            "pattern" -> {
                val pattern = escapeJava(constraint["pattern"] as String)
                """require(value.matches("$pattern"), "$property: must match pattern $pattern");"""
            }

            "range" -> {
                val min = constraint["min"] as Long?
                val max = constraint["max"] as Long?
                when {
                    min != null && max != null ->
                        """require(value.longValue() >= $min && value.longValue() <= $max, "$property: must be between $min and $max");"""
                    min != null ->
                        """require(value.longValue() >= $min, "$property: must be at least $min");"""
                    else ->
                        """require(value.longValue() <= $max, "$property: must be at most $max");"""
                }
            }

            "minItems" -> {
                val min = constraint["min"] as Int
                """require(value.size() >= $min, "$property: must have at least $min item(s)");"""
            }

            else -> throw GradleException("unhandled constraint kind: ${constraint["kind"]}")
        }

        val methods = (readSpec(spec)["constrainedSchemas"] as List<Map<String, Any?>>).map { schema ->
            val schemaName = schema["name"] as String
            if (!modelSources.resolve("$schemaName.java").exists()) {
                throw GradleException(
                    "schema '$schemaName' carries constraints but no generated model " +
                        "${modelSources.resolve("$schemaName.java")} exists — the generator no longer emits " +
                        "the class name the spec declares",
                )
            }

            val body = (schema["fields"] as List<Map<String, Any?>>).joinToString("\n") { field ->
                val property = field["property"] as String
                val getter = "get" + property.replaceFirstChar { it.uppercase() }
                val checks = (field["constraints"] as List<Map<String, Any?>>).map { check(property, it) }
                buildString {
                    append("        {\n")
                    append("            var value = model.$getter();\n")
                    if (field["nullable"] as Boolean) {
                        append("            if (value != null) {\n")
                        append(checks.joinToString("\n") { "                $it" })
                        append("\n            }\n")
                    } else {
                        // Required in the contract, but nullable in Java — say so, rather than
                        // letting the constraint check below throw a bare NullPointerException.
                        append("            require(value != null, \"$property: is required\");\n")
                        append(checks.joinToString("\n") { "            $it" })
                        append("\n")
                    }
                    append("        }")
                }
            }

            "    /** Validates every {@code $schemaName} constraint the contract declares. */\n" +
                "    public static $schemaName validate($schemaName model) {\n" +
                "$body\n" +
                "        return model;\n" +
                "    }"
        }

        val outFile = outDir.get().file("app/epistola/client/jakarta/validation/ModelValidation.java").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            buildString {
                appendLine("// Generated from the bundled OpenAPI spec's schema constraints — do not edit.")
                appendLine("package app.epistola.client.jakarta.validation;")
                appendLine()
                appendLine("import app.epistola.client.jakarta.model.*;")
                appendLine()
                appendLine("/**")
                appendLine(" * Fail-fast checks for the {@code minLength} / {@code maxLength} / {@code pattern} /")
                appendLine(" * {@code minimum} / {@code maximum} / {@code minItems} constraints the contract declares,")
                appendLine(" * so a malformed request is rejected locally instead of costing a round trip.")
                appendLine(" *")
                appendLine(" * <p>Each overload returns its argument, so it composes into a call chain:")
                appendLine(" * {@code api.createTenant(ModelValidation.validate(request))}.")
                appendLine(" */")
                appendLine("public final class ModelValidation {")
                appendLine()
                append(methods.joinToString("\n\n"))
                appendLine()
                appendLine()
                appendLine("    private static void require(boolean condition, String message) {")
                appendLine("        if (!condition) {")
                appendLine("            throw new IllegalArgumentException(message);")
                appendLine("        }")
                appendLine("    }")
                appendLine()
                appendLine("    private ModelValidation() {")
                appendLine("    }")
                appendLine("}")
            },
        )
        logger.lifecycle("Generated validation for ${methods.size} model(s) → ${outFile.relativeTo(project.projectDir)}")
    }
}

// --- Client-identity constants generated from the spec's x-client-identity extension ---
//
// The header name and the User-Agent product token are the wire contract this client writes and
// the server module parses. Generated on both sides, so they cannot disagree.
val generatedIdentityDir = layout.buildDirectory.dir("generated-identity/src/main/java")

val generateClientIdentityConstants by tasks.registering {
    description = "Generates the client-identity constants from the spec's x-client-identity extension"
    group = "openapi tools"

    inputs.file(bundledSpec)
    outputs.dir(generatedIdentityDir)

    val outDir = generatedIdentityDir
    val spec = bundledSpec

    @Suppress("UNCHECKED_CAST")
    doLast {
        val identity = readSpec(spec)["clientIdentity"] as Map<String, String>

        val outFile = outDir.get().file("app/epistola/client/jakarta/identity/ContractIdentity.java").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            """
            |// Generated from the bundled OpenAPI spec's x-client-identity extension — do not edit.
            |package app.epistola.client.jakarta.identity;
            |
            |/**
            | * The client-identity wire contract, from the spec's {@code x-client-identity} extension.
            | *
            | * <p>This client writes these headers and the Epistola server module parses them; both
            | * generate from this one registry, so the two halves cannot drift apart.
            | */
            |final class ContractIdentity {
            |
            |    /** Header carrying the caller's node identifier. */
            |    static final String NODE_ID_HEADER = "${identity["nodeIdHeader"]}";
            |
            |    /** The product token every Epistola client's {@code User-Agent} must lead with. */
            |    static final String CONTRACT_PRODUCT = "${identity["contractProduct"]}";
            |
            |    /** Separator between {@code User-Agent} product tokens. */
            |    static final String PRODUCT_SEPARATOR = "${identity["userAgentProductSeparator"]}";
            |
            |    /** Separator between a product name and its version. */
            |    static final String VERSION_SEPARATOR = "${identity["userAgentVersionSeparator"]}";
            |
            |    private ContractIdentity() {
            |    }
            |}
            """.trimMargin() + "\n",
        )
        logger.lifecycle("Generated ContractIdentity → ${outFile.relativeTo(project.projectDir)}")
    }
}

// --- Contract version resource, read at runtime by ClientIdentity for the User-Agent ---
val generatedResourcesDir = layout.buildDirectory.dir("generated-resources")

val generateContractVersionResource by tasks.registering {
    description = "Writes the contract version to a resource file for ClientIdentity"
    group = "openapi tools"
    inputs.file(bundledSpec)
    outputs.dir(generatedResourcesDir)

    val outDir = generatedResourcesDir
    val spec = bundledSpec

    doLast {
        val specVersion = readSpec(spec)["version"] as String
        val outFile = outDir.get().file("epistola-contract-version.txt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(specVersion)
        logger.lifecycle("Wrote contract version $specVersion → ${outFile.relativeTo(project.projectDir)}")
    }
}

sourceSets {
    main {
        java.srcDir(generatedDir.map { it.dir("src/main/java") })
        java.srcDir(generatedProblemSlugsDir)
        java.srcDir(generatedValidationDir)
        java.srcDir(generatedIdentityDir)
        resources.srcDir(generatedResourcesDir)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.toolchain.get().toInt()))
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Jakarta EE 10 servers run on Java 17; the client stays loadable there even though the
    // build toolchain is newer. Deliberately lower than the java-toolchain used elsewhere.
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.compileJava {
    dependsOn(generateProblemSlugs, generateValidation, generateClientIdentityConstants)
}

tasks.processResources {
    dependsOn(generateContractVersionResource)
}

dependencies {
    // Everything the application server provides. compileOnly on purpose: shipping any of
    // these would put a second copy of a container API (or an implementation) into the
    // consumer's WAR, which is the classloading hazard this client exists to avoid.
    // DependencyHygieneTest asserts the published artifact has no runtime dependencies at all.
    compileOnly(libs.jakarta.ws.rs.api)
    compileOnly(libs.jakarta.json.api)
    compileOnly(libs.jakarta.json.bind.api)
    compileOnly(libs.jakarta.annotation.api)
    compileOnly(libs.microprofile.rest.client.api)
    compileOnly(libs.microprofile.config.api)

    // Optional: only needed by TemplateSchemaValidator / ValidatingGenerationApi. Consumers
    // who want client-side JSON Schema validation add it themselves (see the README).
    compileOnly(libs.json.schema.validator)

    // Tests run against a real MicroProfile Rest Client implementation, so the generated
    // interfaces are exercised over the wire rather than merely compiled.
    testCompileOnly(libs.jakarta.annotation.api)
    testImplementation(libs.jakarta.ws.rs.api)
    testImplementation(libs.jakarta.json.api)
    testImplementation(libs.jakarta.json.bind.api)
    testImplementation(libs.microprofile.rest.client.api)
    testImplementation(libs.microprofile.config.api)
    testImplementation(libs.resteasy.client)
    testImplementation(libs.resteasy.json.binding.provider)
    testImplementation(libs.resteasy.microprofile.rest.client)
    testImplementation(libs.yasson)
    testImplementation(libs.smallrye.config)
    testImplementation(libs.parsson)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// --- Dependency hygiene ---
//
// The artifact must resolve with no javax.*, no Spring and no bundled JAX-RS implementation.
// This writes the resolved classpaths to a test resource; DependencyHygieneTest asserts on it,
// so the guarantee is a failing test rather than a reviewer reading a dependency tree.
val dependencyReportDir = layout.buildDirectory.dir("generated-test-resources")

val generateDependencyReport by tasks.registering {
    description = "Records the resolved compile/runtime classpaths for DependencyHygieneTest"
    group = "verification"

    val runtimeClasspath = configurations.named("runtimeClasspath")
    val compileClasspath = configurations.named("compileClasspath")
    val outDir = dependencyReportDir
    outputs.dir(outDir)

    doLast {
        fun coordinates(configuration: Configuration): List<String> = configuration.resolvedConfiguration
            .resolvedArtifacts
            .map { with(it.moduleVersion.id) { "$group:$name:$version" } }
            .sorted()
            .distinct()

        val outFile = outDir.get().file("dependency-report.txt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            buildString {
                appendLine("# Generated by the generateDependencyReport task — do not edit.")
                coordinates(runtimeClasspath.get()).forEach { appendLine("runtime\t$it") }
                coordinates(compileClasspath.get()).forEach { appendLine("compile\t$it") }
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

// --- Deployment smoke test against a real application server ---
//
// The smoke application is a separate source set rather than a string baked into the test: it has
// to compile against the published client the way a consumer's code does, and a WAR assembled from
// real class files is the only kind that proves anything about deployment.
val smokeApp: SourceSet by sourceSets.creating

dependencies {
    "smokeAppCompileOnly"(sourceSets.main.get().output)
    "smokeAppCompileOnly"(libs.jakarta.ws.rs.api)
    "smokeAppCompileOnly"(libs.jakarta.annotation.api)
    "smokeAppCompileOnly"(libs.jakarta.enterprise.cdi.api)
    "smokeAppCompileOnly"(libs.jakarta.inject.api)
    "smokeAppCompileOnly"(libs.microprofile.rest.client.api)
}

// Built as part of `check`, not of `classes`: the smoke application is also a check that the
// client's public API is usable from a plain Jakarta EE bean, so it should break the build when it
// is not — but it compiles *against* the main output, so it cannot be part of producing it.
tasks.check {
    dependsOn(smokeApp.classesTaskName)
}

// Opt in with `-PdeploymentTest`: it needs Docker and pulls a WildFly image, so it is not part
// of the default `check`. It deploys a WAR that @Injects a generated @RestClient interface into
// a running server — a jar that resolves is not a jar that deploys.
val deploymentTest by tasks.registering(Test::class) {
    description = "Deploys the client into a real WildFly instance (requires Docker; -PdeploymentTest)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("deployment")
    }
    dependsOn(tasks.jar, smokeApp.classesTaskName)
    systemProperty("epistola.client.jar", tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty(
        "epistola.smokeApp.classes",
        smokeApp.output.classesDirs.singleFile.absolutePath,
    )
    systemProperty(
        "epistola.smokeApp.resources",
        smokeApp.output.resourcesDir!!.absolutePath,
    )
    // providers.gradleProperty, not project.hasProperty: Gradle exposes tasks as project
    // properties, so hasProperty("deploymentTest") is true purely because this task exists.
    val optedIn = providers.gradleProperty("deploymentTest").isPresent
    onlyIf { optedIn }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("deployment")
    }
}

tasks.check {
    if (providers.gradleProperty("deploymentTest").isPresent) {
        dependsOn(deploymentTest)
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        encoding = "UTF-8"
    }
}

// The vanniktech plugin's jar tasks need the generated sources to exist first.
tasks.matching { it.name == "javadocJar" || it.name == "sourcesJar" || it.name == "plainJavadocJar" }.configureEach {
    dependsOn(generateProblemSlugs, generateValidation, generateClientIdentityConstants, generateContractVersionResource)
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
    configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))

    publishToMavenCentral(automaticRelease = true)

    // Only sign when GPG credentials are available (CI or release builds)
    if (project.findProperty("signing.keyId") != null || System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null) {
        signAllPublications()
    }

    coordinates(project.group.toString(), "client-jakarta", project.version.toString())

    pom {
        name.set("Epistola Jakarta EE Client")
        description.set(
            "Java client library for the Epistola API targeting Jakarta EE application servers, " +
                "using MicroProfile Rest Client and JSON-B",
        )
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

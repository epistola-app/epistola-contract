plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.maven.publish)
    `java-library`
}

// Version can be overridden via -Pversion=X (used for snapshots)
// Otherwise, calculated from OpenAPI spec version + patch version
val calculatedVersion: String = run {
    val specFile = file("$rootDir/../epistola-api.yaml")
    val apiVersion: String = if (specFile.exists()) {
        val versionRegex = Regex("""^\s*version:\s*["']?(\d+\.\d+)\.\d+["']?\s*$""", RegexOption.MULTILINE)
        val match = versionRegex.find(specFile.readText())
        match?.groupValues?.get(1) ?: "0.0"
    } else {
        "0.0"
    }
    val patchVersion: String = findProperty("patchVersion")?.toString() ?: "0"
    "$apiVersion.$patchVersion"
}

group = "app.epistola.contract"
version = findProperty("version")?.toString()?.takeIf { it != "unspecified" } ?: calculatedVersion
description = "Epistola API Server Interfaces for Kotlin/Spring"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
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

    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "supportingFiles" to "",
        ),
    )
}

sourceSets {
    main {
        kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
    }
}

tasks.compileKotlin {
    dependsOn(tasks.openApiGenerate)
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jakarta.validation.api)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.databind)

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
    dependsOn(tasks.openApiGenerate)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

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

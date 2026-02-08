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

sourceSets {
    main {
        kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
    }
}

tasks.compileKotlin {
    dependsOn(tasks.openApiGenerate)
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

// Configure vanniktech plugin's jar tasks to depend on openApiGenerate since sources are generated
tasks.matching { it.name == "plainJavadocJar" || it.name == "sourcesJar" }.configureEach {
    dependsOn(tasks.openApiGenerate)
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
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

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.openapi.generator)
    `java-library`
    `maven-publish`
}

val generatedDir = layout.buildDirectory.dir("generated")

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set("$rootDir/../spec/epistola-api.yaml")
    outputDir.set(generatedDir.map { it.asFile.absolutePath })
    packageName.set("io.epistola.client")
    apiPackage.set("io.epistola.client.api")
    modelPackage.set("io.epistola.client.model")
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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = rootProject.group.toString()
            artifactId = "client-kotlin-spring-restclient"
            version = rootProject.version.toString()
        }
    }
}

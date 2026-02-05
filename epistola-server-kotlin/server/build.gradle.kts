plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.openapi.generator)
    `java-library`
    `maven-publish`
}

val generatedDir = layout.buildDirectory.dir("generated")

openApiGenerate {
    generatorName.set("kotlin-spring")
    inputSpec.set("$rootDir/../spec/epistola-api.yaml")
    outputDir.set(generatedDir.map { it.asFile.absolutePath })
    packageName.set("io.epistola.server")
    apiPackage.set("io.epistola.server.api")
    modelPackage.set("io.epistola.server.model")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useTags" to "true",
            "documentationProvider" to "none",
            "useSpringBoot3" to "true",
            "enumPropertyNaming" to "UPPERCASE",
            "serializableModel" to "true",
            "useBeanValidation" to "true",
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
            artifactId = "epistola-server-kotlin"
            version = rootProject.version.toString()
        }
    }
}

import org.cyclonedx.model.Component

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.graalvm.native)
    id("org.cyclonedx.bom")
}

dependencies {
    implementation(project(":lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// SBOM generation
tasks.cyclonedxDirectBom {
    projectType = Component.Type.APPLICATION
    includeBomSerialNumber = true
    includeLicenseText = false
    jsonOutput = layout.buildDirectory.file("sbom/bom.json").get().asFile
}

// Convenience task for generating SBOM
tasks.register("generateSbom") {
    group = "verification"
    description = "Generate CycloneDX SBOM"
    dependsOn(tasks.cyclonedxDirectBom)
}

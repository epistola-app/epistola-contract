plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

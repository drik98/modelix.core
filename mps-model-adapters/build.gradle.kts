plugins {
    kotlin("jvm")
    `maven-publish`
}

val mpsVersion = project.findProperty("mps.version")?.toString().takeIf { !it.isNullOrBlank() } ?: "2020.3.6"

dependencies {
    api(project(":model-api"))

    compileOnly("com.jetbrains:mps-openapi:$mpsVersion")
    compileOnly("com.jetbrains:mps-core:$mpsVersion")
    compileOnly("com.jetbrains:mps-environment:$mpsVersion")

    implementation(kotlin("stdlib"))
    implementation(libs.kotlin.logging)
}

group = "org.modelix.mps"

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "model-adapters"
            from(components["kotlin"])
        }
    }
}
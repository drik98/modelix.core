plugins {
    `modelix-kotlin-jvm-with-junit`
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":model-client", "jvmRuntimeElements"))
    implementation(project(":bulk-model-sync-lib"))
    implementation(project(":mps-model-adapters"))
    implementation(libs.modelix.buildtools.gradle)
    implementation(libs.modelix.buildtools.lib)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    testImplementation(libs.kotest.assertions.coreJvm)
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    val modelSync by plugins.creating {
        id = "org.modelix.bulk-model-sync"
        implementationClass = "org.modelix.model.sync.bulk.gradle.ModelSyncGradlePlugin"
    }
}

val writeVersionFile by tasks.registering {
    val propertiesFile = projectDir.resolve("src/main/resources/modelix.core.version.properties")
    propertiesFile.parentFile.mkdirs()
    propertiesFile.writeText(
        """
        modelix.core.version=$version
        """.trimIndent(),
    )
}
tasks.named("processResources") {
    dependsOn(writeVersionFile)
}

tasks.test {
    setEnvironment("MODELIX_CORE_PATH" to rootDir.absolutePath)
    useJUnitPlatform()
}

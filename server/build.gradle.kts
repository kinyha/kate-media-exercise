import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.kotlin.serialization)
    application
}

application { mainClass.set("dev.kinyha.counters.server.MainKt") }

dependencies {
    implementation(project(":api-models"))
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.statusPages)
    implementation(libs.ktor.server.autoHeadResponse)
    implementation(libs.ktor.server.callId)
    implementation(libs.ktor.server.callLogging)
    implementation(libs.ktor.server.swagger)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logstash.encoder)
    testImplementation(project(":client"))
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.ktor.server.testHost)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.awaitility)
    testImplementation(libs.swagger.parser)
}

fun Test.tagged(tag: String) {
    val testSources = sourceSets.test.get()
    group = "verification"
    testClassesDirs = testSources.output.classesDirs
    classpath = testSources.runtimeClasspath
    useJUnitPlatform { includeTags(tag) }
}

tasks.test { useJUnitPlatform { excludeTags("integration", "e2e") } }

val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Runs repository and API tests against real PostgreSQL."
        tagged("integration")
        shouldRunAfter(tasks.test)
    }

val dockerImage =
    tasks.register<Exec>("dockerImage") {
        description = "Builds the runtime image from locally compiled distributions."
        dependsOn(tasks.installDist, ":client:installDist")
        workingDir(rootProject.projectDir)
        val docker =
            providers.environmentVariable("PATH").map { path ->
                path
                    .split(File.pathSeparator)
                    .map { File(it, "docker") }
                    .firstOrNull(File::canExecute)
                    ?.absolutePath ?: "docker"
            }
        commandLine(docker.get(), "build", "-f", "docker/Dockerfile.test", "-t", "katemedia-counters:test", ".")
    }

val e2eTest =
    tasks.register<Test>("e2eTest") {
        description = "Runs HTTPS and concurrency tests against two separate server containers."
        tagged("e2e")
        dependsOn(dockerImage)
        shouldRunAfter(integrationTest)
        systemProperty("project.root", rootProject.projectDir.absolutePath)
    }

tasks.check { dependsOn(integrationTest, e2eTest) }

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
}

subprojects {
    group = "dev.kinyha.counters"
    version = "1.0.0"

    // Plugins are declared by each module's own `plugins {}` block; react to them instead of
    // applying them across projects, so the shared configuration stays type-safe.
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions { allWarningsAsErrors.set(true) }
        }
        dependencies {
            "testImplementation"(platform(rootProject.libs.junit.bom))
            "testImplementation"(rootProject.libs.junit.jupiter)
            "testImplementation"(kotlin("test"))
            "testRuntimeOnly"(rootProject.libs.junit.platformLauncher)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed", "skipped")
                exceptionFormat = TestExceptionFormat.FULL
            }
            systemProperty("junit.jupiter.execution.timeout.default", "120 s")
            maxHeapSize = "768m"
        }
    }

    pluginManager.withPlugin("io.gitlab.arturbosch.detekt") {
        configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("config/detekt.yml"))
        }
    }
}

dependencies {
    kover(project(":api-models"))
    kover(project(":server"))
    kover(project(":client"))
}

tasks.register<Exec>("composeUp") {
    group = "application"
    description = "Builds and starts the local Compose stack, then waits until it is healthy."
    workingDir(rootProject.projectDir)
    commandLine("docker", "compose", "up", "--build", "-d", "--wait", "--wait-timeout", "180")
}

tasks.register<Exec>("composeStop") {
    group = "application"
    description = "Stops the local Compose stack without deleting its data."
    workingDir(rootProject.projectDir)
    commandLine("docker", "compose", "stop")
}

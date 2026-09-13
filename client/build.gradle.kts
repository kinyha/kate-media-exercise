plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    application
}

application { mainClass.set("dev.kinyha.counters.client.MainKt") }
dependencies {
    implementation(project(":api-models"))
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.contentNegotiation)
    implementation(libs.ktor.serialization.json)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.ktor.client.mock)
}

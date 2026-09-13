pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "katemedia-counters"
include("api-models", "server", "client")

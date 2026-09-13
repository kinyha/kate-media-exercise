package dev.kinyha.counters.server

import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import java.nio.file.Files
import java.security.KeyStore

fun main() {
    val config = ServerConfig.fromEnvironment()
    val keyStore =
        KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(config.keyStorePath).use { load(it, config.keyStorePassword.toCharArray()) }
        }
    DatabaseResources(config.database).use { resources ->
        resources.migrate()
        val service = CounterService(PostgresCounterRepository(resources.database))
        embeddedServer(
            Netty,
            applicationEnvironment {},
            {
                // start(wait = true) registers a shutdown hook that stops the engine with these settings.
                shutdownGracePeriod = 1_000
                shutdownTimeout = 15_000
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = "server",
                    keyStorePassword = { config.keyStorePassword.toCharArray() },
                    privateKeyPassword = { config.keyStorePassword.toCharArray() },
                ) {
                    port = config.port
                    host = "0.0.0.0"
                }
            },
        ) { counterModule(service) }.start(wait = true)
    }
}

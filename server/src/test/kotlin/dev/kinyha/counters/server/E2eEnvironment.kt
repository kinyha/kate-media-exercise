package dev.kinyha.counters.server

import com.github.dockerjava.api.model.ExposedPort
import dev.kinyha.counters.client.CounterClient
import dev.kinyha.counters.client.createHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility.await
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture

class AppContainer : GenericContainer<AppContainer>(DockerImageName.parse("katemedia-counters:test"))

class E2eEnvironment : AutoCloseable {
    val network: Network = Network.newNetwork()
    val postgres: PgContainer = PgContainer().withNetwork(network).withNetworkAliases("db")
    val certificates: Path = Path.of(System.getProperty("project.root"), "server/build/e2e-certificates")
    val servers: List<AppContainer> = listOf(AppContainer(), AppContainer())
    lateinit var http: HttpClient
    lateinit var clients: List<CounterClient>
    val docker = DockerClientFactory.instance().client()

    fun start() {
        createCertificates()
        postgres.start()
        servers.forEachIndexed { index, server ->
            server
                .withNetwork(network)
                .withNetworkAliases("server-${if (index == 0) "a" else "b"}")
                .withExposedPorts(8443)
                .withEnv("DB_URL", "jdbc:postgresql://db:5432/${postgres.databaseName}")
                .withEnv("DB_USER", postgres.username)
                .withEnv("DB_PASSWORD", postgres.password)
                .withEnv("DB_POOL_SIZE", "10")
                .withEnv("TLS_KEYSTORE_PATH", "/certs/server.p12")
                .withEnv("TLS_KEYSTORE_PASSWORD", "test-password")
                .withEnv("TLS_CA_CERT", "/certs/server.crt")
                .withEnv("API_BASE_URL", "https://localhost:8443")
                .withCopyFileToContainer(
                    MountableFile.forHostPath(certificates.resolve("server.p12"), 292),
                    "/certs/server.p12",
                ).withCopyFileToContainer(
                    MountableFile.forHostPath(certificates.resolve("server.crt"), 292),
                    "/certs/server.crt",
                ).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)))
        }
        CompletableFuture.allOf(*servers.map { CompletableFuture.runAsync { it.start() } }.toTypedArray()).join()
        http = createHttpClient(certificates.resolve("server.crt"))
        refreshClients()
        awaitReady()
    }

    fun url(index: Int): String {
        // Docker may reassign an ephemeral host port on restart; avoid Testcontainers' cached inspection.
        val bindings =
            docker
                .inspectContainerCmd(servers[index].containerId)
                .exec()
                .networkSettings.ports.bindings
        val port = requireNotNull(bindings[ExposedPort.tcp(8443)]).first().hostPortSpec
        return "https://${servers[index].host}:$port"
    }

    fun refreshClients() {
        clients = servers.indices.map { CounterClient(url(it), http) }
    }

    fun awaitReady() {
        await().atMost(Duration.ofSeconds(45)).ignoreExceptions().until {
            runBlocking { clients.all { it.healthy() } }
        }
    }

    fun saveLogs(label: String) {
        val directory = Path.of(System.getProperty("project.root"), "server/build/e2e-logs")
        Files.createDirectories(directory)
        servers.forEachIndexed { index, server ->
            if (server.containerId != null) {
                Files.writeString(directory.resolve("$label-server-$index.log"), server.logs)
            }
        }
        if (postgres.containerId != null) Files.writeString(directory.resolve("$label-postgres.log"), postgres.logs)
    }

    private fun createCertificates() {
        Files.createDirectories(certificates)
        Files.deleteIfExists(certificates.resolve("server.p12"))
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()

        fun execute(vararg args: String) {
            val process = ProcessBuilder(keytool, *args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
        }
        execute(
            "-genkeypair",
            "-alias",
            "server",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "2",
            "-storetype",
            "PKCS12",
            "-keystore",
            certificates.resolve("server.p12").toString(),
            "-storepass",
            "test-password",
            "-dname",
            "CN=localhost",
            "-ext",
            "SAN=dns:localhost,dns:server-a,dns:server-b,ip:127.0.0.1",
            "-noprompt",
        )
        execute(
            "-exportcert",
            "-rfc",
            "-alias",
            "server",
            "-keystore",
            certificates.resolve("server.p12").toString(),
            "-storepass",
            "test-password",
            "-file",
            certificates.resolve("server.crt").toString(),
        )
    }

    override fun close() {
        if (::http.isInitialized) http.close()
        servers.reversed().forEach { it.stop() }
        postgres.stop()
        network.close()
    }
}

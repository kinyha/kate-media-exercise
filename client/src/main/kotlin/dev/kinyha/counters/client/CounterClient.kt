package dev.kinyha.counters.client

import dev.kinyha.counters.api.ApiError
import dev.kinyha.counters.api.Counter
import dev.kinyha.counters.api.CreateCounterRequest
import dev.kinyha.counters.api.Health
import dev.kinyha.counters.api.strictJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

fun createHttpClient(caCertificate: Path? = null): HttpClient =
    HttpClient(CIO) {
        install(ContentNegotiation) { json(strictJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            socketTimeoutMillis = 20_000
            requestTimeoutMillis = 30_000
        }
        engine {
            maxConnectionsCount = 100
            endpoint {
                maxConnectionsPerRoute = 64
                connectAttempts = 1
            }
            if (caCertificate != null) {
                https { trustManager = certificateTrustManager(caCertificate) }
            }
        }
    }

private fun certificateTrustManager(path: Path): X509TrustManager {
    val certificate = Files.newInputStream(path).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
    val store =
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("counters", certificate)
        }
    return TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(store) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .single()
}

class ApiException(
    val status: Int,
    val error: ApiError,
) : RuntimeException("$status ${error.code}: ${error.message}")

class CounterClient(
    baseUrl: String,
    private val http: HttpClient,
) {
    private val base = baseUrl.trimEnd('/')

    init {
        require(Url(base).protocol == URLProtocol.HTTPS) { "An HTTPS base URL is required" }
    }

    suspend fun create(
        name: String,
        value: Int,
    ): Counter =
        http
            .post("$base/counters") {
                contentType(ContentType.Application.Json)
                setBody(CreateCounterRequest(name, value))
            }.checked()
            .body()

    suspend fun get(name: String): Counter = http.get(resource(name)).checked().body()

    suspend fun list(): List<Counter> = http.get("$base/counters").checked().body()

    suspend fun increment(name: String): Counter = http.post("${resource(name)}/increment").checked().body()

    suspend fun delete(name: String) {
        http.delete(resource(name)).checked()
    }

    suspend fun healthy(): Boolean {
        val response = http.get("$base/health/ready")
        return response.status.isSuccess() && response.body<Health>().status == "UP"
    }

    private fun resource(name: String): String = "$base/counters/${name.encodeURLPathPart()}"

    private suspend fun HttpResponse.checked(): HttpResponse {
        if (!status.isSuccess()) throw ApiException(status.value, body())
        return this
    }
}

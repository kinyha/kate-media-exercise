package dev.kinyha.counters.client

import dev.kinyha.counters.api.Counter
import dev.kinyha.counters.api.strictJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CounterClientTest {
    @Test
    fun `client preserves encoded names and decodes response`() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    assertEquals("/counters/a%20b%3F%25/increment", request.url.encodedPath)
                    assertEquals(HttpMethod.Post, request.method)
                    respond(
                        """{"name":"a b?%","value":2}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            HttpClient(engine) { install(ContentNegotiation) { json(strictJson) } }.use { http ->
                assertEquals(Counter("a b?%", 2), CounterClient("https://localhost", http).increment("a b?%"))
            }
        }

    @Test
    fun `server error is exposed and non-idempotent operation is not retried`() =
        runBlocking {
            var attempts = 0
            val engine =
                MockEngine {
                    attempts++
                    respond(
                        """{"code":"DATABASE_UNAVAILABLE","message":"Database temporarily unavailable"}""",
                        HttpStatusCode.ServiceUnavailable,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            HttpClient(engine) { install(ContentNegotiation) { json(strictJson) } }.use { http ->
                val exception =
                    assertFailsWith<ApiException> { CounterClient("https://localhost", http).increment("x") }
                assertEquals(503, exception.status)
                assertEquals("DATABASE_UNAVAILABLE", exception.error.code)
                assertEquals(1, attempts)
            }
        }

    @Test
    fun `plaintext base URL is rejected`() {
        createHttpClient().use { http ->
            assertFailsWith<IllegalArgumentException> { CounterClient("http://localhost", http) }
        }
    }

    @Test
    fun `invalid CLI arguments fail before any request`(): Unit =
        runBlocking {
            val engine = MockEngine { error("No HTTP call expected") }
            HttpClient(engine).use { http ->
                val client = CounterClient("https://localhost", http)
                for (args in listOf(listOf("create", "x", "no"), listOf("get"), listOf("list", "extra"))) {
                    assertFailsWith<IllegalArgumentException> { execute(args, client) }
                }
                assertFailsWith<IllegalStateException> { execute(emptyList(), client) }
            }
        }
}

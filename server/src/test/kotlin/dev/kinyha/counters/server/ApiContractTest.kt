package dev.kinyha.counters.server

import dev.kinyha.counters.api.ApiError
import dev.kinyha.counters.api.Counter
import dev.kinyha.counters.api.CreateCounterRequest
import dev.kinyha.counters.api.strictJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiContractTest : PostgresFixture() {
    @Test
    fun `complete lifecycle and REST response contract`() =
        withApi { api ->
            assertEquals(emptyList(), api.get("/counters").body<List<Counter>>())
            val created = api.createCounter("views", -1)
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("/counters/views", created.headers[HttpHeaders.Location])
            assertNotNull(created.headers["X-Request-ID"])
            assertEquals(Counter("views", -1), created.body())
            assertEquals(Counter("views", -1), api.get("/counters/views").body())
            assertEquals(Counter("views", 0), api.post("/counters/views/increment").body())
            assertEquals(listOf(Counter("views", 0)), api.get("/counters").body())
            val deleted = api.delete("/counters/views")
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertEquals("", deleted.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, api.delete("/counters/views").status)
            assertEquals(HttpStatusCode.NotFound, api.get("/counters/views").status)
            assertEquals(HttpStatusCode.NotFound, api.post("/counters/views/increment").status)
        }

    @Test
    fun `duplicate does not overwrite existing value`() =
        withApi { api ->
            assertEquals(HttpStatusCode.Created, api.createCounter("same", 42).status)
            val duplicate = api.createCounter("same", 99)
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertEquals("COUNTER_ALREADY_EXISTS", duplicate.body<ApiError>().code)
            assertEquals(42, api.get("/counters/same").body<Counter>().value)
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{", "{}", "null", "[]", "{\"name\":\"x\"}", "{\"value\":1}",
            "{\"name\":1,\"value\":1}", "{\"name\":\"x\",\"value\":\"1\"}",
            "{\"name\":\"x\",\"value\":1.5}", "{\"name\":\"x\",\"value\":2147483648}",
            "{\"name\":\"x\",\"value\":-2147483649}", "{\"name\":\"x\",\"value\":null}",
            "{\"name\":\"x\",\"value\":1,\"extra\":true}",
        ],
    )
    fun `invalid JSON payloads return a stable error`(payload: String) =
        withApi { api ->
            val response =
                api.post("/counters") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals(ApiError("INVALID_REQUEST", "Invalid request"), response.body())
            assertEquals(emptyList(), api.get("/counters").body<List<Counter>>())
        }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\t", ".", "..", "a/b", "a\nb", "a\u0000b"])
    fun `invalid names are rejected`(name: String) =
        withApi { api ->
            assertEquals(HttpStatusCode.BadRequest, api.createCounter(name, 1).status)
        }

    @Test
    fun `names preserve Unicode spaces symbols and case with usable Location`() =
        withApi { api ->
            val names = listOf("Просмотры 👀", "x y?%#+", "Case", "case", " x ", "a".repeat(255), "👀".repeat(255))
            for (name in names) {
                val created = api.createCounter(name, Int.MIN_VALUE)
                assertEquals(HttpStatusCode.Created, created.status)
                val location = assertNotNull(created.headers[HttpHeaders.Location])
                assertEquals(Counter(name, Int.MIN_VALUE), api.get(location).body())
            }
            val listed = api.get("/counters").body<List<Counter>>()
            assertEquals(names.toSet(), listed.map { it.name }.toSet())
            assertEquals(HttpStatusCode.BadRequest, api.createCounter("a".repeat(256), 0).status)
        }

    @Test
    fun `list is ordered by name and overflow preserves the maximum value`() =
        withApi { api ->
            for (name in listOf("z", "a")) api.createCounter(name, Int.MAX_VALUE)
            assertEquals(listOf("a", "z"), api.get("/counters").body<List<Counter>>().map { it.name })
            val overflow = api.post("/counters/a/increment")
            assertEquals(HttpStatusCode.Conflict, overflow.status)
            assertEquals("COUNTER_OVERFLOW", overflow.body<ApiError>().code)
            assertEquals(Int.MAX_VALUE, api.get("/counters/a").body<Counter>().value)
        }

    @Test
    fun `health endpoints report liveness and readiness`() =
        withApi { api ->
            assertEquals(HttpStatusCode.OK, api.get("/health/live").status)
            assertEquals(HttpStatusCode.OK, api.get("/health/ready").status)
        }

    @Test
    fun `unknown routes methods and media types are reported as protocol errors`() =
        withApi { api ->
            api.createCounter("a", 0)
            assertEquals(HttpStatusCode.NotFound, api.get("/missing").status)
            val head = api.head("/counters")
            assertEquals(HttpStatusCode.OK, head.status)
            assertEquals("", head.bodyAsText())
            assertEquals(HttpStatusCode.OK, api.head("/counters/a").status)
            assertEquals(HttpStatusCode.NotFound, api.head("/counters/missing").status)
            val notAllowed = api.put("/counters/a")
            assertEquals(HttpStatusCode.MethodNotAllowed, notAllowed.status)
            assertEquals("GET, HEAD, DELETE", notAllowed.headers[HttpHeaders.Allow])
            val wrongMediaType =
                api.post("/counters") {
                    contentType(ContentType.Text.Plain)
                    setBody("hello")
                }
            assertEquals(HttpStatusCode.UnsupportedMediaType, wrongMediaType.status)
            assertEquals("UNSUPPORTED_MEDIA_TYPE", wrongMediaType.body<ApiError>().code)
        }

    @Test
    fun `specification and Swagger UI are served`() =
        withApi { api ->
            assertTrue(api.get("/openapi.yaml").bodyAsText().contains("openapi: 3.0.3"))
            assertTrue(api.get("/swagger").bodyAsText().contains("SwaggerUIBundle"))
        }

    private fun withApi(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { counterModule(service) }
            block(createClient { install(ContentNegotiation) { json(strictJson) } })
        }

    private suspend fun HttpClient.createCounter(
        name: String,
        value: Int,
    ): HttpResponse =
        post("/counters") {
            contentType(ContentType.Application.Json)
            setBody(CreateCounterRequest(name, value))
        }
}

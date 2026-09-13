package dev.kinyha.counters.server

import dev.kinyha.counters.api.Counter
import dev.kinyha.counters.client.ApiException
import dev.kinyha.counters.client.createHttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencyE2eTest {
    private val environment = E2eEnvironment()

    @BeforeAll
    @Timeout(180)
    fun startServers() {
        try {
            environment.start()
        } catch (exception: Exception) {
            environment.saveLogs("startup-failure")
            environment.close()
            throw exception
        }
    }

    @AfterEach
    fun captureLogs(info: TestInfo) {
        val method = info.testMethod.orElseThrow().name
        environment.saveLogs(method.replace(Regex("[^A-Za-z0-9]"), "_"))
    }

    @AfterAll
    fun stopServers() = environment.close()

    @ParameterizedTest(name = "1000 increments; workers={0}; processes={1}")
    @CsvSource("1,1", "8,1", "32,1", "1,2", "8,2", "32,2")
    fun `every increment is committed exactly once with a unique returned value`(
        workers: Int,
        processes: Int,
    ) = runBlocking {
        val name = uniqueName()
        val initial = -100
        environment.clients[0].create(name, initial)
        val results =
            parallel(1_000, workers) { index ->
                environment.clients[index % processes].increment(name).value
            }
        assertEquals((initial + 1..initial + 1_000).toList(), results.sorted())
        assertEquals(initial + 1_000, environment.clients[1].get(name).value)
    }

    @Test
    fun `one of 100 concurrent creates wins and its value is preserved`() =
        runBlocking {
            val name = uniqueName()
            val outcomes =
                parallel(100, 32) { index ->
                    try {
                        environment.clients[index % 2].create(name, index)
                    } catch (exception: ApiException) {
                        assertEquals(409, exception.status)
                        assertEquals("COUNTER_ALREADY_EXISTS", exception.error.code)
                        null
                    }
                }
            val winner = outcomes.filterNotNull().single()
            assertEquals(winner, environment.clients[1].get(name))
            assertEquals(1, environment.clients[0].list().count { it.name == name })
        }

    @Test
    fun `independent counters do not interfere`() =
        runBlocking {
            val names = List(8) { uniqueName() }
            names.forEach { environment.clients[0].create(it, 0) }
            val results =
                parallel(800, 32) { index ->
                    environment.clients[index % 2].increment(names[index % names.size])
                }
            for (name in names) {
                assertEquals((1..100).toList(), results.filter { it.name == name }.map { it.value }.sorted())
                assertEquals(100, environment.clients[1].get(name).value)
            }
        }

    @Test
    fun `only one request crosses the last available Int step`() =
        runBlocking {
            val name = uniqueName()
            environment.clients[0].create(name, Int.MAX_VALUE - 1)
            val results =
                parallel(100, 32) { index ->
                    try {
                        environment.clients[index % 2].increment(name).value
                    } catch (exception: ApiException) {
                        assertEquals(409, exception.status)
                        assertEquals("COUNTER_OVERFLOW", exception.error.code)
                        null
                    }
                }
            assertEquals(listOf(Int.MAX_VALUE), results.filterNotNull())
            assertEquals(Int.MAX_VALUE, environment.clients[1].get(name).value)
        }

    @Test
    fun `delete racing increments never resurrects a counter`() =
        runBlocking {
            repeat(10) {
                val name = uniqueName()
                environment.clients[0].create(name, 0)
                val results =
                    parallel(33, 33) { index ->
                        if (index == 0) {
                            environment.clients[0].delete(name)
                            null
                        } else {
                            try {
                                environment.clients[index % 2].increment(name).value
                            } catch (exception: ApiException) {
                                assertEquals(404, exception.status)
                                null
                            }
                        }
                    }.filterNotNull()
                assertEquals((1..results.size).toList(), results.sorted())
                val failure =
                    assertFailsWith<ApiException> { runBlocking { environment.clients[1].get(name) } }
                assertEquals(404, failure.status)
            }
        }

    @Test
    fun `confirmed writes survive restarting application and database processes`() =
        runBlocking {
            val name = uniqueName()
            environment.clients[0].create(name, 40)
            environment.clients[1].increment(name)
            environment.servers.forEach {
                environment.docker
                    .restartContainerCmd(it.containerId)
                    .withTimeout(20)
                    .exec()
            }
            environment.refreshClients()
            environment.awaitReady()
            assertEquals(Counter(name, 41), environment.clients[0].get(name))
            environment.docker
                .restartContainerCmd(environment.postgres.containerId)
                .withTimeout(10)
                .exec()
            environment.awaitReady()
            assertEquals(Counter(name, 41), environment.clients[1].get(name))
        }

    @Test
    fun `database outage affects readiness not liveness and recovers`() =
        runBlocking {
            environment.docker
                .stopContainerCmd(environment.postgres.containerId)
                .withTimeout(10)
                .exec()
            try {
                assertEquals(HttpStatusCode.OK, environment.http.get("${environment.url(0)}/health/live").status)
                assertFalse(environment.clients[0].healthy())
                val failure =
                    assertFailsWith<ApiException> { runBlocking { environment.clients[1].list() } }
                assertEquals(503, failure.status)
            } finally {
                environment.docker.startContainerCmd(environment.postgres.containerId).exec()
                environment.awaitReady()
            }
            assertTrue(environment.clients[1].healthy())
        }

    @Test
    fun `TLS rejects untrusted certificate and CLI completes its demo`() =
        runBlocking {
            createHttpClient().use { untrusted ->
                val failure = runCatching { untrusted.get("${environment.url(0)}/health/live") }.exceptionOrNull()
                assertNotNull(failure, "Untrusted self-signed certificate was accepted")
                assertTrue(
                    generateSequence(failure) { it.cause }.any {
                        it is javax.net.ssl.SSLException ||
                            it is java.security.cert.CertificateException ||
                            it is java.security.cert.CertPathBuilderException
                    },
                    "Expected certificate rejection, got $failure",
                )
            }
            val demo = environment.servers[0].execInContainer("/app/client/bin/client", "demo")
            assertEquals(0, demo.exitCode, demo.stderr)
            assertTrue(demo.stdout.contains("INCREMENT Counter("))
            assertTrue(demo.stdout.contains("value=42"))
            assertTrue(demo.stdout.contains("DELETE demo-"))
            val address =
                environment.docker
                    .inspectContainerCmd(environment.servers[0].containerId)
                    .exec()
                    .networkSettings.networks.values
                    .first()
                    .ipAddress
            val wrongHost =
                environment.servers[0].execInContainer(
                    "env",
                    "API_BASE_URL=https://$address:8443",
                    "/app/client/bin/client",
                    "health",
                )
            assertEquals(1, wrongHost.exitCode)
            assertTrue(wrongHost.stderr.contains("certificate", ignoreCase = true), wrongHost.stderr)
            val invalid = environment.servers[0].execInContainer("/app/client/bin/client", "create", "bad", "oops")
            assertEquals(1, invalid.exitCode)
            assertTrue(
                environment.http
                    .get("${environment.url(0)}/swagger")
                    .bodyAsText()
                    .contains("SwaggerUIBundle"),
            )
        }

    private fun uniqueName(): String = "test-${UUID.randomUUID()}"

    private suspend fun <T> parallel(
        count: Int,
        workers: Int,
        action: suspend (Int) -> T,
    ): List<T> =
        coroutineScope {
            val start = CompletableDeferred<Unit>()
            val ready = AtomicInteger()
            val allReady = CompletableDeferred<Unit>()
            val tasks =
                (0 until workers).map { worker ->
                    async(Dispatchers.IO) {
                        if (ready.incrementAndGet() == workers) allReady.complete(Unit)
                        start.await()
                        (worker until count step workers).map { action(it) }
                    }
                }
            allReady.await()
            start.complete(Unit)
            tasks.awaitAll().flatten()
        }
}

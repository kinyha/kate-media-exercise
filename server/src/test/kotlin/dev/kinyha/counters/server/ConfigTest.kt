package dev.kinyha.counters.server

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConfigTest {
    @TempDir
    lateinit var directory: Path

    private fun environment(): Map<String, String> =
        mapOf(
            "DB_URL" to "jdbc:postgresql://localhost/counters",
            "DB_USER" to "counters",
            "DB_PASSWORD" to "private-db-password",
            "TLS_KEYSTORE_PATH" to Files.writeString(directory.resolve("test.p12"), "readable").toString(),
            "TLS_KEYSTORE_PASSWORD" to "private-tls-password",
        )

    @Test
    fun `defaults are bounded and credentials are redacted`() {
        val config = ServerConfig.fromEnvironment(environment())
        assertEquals(8443, config.port)
        assertEquals(10, config.database.poolSize)
        assertFalse(config.toString().contains("private-db-password"))
        assertFalse(config.toString().contains("private-tls-password"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["DB_URL", "DB_USER", "DB_PASSWORD", "TLS_KEYSTORE_PATH", "TLS_KEYSTORE_PASSWORD"])
    fun `required configuration fails fast`(key: String) {
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(environment() - key) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "101", "invalid", "-1"])
    fun `invalid pool size is rejected`(value: String) {
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(environment() + ("DB_POOL_SIZE" to value))
        }
    }

    @Test
    fun `invalid database URL port timeout and keystore fail fast`() {
        val invalid =
            mapOf(
                "DB_URL" to "jdbc:h2:mem:test",
                "HTTPS_PORT" to "65536",
                "DB_CONNECTION_TIMEOUT_MS" to "1",
                "DB_STATEMENT_TIMEOUT_MS" to "0",
                "TLS_KEYSTORE_PATH" to directory.resolve("missing").toString(),
            )
        for ((key, value) in invalid) {
            assertFailsWith<IllegalArgumentException>(key) {
                ServerConfig.fromEnvironment(environment() + (key to value))
            }
        }
    }
}

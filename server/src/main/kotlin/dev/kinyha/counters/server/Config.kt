package dev.kinyha.counters.server

import java.nio.file.Files
import java.nio.file.Path

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val poolSize: Int = 10,
    val connectionTimeoutMs: Int = 3_000,
    val statementTimeoutMs: Int = 10_000,
) {
    // without pass
    override fun toString(): String = "DatabaseConfig(user=$user, poolSize=$poolSize, credentials=<redacted>)"
}

data class ServerConfig(
    val database: DatabaseConfig,
    val keyStorePath: Path,
    val keyStorePassword: String,
    val port: Int = 8443,
) {
    override fun toString(): String = "ServerConfig(port=$port, database=$database, TLS credentials=<redacted>)"

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            // helpers
            fun required(key: String): String =
                requireNotNull(env[key]?.takeIf { it.isNotBlank() }) { "$key must be set" }

            fun number(
                key: String,
                default: Int,
                range: IntRange,
            ): Int {
                val value = env[key]?.let { requireNotNull(it.toIntOrNull()) { "$key must be an integer" } } ?: default
                require(value in range) { "$key must be in $range" }
                return value
            }

            val url = required("DB_URL")
            require(url.startsWith("jdbc:postgresql:")) { "DB_URL must be a PostgreSQL JDBC URL" }
            val keyStorePath = Path.of(required("TLS_KEYSTORE_PATH"))
            require(Files.isReadable(keyStorePath)) { "TLS keystore must be readable" }
            return ServerConfig(
                database =
                    DatabaseConfig(
                        url = url,
                        user = required("DB_USER"),
                        password = required("DB_PASSWORD"),
                        poolSize = number("DB_POOL_SIZE", 10, 1..100),
                        connectionTimeoutMs = number("DB_CONNECTION_TIMEOUT_MS", 3_000, 250..60_000),
                        statementTimeoutMs = number("DB_STATEMENT_TIMEOUT_MS", 10_000, 100..60_000),
                    ),
                keyStorePath = keyStorePath,
                keyStorePassword = required("TLS_KEYSTORE_PASSWORD"),
                port = number("HTTPS_PORT", 8443, 1..65535),
            )
        }
    }
}

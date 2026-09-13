package dev.kinyha.counters.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

class DatabaseResources(
    config: DatabaseConfig,
) : AutoCloseable {
    val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = config.poolSize
                minimumIdle = 1
                connectionTimeout = config.connectionTimeoutMs.toLong()
                validationTimeout = minOf(1_000, config.connectionTimeoutMs).toLong()
                initializationFailTimeout = 10_000
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                addDataSourceProperty("connectTimeout", "3")
                addDataSourceProperty("socketTimeout", socketTimeoutSeconds(config).toString())
                addDataSourceProperty("ApplicationName", "katemedia-counters")
                addDataSourceProperty("options", "-c statement_timeout=${config.statementTimeoutMs}")
            },
        )
    val database: Database = Database.connect(dataSource)

    fun migrate() {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    override fun close() = dataSource.close()

    private companion object {
        // connectTimeout < statement_timeout < socketTimeout
        const val SOCKET_TIMEOUT_HEADROOM_SECONDS = 5

        fun socketTimeoutSeconds(config: DatabaseConfig): Int =
            (config.statementTimeoutMs + 999) / 1_000 + SOCKET_TIMEOUT_HEADROOM_SECONDS
    }
}

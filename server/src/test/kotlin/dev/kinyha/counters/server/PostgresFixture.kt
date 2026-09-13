package dev.kinyha.counters.server

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID

class PgContainer : PostgreSQLContainer<PgContainer>("postgres:17.11-alpine")

object TestPostgres {
    val container: PgContainer = PgContainer().apply { start() }
}

@Tag("integration")
abstract class PostgresFixture {
    protected lateinit var resources: DatabaseResources
    protected lateinit var repository: PostgresCounterRepository
    protected lateinit var service: CounterService
    protected lateinit var config: DatabaseConfig
    private lateinit var schema: String

    @BeforeEach
    fun prepareDatabase() {
        val pg = TestPostgres.container
        schema = "test_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        config = DatabaseConfig("${pg.jdbcUrl}&currentSchema=$schema", pg.username, pg.password)
        resources = DatabaseResources(config)
        resources.migrate()
        repository = PostgresCounterRepository(resources.database)
        service = CounterService(repository)
    }

    @AfterEach
    fun cleanDatabase() {
        if (::resources.isInitialized) resources.close()
        if (::schema.isInitialized) {
            val pg = TestPostgres.container
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}

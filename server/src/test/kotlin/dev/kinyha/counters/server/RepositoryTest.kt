package dev.kinyha.counters.server

import dev.kinyha.counters.api.Counter
import dev.kinyha.counters.api.CreateCounterRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val WAITING_INCREMENTS =
    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
        "AND application_name = 'katemedia-counters' AND query LIKE '%UPDATE%RETURNING%'"

private const val COMMITTED_MIGRATIONS = "SELECT count(*) FROM flyway_schema_history WHERE success"

class RepositoryTest : PostgresFixture() {
    // dirty read check
    @Test
    fun `rollback releases row lock without publishing uncommitted value`() =
        runBlocking {
            repository.create(CreateCounterRequest("locked", 10))
            resources.dataSource.connection.use { blocker ->
                blocker.autoCommit = false
                blocker.update("UPDATE counters SET value = 100 WHERE name = 'locked'")
                assertEquals(10, repository.find("locked")?.value)
                val increment = async(Dispatchers.IO) { repository.increment("locked") }
                await().atMost(Duration.ofSeconds(5)).until { resources.scalar(WAITING_INCREMENTS) > 0 }
                assertFalse(increment.isCompleted)
                blocker.rollback()
                assertEquals(Counter("locked", 11), increment.await())
            }
            assertEquals(11, repository.find("locked")?.value)
        }

    @Test
    fun `statement timeout returns unavailable and does not change the value`() =
        runBlocking {
            repository.create(CreateCounterRequest("locked", 10))
            DatabaseResources(config.copy(statementTimeoutMs = 250)).use { shortResources ->
                val shortRepository = PostgresCounterRepository(shortResources.database)
                resources.dataSource.connection.use { blocker ->
                    blocker.autoCommit = false
                    blocker.update("UPDATE counters SET value = 20 WHERE name = 'locked'")
                    val failure =
                        assertFailsWith<CounterFailure> { runBlocking { shortRepository.increment("locked") } }
                    assertEquals(Failure.DATABASE_UNAVAILABLE, failure.failure)
                    blocker.rollback()
                }
            }
            assertEquals(10, repository.find("locked")?.value)
        }

    @Test
    fun `pool exhaustion is bounded and recovery is automatic`() =
        runBlocking {
            DatabaseResources(config.copy(poolSize = 1, connectionTimeoutMs = 250)).use { single ->
                val limited = PostgresCounterRepository(single.database)
                single.dataSource.connection.use {
                    assertFalse(limited.ready())
                    val failure = assertFailsWith<CounterFailure> { runBlocking { limited.list() } }
                    assertEquals(Failure.DATABASE_UNAVAILABLE, failure.failure)
                }
                assertTrue(limited.ready())
            }
        }

    @Test
    fun `readiness reports down instead of failing when the pool is gone`() =
        runBlocking {
            val closed = DatabaseResources(config).also { it.close() }
            assertFalse(PostgresCounterRepository(closed.database).ready())
        }

    @Test
    fun `migrations and a new database pool preserve committed values`() =
        runBlocking {
            repository.create(CreateCounterRequest("durable", 40))
            repository.increment("durable")
            resources.close()
            resources = DatabaseResources(config)
            resources.migrate()
            resources.migrate()
            assertEquals(Counter("durable", 41), PostgresCounterRepository(resources.database).find("durable"))
            assertEquals(1, resources.scalar(COMMITTED_MIGRATIONS))
        }

    private fun Connection.update(sql: String) = createStatement().use { it.executeUpdate(sql) }

    private fun DatabaseResources.scalar(sql: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use {
                    it.next()
                    it.getInt(1)
                }
            }
        }
}

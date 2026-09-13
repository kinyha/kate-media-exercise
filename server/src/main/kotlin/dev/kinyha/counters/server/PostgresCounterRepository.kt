package dev.kinyha.counters.server

import dev.kinyha.counters.api.Counter
import dev.kinyha.counters.api.CreateCounterRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.sql.SQLException
import java.sql.SQLTransientConnectionException

object Counters : Table("counters") {
    val name = varchar("name", MAX_NAME_LENGTH)
    val value = integer("value")
    override val primaryKey = PrimaryKey(name)
}

class PostgresCounterRepository(
    private val database: Database,
) : CounterRepository {
    override suspend fun create(request: CreateCounterRequest): Counter =
        query {
            Counters.insert {
                it[name] = request.name
                it[value] = request.value
            }
            Counter(request.name, request.value)
        }

    override suspend fun find(name: String): Counter? =
        query {
            Counters
                .selectAll()
                .where { Counters.name eq name }
                .singleOrNull()
                ?.toCounter()
        }

    override suspend fun list(): List<Counter> =
        query {
            Counters.selectAll().orderBy(Counters.name).map { it.toCounter() }
        }

    override suspend fun increment(name: String): Counter? =
        query {
            Counters
                .updateReturning(where = { Counters.name eq name }) {
                    it[value] = value + 1
                }.singleOrNull()
                ?.toCounter()
        }

    override suspend fun delete(name: String): Boolean =
        query {
            Counters.deleteWhere { Counters.name eq name } == 1
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun ready(): Boolean =
        try {
            query { exec("SELECT 1") { it.next() } == true }
        } catch (_: Exception) {
            false
        }

    private suspend fun <T> query(block: JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            try {
                transaction(database) {
                    // default in expose 3 attempts bad for increments
                    maxAttempts = 1
                    block()
                }
            } catch (exception: SQLException) {
                throw translate(exception)
            }
        }

    private fun translate(exception: SQLException): RuntimeException {
        val states =
            generateSequence<Throwable>(exception) { it.cause }
                .filterIsInstance<SQLException>()
                .mapNotNull { it.sqlState }
                .toSet()
        val unavailable =
            exception is SQLTransientConnectionException ||
                states.any { it.startsWith("08") || it in UNAVAILABLE_STATES }
        return when {
            "23505" in states -> CounterFailure(Failure.COUNTER_ALREADY_EXISTS)
            "22003" in states -> CounterFailure(Failure.COUNTER_OVERFLOW)
            unavailable -> CounterFailure(Failure.DATABASE_UNAVAILABLE)
            else -> IllegalStateException("Database operation failed", exception)
        }
    }

    private fun ResultRow.toCounter(): Counter = Counter(this[Counters.name], this[Counters.value])

    private companion object {
        val UNAVAILABLE_STATES = setOf("57014", "57P01", "57P02", "57P03", "53300")
    }
}

package dev.kinyha.counters.server

import dev.kinyha.counters.api.Counter
import dev.kinyha.counters.api.CreateCounterRequest

internal const val MAX_NAME_LENGTH = 255

enum class Failure(
    val status: Int,
    val publicMessage: String,
) {
    INVALID_REQUEST(400, "Invalid request"),
    COUNTER_NOT_FOUND(404, "Counter not found"),
    COUNTER_ALREADY_EXISTS(409, "Counter already exists"),
    COUNTER_OVERFLOW(409, "Counter has reached the maximum Int value"),
    DATABASE_UNAVAILABLE(503, "Database temporarily unavailable"),
}

class CounterFailure(
    val failure: Failure,
) : RuntimeException(failure.publicMessage)

interface CounterRepository {
    suspend fun create(request: CreateCounterRequest): Counter

    suspend fun find(name: String): Counter?

    suspend fun list(): List<Counter>

    suspend fun increment(name: String): Counter?

    suspend fun delete(name: String): Boolean

    suspend fun ready(): Boolean
}

class CounterService(
    private val repository: CounterRepository,
) {
    suspend fun create(request: CreateCounterRequest): Counter {
        validateName(request.name)
        return repository.create(request)
    }

    suspend fun get(name: String): Counter {
        validateName(name)
        return repository.find(name) ?: throw CounterFailure(Failure.COUNTER_NOT_FOUND)
    }

    suspend fun list(): List<Counter> = repository.list()

    suspend fun increment(name: String): Counter {
        validateName(name)
        return repository.increment(name) ?: throw CounterFailure(Failure.COUNTER_NOT_FOUND)
    }

    suspend fun delete(name: String) {
        validateName(name)
        if (!repository.delete(name)) throw CounterFailure(Failure.COUNTER_NOT_FOUND)
    }

    suspend fun ready(): Boolean = repository.ready()

    private fun validateName(name: String) {
        val valid =
            name.isNotBlank() &&
                name != "." &&
                name != ".." &&
                name.codePointCount(0, name.length) <= MAX_NAME_LENGTH &&
                name.none { it == '/' || it.isISOControl() }
        if (!valid) throw CounterFailure(Failure.INVALID_REQUEST)
    }
}

package dev.kinyha.counters.api

import kotlinx.serialization.Serializable

@Serializable
data class CreateCounterRequest(
    val name: String,
    @Serializable(with = StrictIntSerializer::class)
    val value: Int,
)

@Serializable
data class Counter(
    val name: String,
    val value: Int,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

@Serializable
data class Health(
    val status: String,
)

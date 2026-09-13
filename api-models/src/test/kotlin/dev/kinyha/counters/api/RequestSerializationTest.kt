package dev.kinyha.counters.api

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestSerializationTest {
    @ParameterizedTest
    @ValueSource(strings = ["1", "true", "null", "[]", "{}"])
    fun `name must be a JSON string`(name: String) {
        assertFailsWith<SerializationException> {
            strictJson.decodeFromString<CreateCounterRequest>("""{"name":$name,"value":1}""")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"1\"", "1.5", "true", "null", "[]", "{}", "2147483648", "-2147483649"])
    fun `value must be a JSON Int without coercion`(value: String) {
        assertFailsWith<SerializationException> {
            strictJson.decodeFromString<CreateCounterRequest>("""{"name":"x","value":$value}""")
        }
    }

    @Test
    fun `unknown fields are rejected`() {
        assertFailsWith<SerializationException> {
            strictJson.decodeFromString<CreateCounterRequest>("""{"name":"x","value":1,"extra":true}""")
        }
    }

    @Test
    fun `boundary integers and Unicode round trip`() {
        for (value in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)) {
            val request = CreateCounterRequest("Просмотры 👀", value)
            assertEquals(request, strictJson.decodeFromString(strictJson.encodeToString(request)))
        }
    }
}

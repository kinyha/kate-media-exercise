package dev.kinyha.counters.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.intOrNull

// убираем дефолт для парса имени 1 = "1"
val strictJson: Json = Json { isLenient = false }

// по спеке принимаем лишь integer
object StrictIntSerializer : JsonTransformingSerializer<Int>(Int.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonPrimitive || element.isString || element.intOrNull == null) {
            throw SerializationException("Expected a JSON 32-bit integer")
        }
        return element
    }
}

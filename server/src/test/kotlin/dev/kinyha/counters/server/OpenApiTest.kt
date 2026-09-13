package dev.kinyha.counters.server

import io.swagger.v3.parser.OpenAPIV3Parser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OpenApiTest {
    @Test
    fun `specification is valid and covers all public routes`() {
        val spec = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        val result = OpenAPIV3Parser().readContents(spec, null, null)
        assertEquals(emptyList(), result.messages)
        val api = assertNotNull(result.openAPI)
        assertEquals(
            setOf("/counters", "/counters/{name}", "/counters/{name}/increment", "/health/live", "/health/ready"),
            api.paths.keys,
        )
        assertEquals(7, api.paths.values.sumOf { it.readOperations().size })
        val counter = assertNotNull(api.components.schemas["Counter"])
        assertEquals(setOf("name", "value"), counter.required.toSet())
    }
}

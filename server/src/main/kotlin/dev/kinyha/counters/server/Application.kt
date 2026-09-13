package dev.kinyha.counters.server

import dev.kinyha.counters.api.ApiError
import dev.kinyha.counters.api.CreateCounterRequest
import dev.kinyha.counters.api.Health
import dev.kinyha.counters.api.strictJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import org.slf4j.event.Level
import java.util.UUID

fun Application.counterModule(service: CounterService) {
    installPlugins()
    val specification = requireNotNull(this::class.java.getResource("/openapi.yaml")).readText()
    routing {
        get("/health/live") { call.respond(Health("UP")) }
        get("/health/ready") {
            val ready = service.ready()
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                Health(if (ready) "UP" else "DOWN"),
            )
        }
        route("/counters") {
            post {
                val created = service.create(call.receive<CreateCounterRequest>())
                call.response.header(HttpHeaders.Location, "/counters/${created.name.encodeURLPathPart()}")
                call.respond(HttpStatusCode.Created, created)
            }
            get { call.respond(service.list()) }
            get("/{name}") { call.respond(service.get(call.parameters["name"]!!)) }
            post("/{name}/increment") { call.respond(service.increment(call.parameters["name"]!!)) }
            delete("/{name}") {
                service.delete(call.parameters["name"]!!)
                call.respond(HttpStatusCode.NoContent)
            }
            route("/{name}") { methodNotAllowed("GET, HEAD, DELETE") }
            route("/{name}/increment") { methodNotAllowed("POST") }
            methodNotAllowed("GET, HEAD, POST")
        }
        get("/openapi.yaml") { call.respondText(specification, ContentType.parse("application/yaml")) }
        swaggerUI(path = "swagger", swaggerFile = "openapi.yaml")
    }
}

private fun Application.installPlugins() {
    install(ContentNegotiation) { json(strictJson) }
    install(AutoHeadResponse)
    install(CallId) {
        generate { UUID.randomUUID().toString() }
        replyToHeader("X-Request-ID")
    }
    install(CallLogging) {
        level = Level.INFO
        mdc("request_id") { it.callId }
        mdc("method") { it.request.httpMethod.value }
        mdc("path") { it.request.path() }
        mdc("status") { it.statusCode }
        format { "request completed in ${it.processingTimeMillis()}ms" }
    }
    install(StatusPages) {
        exception<CounterFailure> { call, cause ->
            call.respond(HttpStatusCode.fromValue(cause.failure.status), cause.failure.toError())
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, Failure.INVALID_REQUEST.toError())
        }
        exception<CannotTransformContentToTypeException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, HttpStatusCode.UnsupportedMediaType.toError())
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            this@installPlugins.log.error("Unhandled request failure; request_id={}", call.callId, cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL_ERROR", "Internal server error"))
        }
        status(
            HttpStatusCode.NotFound,
            HttpStatusCode.MethodNotAllowed,
            HttpStatusCode.UnsupportedMediaType,
        ) { call, code ->
            call.respond(code, code.toError())
        }
    }
}

private fun Route.methodNotAllowed(allow: String) {
    handle {
        call.response.header(HttpHeaders.Allow, allow)
        call.respond(HttpStatusCode.MethodNotAllowed)
    }
}

private val ApplicationCall.statusCode: String? get() = response.status()?.value?.toString()

private fun Failure.toError(): ApiError = ApiError(name, publicMessage)

private fun HttpStatusCode.toError(): ApiError = ApiError(description.uppercase().replace(' ', '_'), description)

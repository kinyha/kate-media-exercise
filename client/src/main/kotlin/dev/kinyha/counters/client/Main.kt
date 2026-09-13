package dev.kinyha.counters.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.UUID
import kotlin.system.exitProcess

private val prettyJson = Json { prettyPrint = true }
private val commandArity =
    mapOf("create" to 3, "get" to 2, "increment" to 2, "delete" to 2, "list" to 1, "demo" to 1, "health" to 1)
private const val USAGE =
    "Usage: client create <name> <value> | get <name> | increment <name> | delete <name> | list | demo | health"

@Suppress("TooGenericExceptionCaught")
fun main(args: Array<String>) {
    try {
        runBlocking {
            val base = System.getenv("API_BASE_URL") ?: "https://localhost:8443"
            val certificate = System.getenv("TLS_CA_CERT")?.let(Path::of)
            createHttpClient(certificate).use { http ->
                execute(args.toList(), CounterClient(base, http))
            }
        }
    } catch (exception: Exception) {
        System.err.println(exception.message ?: exception.javaClass.simpleName)
        exitProcess(1)
    }
}

internal suspend fun execute(
    args: List<String>,
    client: CounterClient,
) {
    val command = args.firstOrNull() ?: error(USAGE)
    val arity = commandArity[command] ?: error(USAGE)
    require(args.size == arity) { USAGE }
    when (command) {
        "create" -> {
            val value = requireNotNull(args[2].toIntOrNull()) { "Value must be a 32-bit integer" }
            println(prettyJson.encodeToString(client.create(args[1], value)))
        }
        "get" -> println(prettyJson.encodeToString(client.get(args[1])))
        "increment" -> println(prettyJson.encodeToString(client.increment(args[1])))
        "delete" -> {
            client.delete(args[1])
            println("Deleted")
        }
        "list" -> println(prettyJson.encodeToString(client.list()))
        "demo" -> demo(client)
        "health" -> check(client.healthy()) { "Service is not ready" }
    }
}

private suspend fun demo(client: CounterClient) {
    val name = "demo-${UUID.randomUUID()}"
    println("CREATE ${client.create(name, 41)}")
    try {
        println("GET ${client.get(name)}")
        println("INCREMENT ${client.increment(name)}")
        println("LIST ${client.list()}")
    } finally {
        client.delete(name)
        println("DELETE $name")
    }
}

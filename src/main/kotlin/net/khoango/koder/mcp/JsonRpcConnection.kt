package net.khoango.koder.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** An error returned by an MCP server, a timeout, or a closed connection. */
class McpException(message: String, val code: Int? = null, val data: JsonElement? = null) : Exception(message)

class JsonRpcConnection(input: InputStream, private val output: OutputStream, scope: CoroutineScope) {

    private val reader = input.bufferedReader(Charsets.UTF_8)
    private val writeLock = Mutex()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    @Volatile
    private var closed = false

    init {
        scope.launch(Dispatchers.IO) { readLoop() }
    }

    /** Sends a request and waits for its result. Throws [McpException] for an error, a timeout, or a closed connection. */
    suspend fun request(method: String, params: JsonObject, timeout: Duration = 30.seconds): JsonElement {
        if (closed) throw McpException(CLOSED_MESSAGE)

        val id = nextId.getAndIncrement()
        val response = CompletableDeferred<JsonElement>()
        pending[id] = response
        try {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            })
            return withTimeoutOrNull(timeout) { response.await() }
                ?: throw McpException("The MCP server didn't answer '$method' within $timeout.")
        } finally {
            pending.remove(id)
        }
    }

    // notify and close: unchanged

    private suspend fun send(message: JsonObject) {
        val line = Json.encodeToString(JsonObject.serializer(), message) + "\n"
        writeLock.withLock {
            try {
                withContext(Dispatchers.IO) {
                    output.write(line.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (e: IOException) {
                throw McpException(CLOSED_MESSAGE)
            }
        }
    }

    private fun readLoop() {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                handle(message)
            }
        } catch (e: IOException) {
            // The stream was closed; handled in finally.
        } finally {
            closed = true
            val error = McpException(CLOSED_MESSAGE)
            pending.values.forEach { it.completeExceptionally(error) }
        }
    }

    private fun handle(message: JsonObject) {
        // Messages with a method are notifications (modern servers never send requests on stdio).
        // They're ignored for now: only responses to Koder's own requests matter.
        if (message["method"] != null) return
        handleResponse(message)
    }

    private fun handleResponse(message: JsonObject) {
        val id = (message["id"] as? JsonPrimitive)?.longOrNull ?: return
        val waiting = pending[id] ?: return

        val error = message["error"] as? JsonObject
        if (error != null) {
            waiting.completeExceptionally(
                McpException(
                    (error["message"] as? JsonPrimitive)?.content ?: "Unknown error",
                    code = (error["code"] as? JsonPrimitive)?.intOrNull,
                    data = error["data"],
                )
            )
        } else {
            waiting.complete(message["result"] ?: JsonNull)
        }
    }

    fun close() {
        runCatching { output.close() }
    }

    companion object {
        private const val CLOSED_MESSAGE = "The MCP server closed the connection."
    }
}
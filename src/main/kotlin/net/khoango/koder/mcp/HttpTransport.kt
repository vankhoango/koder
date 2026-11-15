package net.khoango.koder.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Reads Server-Sent Events from [lines] until one contains a JSON-RPC response (a message without "method"),
 * and returns that event's data. Notifications, comments, and other fields are skipped.
 */
internal fun readFinalSseMessage(lines: Iterator<String>): String {
    val data = StringBuilder()

    fun takeEvent(): String? {
        val event = data.toString()
        data.clear()
        val message = runCatching { Json.parseToJsonElement(event) as? JsonObject }.getOrNull()
        return if (message != null && message["method"] == null) event else null
    }

    for (line in lines) {
        when {
            line.isEmpty() -> if (data.isNotEmpty()) takeEvent()?.let { return it }
            line.startsWith(":") -> Unit   // a comment, such as a keep-alive
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
            else -> Unit   // other fields, such as "event:" or "id:"
        }
    }
    // The stream may end without a final empty line.
    if (data.isNotEmpty()) takeEvent()?.let { return it }
    throw McpException("The server's event stream ended without a response.")
}

/** The Streamable HTTP transport: each request is its own HTTP POST to the server's MCP endpoint. */
class HttpTransport(
    private val url: URI,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : McpTransport {

    private val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()
    private val nextId = AtomicLong(1)

    override suspend fun request(method: String, params: JsonObject, timeout: Duration): JsonElement {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", nextId.getAndIncrement())
            put("method", method)
            put("params", params)
        }

        val request = HttpRequest.newBuilder(url)
            .timeout(timeout.toJavaDuration())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Mcp-Method", method)
            .apply {
                protocolVersionOf(params)?.let { header("MCP-Protocol-Version", it) }
                nameFor(method, params)?.let { header("Mcp-Name", encodeHeaderValue(it)) }
                extraHeaders.forEach { (name, value) -> header(name, value) }
            }
            .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(JsonObject.serializer(), body)))
            .build()

        val response = try {
            client.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).await()
        } catch (e: HttpTimeoutException) {
            throw McpException("The MCP server didn't answer '$method' within $timeout.")
        } catch (e: IOException) {
            throw McpException("Could not reach the MCP server at $url: ${e.message}")
        }

        val lines = response.body()
        try {
            val isStream = response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")
            val message = withTimeoutOrNull(timeout) {
                runInterruptible(Dispatchers.IO) {
                    if (isStream) readFinalSseMessage(lines.iterator()) else lines.toList().joinToString("\n")
                }
            } ?: throw McpException("The MCP server didn't finish answering '$method' within $timeout.")

            return parseResponse(response.statusCode(), message)
        } catch (e: UncheckedIOException) {
            throw McpException("The connection to the MCP server at $url failed: ${e.message}")
        } finally {
            lines.close()   // also tells the server to stop: closing the stream means "cancelled"
        }
    }

    override fun close() = client.close()

    private fun parseResponse(status: Int, body: String): JsonElement {
        val message = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

        // Modern servers send JSON-RPC errors with HTTP errors too (400, 404), so check the body first.
        (message?.get("error") as? JsonObject)?.let { throw mcpExceptionFrom(it) }

        if (status !in 200..299) {
            throw McpException("The MCP server answered with HTTP $status: ${body.take(200)}")
        }
        return message?.get("result")
            ?: throw McpException("The MCP server's answer isn't a JSON-RPC response.")
    }

    private fun protocolVersionOf(params: JsonObject): String? =
        ((params["_meta"] as? JsonObject)?.get("io.modelcontextprotocol/protocolVersion") as? JsonPrimitive)?.contentOrNull

    private fun nameFor(method: String, params: JsonObject): String? {
        val field = when (method) {
            "tools/call", "prompts/get" -> "name"
            "resources/read" -> "uri"
            else -> return null
        }
        return (params[field] as? JsonPrimitive)?.contentOrNull
    }

}

/**
 * Makes a value safe for an HTTP header: plain, printable ASCII is sent as it is;
 * anything else is sent as `=?base64?…?=`, the Base64 of its UTF-8 bytes.
 */
internal fun encodeHeaderValue(value: String): String {
    val isSafe = value.isNotEmpty() &&
            value.all { it.code in 0x20..0x7E } &&
            value.first() != ' ' && value.last() != ' ' &&
            !(value.startsWith("=?base64?") && value.endsWith("?="))
    if (isSafe) return value
    return "=?base64?" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + "?="
}
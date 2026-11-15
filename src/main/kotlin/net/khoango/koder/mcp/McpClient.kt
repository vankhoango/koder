package net.khoango.koder.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class McpServerInfo(val name: String, val version: String, val instructions: String?)

data class McpToolInfo(val name: String, val description: String, val inputSchema: JsonObject)

data class McpToolResult(val text: String, val isError: Boolean)

/** A connection to a modern (2026-07-28) MCP server. Every request carries the protocol metadata. */
class McpClient private constructor(
    private val server: McpServerProcess,
    val info: McpServerInfo,
) : AutoCloseable {

    /** Sends a request, adding the `_meta` block that every modern request needs. */
    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeout: Duration = 30.seconds,
    ): JsonElement =
        server.connection.request(method, JsonObject(params + ("_meta" to META)), timeout)

    /** Lists all tools the server offers, following pagination. */
    suspend fun listTools(): List<McpToolInfo> {
        val tools = mutableListOf<McpToolInfo>()
        var cursor: String? = null
        var pages = 0

        do {
            val params = if (cursor == null) JsonObject(emptyMap()) else buildJsonObject { put("cursor", cursor) }
            val result = request("tools/list", params) as? JsonObject
                ?: throw McpException("The server's answer to tools/list isn't a JSON object.")

            (result["tools"] as? JsonArray).orEmpty().forEach { element ->
                parseTool(element)?.let { tools += it }
            }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull
            pages++
        } while (cursor != null && pages < MAX_PAGES)

        return tools
    }

    /** Calls a tool and returns its result as text. Throws [McpException] for protocol errors. */
    suspend fun callTool(name: String, arguments: JsonObject, timeout: Duration = 60.seconds): McpToolResult {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val result = request("tools/call", params, timeout) as? JsonObject
            ?: throw McpException("The server's answer to tools/call isn't a JSON object.")

        if ((result["resultType"] as? JsonPrimitive)?.contentOrNull == "input_required") {
            return McpToolResult("The tool asked for more input from the user, which Koder doesn't support yet.", isError = true)
        }

        val text = (result["content"] as? JsonArray).orEmpty()
            .map { describeContent(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifBlank { result["structuredContent"]?.toString() ?: "(The tool returned no output.)" }

        return McpToolResult(text, isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    override fun close() = server.close()

    private fun parseTool(element: JsonElement): McpToolInfo? {
        val tool = element as? JsonObject ?: return null
        val name = (tool["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        return McpToolInfo(
            name = name,
            description = (tool["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            inputSchema = tool["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") },
        )
    }

    private fun describeContent(element: JsonElement): String {
        val item = element as? JsonObject ?: return ""
        fun field(name: String) = (item[name] as? JsonPrimitive)?.contentOrNull
        return when (field("type")) {
            "text" -> field("text").orEmpty()
            "image" -> "[An image (${field("mimeType") ?: "unknown type"}) was returned, but Koder can't show images to the model yet.]"
            "audio" -> "[An audio clip was returned, but Koder can't pass audio to the model.]"
            "resource_link" -> "[Resource: ${field("uri") ?: "unknown"}]"
            "resource" -> ((item["resource"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
                ?: "[An embedded resource was returned without text.]"
            else -> "[Unsupported content type: ${field("type")}]"
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "2026-07-28"
        private const val UNSUPPORTED_PROTOCOL_VERSION = -32022
        private const val MAX_PAGES = 100
        private val DISCOVER_TIMEOUT = 10.seconds

        private val META = buildJsonObject {
            put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
            put("io.modelcontextprotocol/clientInfo", buildJsonObject {
                put("name", "koder")
                put("version", "0.1")
            })
            put("io.modelcontextprotocol/clientCapabilities", JsonObject(emptyMap()))
        }

        /** Starts the server and checks that it speaks [PROTOCOL_VERSION]. Throws [McpException] otherwise. */
        suspend fun connect(
            command: List<String>,
            scope: CoroutineScope,
            workingDirectory: Path? = null,
            environment: Map<String, String> = emptyMap(),
        ): McpClient {
            val server = try {
                McpServerProcess.start(command, scope, workingDirectory, extraEnvironment = environment)
            } catch (e: IOException) {
                throw McpException("Could not start the MCP server '${command.joinToString(" ")}': ${e.message}")
            }

            val discovered = try {
                server.connection.request("server/discover", buildJsonObject { put("_meta", META) }, DISCOVER_TIMEOUT)
                        as? JsonObject
            } catch (e: McpException) {
                server.close()
                throw if (e.code == UNSUPPORTED_PROTOCOL_VERSION) {
                    McpException("The MCP server doesn't support protocol $PROTOCOL_VERSION. It supports: ${supportedVersions(e.data)}.")
                } else {
                    legacyServer(e.message)
                }
            }

            val versions = supportedVersions(discovered?.get("supportedVersions"))
            if (discovered == null || PROTOCOL_VERSION !in versions) {
                server.close()
                throw legacyServer("its discovery answer doesn't list $PROTOCOL_VERSION")
            }

            val serverInfo = (discovered["_meta"] as? JsonObject)?.get("io.modelcontextprotocol/serverInfo") as? JsonObject
            return McpClient(
                server,
                McpServerInfo(
                    name = (serverInfo?.get("name") as? JsonPrimitive)?.contentOrNull ?: "unknown",
                    version = (serverInfo?.get("version") as? JsonPrimitive)?.contentOrNull ?: "unknown",
                    instructions = (discovered["instructions"] as? JsonPrimitive)?.contentOrNull,
                ),
            )
        }

        private fun supportedVersions(element: JsonElement?): List<String> {
            val array = (element as? JsonArray) ?: ((element as? JsonObject)?.get("supported") as? JsonArray)
            return array?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        }

        private fun legacyServer(reason: String?) = McpException(
            "This MCP server doesn't speak protocol $PROTOCOL_VERSION ($reason). " +
                    "It may only support the older, handshake-based protocol, which Koder doesn't support."
        )
    }
}
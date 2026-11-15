package net.khoango.koder.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration

/** How MCP messages travel between Koder and a server. */
interface McpTransport : AutoCloseable {
    /** Sends a request and returns its result. Throws [McpException] for errors, timeouts, or a closed connection. */
    suspend fun request(method: String, params: JsonObject, timeout: Duration): JsonElement
}

/** The stdio transport: a local server process, with JSON-RPC over its standard input and output. */
class StdioTransport private constructor(private val server: McpServerProcess) : McpTransport {

    override suspend fun request(method: String, params: JsonObject, timeout: Duration): JsonElement =
        server.connection.request(method, params, timeout)

    override fun close() = server.close()

    companion object {
        fun start(
            command: List<String>,
            scope: CoroutineScope,
            workingDirectory: Path? = null,
            environment: Map<String, String> = emptyMap(),
        ): StdioTransport =
            try {
                StdioTransport(McpServerProcess.start(command, scope, workingDirectory, extraEnvironment = environment))
            } catch (e: IOException) {
                throw McpException("Could not start the MCP server '${command.joinToString(" ")}': ${e.message}")
            }
    }
}
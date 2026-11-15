package net.khoango.koder.mcp

import kotlinx.serialization.json.JsonObject
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.tools.Tool
import net.khoango.koder.tools.ToolOutput
import net.khoango.koder.tools.failure

/** A tool offered by an MCP server, presented to the model as a normal Koder tool. */
class McpTool(
    private val serverName: String,
    private val client: McpClient,
    private val info: McpToolInfo,
) : Tool {

    override val definition = ToolDefinition(
        name = mcpToolName(serverName, info.name),
        description = buildString {
            append(info.description.ifBlank { "(The server gave no description.)" }.take(MAX_DESCRIPTION_LENGTH))
            append("\n(Provided by the MCP server \"$serverName\".)")
        },
        inputSchema = info.inputSchema,
    )

    override suspend fun execute(input: JsonObject): ToolOutput =
        try {
            val result = client.callTool(info.name, input)
            ToolOutput(limitOutput(result.text), isError = result.isError)
        } catch (e: McpException) {
            failure("The MCP server \"$serverName\" reported an error: ${e.message}")
        }

    private fun limitOutput(text: String): String =
        if (text.length <= MAX_OUTPUT_LENGTH) text
        else text.take(MAX_OUTPUT_LENGTH) + "\n[Output truncated: showing the first $MAX_OUTPUT_LENGTH of ${text.length} characters.]"

    companion object {
        private const val MAX_DESCRIPTION_LENGTH = 2_000
        private const val MAX_OUTPUT_LENGTH = 10_000
    }
}

/** The name a model sees for an MCP tool: "mcp__<server>__<tool>", with only safe characters, at most 64 long. */
internal fun mcpToolName(serverName: String, toolName: String): String {
    fun clean(part: String) = part.replace(Regex("[^A-Za-z0-9_-]"), "_")
    return "mcp__${clean(serverName)}__${clean(toolName)}".take(64)
}
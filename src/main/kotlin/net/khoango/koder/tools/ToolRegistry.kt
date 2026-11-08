package net.khoango.koder.tools

import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.ToolDefinition
import kotlin.coroutines.cancellation.CancellationException

class ToolRegistry(tools: List<Tool>) {

    private val toolsByName = tools.associateBy{ it.definition.name }

    val definitions: List<ToolDefinition> = tools.map { it.definition }

    suspend fun execute(call: ContentBlock.ToolUse): ContentBlock.ToolResult {
        val tool = toolsByName[call.name]

        val output = if (tool == null) {
            ToolOutput(
                "Unknown tool '${call.name}'. Available tools: ${toolsByName.keys.joinToString()}",
                isError = true,
            )
        } else {
            try {
                tool.execute(call.input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolOutput("Tool '${call.name}' failed: ${e::class.simpleName} ${e.message}", isError = true)
            }
        }

        return ContentBlock.ToolResult(call.id, call.name, output.content, output.isError)
    }
}
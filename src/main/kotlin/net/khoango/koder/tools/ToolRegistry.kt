package net.khoango.koder.tools

import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.permissions.Answer
import net.khoango.koder.permissions.Decision
import net.khoango.koder.permissions.PermissionPolicy
import kotlin.coroutines.cancellation.CancellationException

class ToolRegistry(
    tools: List<Tool>,
    private val policy: PermissionPolicy = PermissionPolicy(),
    private val askUser: suspend (ContentBlock.ToolUse) -> Answer = { Answer.NO },
) {

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
            when (policy.decide(call)) {
                Decision.ALLOW -> runTool(tool, call)
                Decision.DENY -> BLOCKED
                Decision.ASK -> when (askUser(call)) {
                    Answer.YES -> runTool(tool, call)
                    Answer.ALWAYS -> {
                        policy.allowForSession(call)
                        runTool(tool, call)
                    }
                    Answer.NO -> DECLINED
                }
            }
        }

        return ContentBlock.ToolResult(call.id, call.name, output.content, output.isError)
    }

    private suspend fun runTool(tool: Tool, call: ContentBlock.ToolUse): ToolOutput =
        try {
            tool.execute(call.input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutput("Tool '${call.name}' failed: ${e::class.simpleName} ${e.message}", isError = true)
        }

    companion object {
        private val DECLINED = ToolOutput(
            "The user declined this action. Ask the user how to proceed, or try a different approach.",
            isError = true,
        )
        private val BLOCKED = ToolOutput(
            "Blocked by Koder's safety rules. Don't try to achieve the same effect another way; " +
                    "explain to the user what you wanted to do and why.",
            isError = true,
        )
    }
}
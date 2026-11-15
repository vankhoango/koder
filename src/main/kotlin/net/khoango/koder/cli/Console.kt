package net.khoango.koder.cli

import net.khoango.koder.agent.AgentEvent
import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.Role
import net.khoango.koder.llm.LlmException
import net.khoango.koder.mcp.McpServerConfig
import net.khoango.koder.permissions.Answer
import net.khoango.koder.permissions.alwaysScope
import net.khoango.koder.session.SessionStore
import kotlin.time.Duration

/** Prints the main agent's events. */
fun printEvent(event: AgentEvent) = when (event) {
    is AgentEvent.TextDelta -> {
        print(event.text)
        System.out.flush()
    }
    AgentEvent.TextEnd -> println()
    is AgentEvent.ToolCalled -> println("[tool call] ${event.call.name} ${event.call.input}")
    is AgentEvent.ToolFinished -> {
        val label = if (event.result.isError) "tool error" else "tool result"
        println("[$label] ${event.result.content.preview()}")
    }
    is AgentEvent.StepLimitReached -> println("[step limit of ${event.maxSteps} reached]")
    AgentEvent.Abandoned -> println("(Stopped: the model kept requesting tools after the limit. This exchange isn't saved.)")
    is AgentEvent.AutoCompacting ->
        println("[context about ${event.usedTokens * 100 / event.limit}% full: compacting the conversation]")
    is AgentEvent.Compacted -> println("[conversation compacted into a ${event.summary.length}-character summary]")
    AgentEvent.SubagentStarted, AgentEvent.SubagentFinished -> Unit
}

/** Prints a subagent's events, indented and prefixed with its skill's name. Its streamed text stays hidden. */
fun printSubagentEvent(skillName: String, event: AgentEvent) {
    val prefix = "  [$skillName]"
    when (event) {
        AgentEvent.SubagentStarted -> println("[subagent started: $skillName]")
        AgentEvent.SubagentFinished -> println("[subagent finished: $skillName]")
        is AgentEvent.ToolCalled -> println("$prefix [tool call] ${event.call.name} ${event.call.input}")
        is AgentEvent.ToolFinished -> {
            val label = if (event.result.isError) "tool error" else "tool result"
            println("$prefix [$label] ${event.result.content.preview()}")
        }
        is AgentEvent.StepLimitReached -> println("$prefix [step limit of ${event.maxSteps} reached]")
        AgentEvent.Abandoned -> println("$prefix (stopped: too many tool steps)")
        else -> Unit   // streamed text and context events stay hidden
    }
}

/** Asks whether a tool call may run. */
fun askPermission(call: ContentBlock.ToolUse): Answer {
    val scope = alwaysScope(call)
    print("Allow ${call.name}? [y]es / [a]lways ($scope, this session) / [N]o ")
    System.out.flush()
    return when (readlnOrNull()?.trim()?.lowercase()) {
        "y", "yes" -> Answer.YES
        "a", "always" -> Answer.ALWAYS
        else -> Answer.NO
    }
}

/** Asks whether an MCP server from the project's configuration may be started. */
fun askToStartProjectServer(server: McpServerConfig): Boolean {
    println("This project's .koder/mcp.json wants to start the MCP server '${server.name}':")
    println("  ${server.command.joinToString(" ")}")
    print("Start it? [y/N] ")
    System.out.flush()
    return readlnOrNull()?.trim()?.lowercase() == "y"
}

fun printRetry(attempt: Int, wait: Duration, error: LlmException) {
    println("[${error.message}; retrying in ${wait.inWholeMilliseconds / 1000.0}s (attempt ${attempt + 1})]")
}

fun printResumed(resumed: SessionStore.Resumed) {
    println("[resumed ${resumed.store.file.fileName}: ${resumed.state.history.size} messages]")
    resumed.state.history
        .lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }
        ?.let { println("Last answer: ${it.text.lines().first().take(200)}") }
}

private fun String.preview(maxLength: Int = 200): String {
    val oneLine = replace("\r\n", "\n").replace("\r", "").replace("\n", " ⏎ ")
    return if (oneLine.length <= maxLength) oneLine else oneLine.take(maxLength) + "…"
}
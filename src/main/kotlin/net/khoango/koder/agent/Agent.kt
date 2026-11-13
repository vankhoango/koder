package net.khoango.koder.agent

import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.Role
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.llm.LlmException
import net.khoango.koder.llm.LlmResponse
import net.khoango.koder.llm.LlmStreamEvent
import net.khoango.koder.llm.Usage
import net.khoango.koder.llm.send
import net.khoango.koder.tools.ToolRegistry

class Agent (
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val systemPrompt: String,
    private val maxSteps: Int = 25,
    private val contextLimit: Int? = null,
    private val onEvent: (AgentEvent) -> Unit = {},
) {

    private val history = mutableListOf<Message>()
    private var summary: String? = null

    private val fullSystemPrompt: String
        get() = summary?.let { "$systemPrompt\n\n# Summary of the earlier conversation\n$it" } ?: systemPrompt

    var usage: Usage = Usage.ZERO
        private set

    private var lastInputTokens = 0L

    suspend fun run(userInput: String) {
        compactIfNeeded()
        val turn = mutableListOf(Message.user(userInput))

        var reply = send(turn)
        var steps = 0

        while (reply.toolUses.isNotEmpty() && steps < maxSteps) {
            val toolResult = executeTools(reply.toolUses)
            turn += toolResult
            steps++
            reply = send(turn)
        }

        if (reply.toolUses.isNotEmpty()) {
            onEvent(AgentEvent.StepLimitReached(steps))
            turn += refuseTools(reply.toolUses)
            reply = send(turn)
            if (reply.toolUses.isNotEmpty()) {
                onEvent(AgentEvent.Abandoned)
                return
            }
        }

        history += turn
    }

    private fun refuseTools(calls: List<ContentBlock.ToolUse>): Message =
        Message(
            Role.USER,
            calls.map { call ->
                ContentBlock.ToolResult(
                    toolUseId = call.id,
                    toolName = call.name,
                    content = "Not executed: the limit of $maxSteps tool steps for this request was reached. " +
                            "Don't call any more tools. Summarize what you found so far, and tell the user what remains to be done.",
                    isError = true,
                )
            },
        )

    fun reset() {
        history.clear()
        summary = null
        usage = Usage.ZERO
    }

    private suspend fun send(turn: MutableList<Message>): Message {
        var completed: LlmResponse? = null
        var hasText = false

        llm.stream(fullSystemPrompt, history + turn, tools.definitions).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> {
                    hasText = true
                    onEvent(AgentEvent.TextDelta(event.text))
                }
                is LlmStreamEvent.Completed -> completed = event.response
            }
        }
        if (hasText) onEvent(AgentEvent.TextEnd)

        val response = checkNotNull(completed) { "The stream ended without a final response." }
        usage += response.usage
        lastInputTokens = response.usage.inputTokens
        turn += response.message
        return response.message
    }

    private suspend fun executeTools(calls: List<ContentBlock.ToolUse>): Message {
        val results = calls.map { call ->
            onEvent(AgentEvent.ToolCalled(call))
            tools.execute(call).also { result ->
                onEvent(AgentEvent.ToolFinished(result))
            }
        }
        return Message(Role.USER, results)
    }

    /** Replaces the history with a summary written by the model. Returns false if there was nothing to compact. */
    suspend fun compact(): Boolean {
        if (history.isEmpty()) return false

        val response = llm.send(fullSystemPrompt, history + Message.user(COMPACT_INSTRUCTIONS), tools.definitions)
        usage += response.usage

        val newSummary = response.message.text
        if (newSummary.isBlank() || response.message.toolUses.isNotEmpty()) {
            throw LlmException("The model didn't return a usable summary, so the conversation was not compacted.")
        }

        summary = newSummary
        history.clear()
        lastInputTokens = 0
        onEvent(AgentEvent.Compacted(newSummary))
        return true
    }

    private suspend fun compactIfNeeded() {
        val limit = contextLimit ?: return
        val used = if (lastInputTokens > 0) lastInputTokens else estimateTokens()
        if (used < limit * COMPACT_THRESHOLD) return

        onEvent(AgentEvent.AutoCompacting(used, limit))
        compact()
    }

    /** A rough estimate (about 4 characters per token), for servers that don't report usage. */
    private fun estimateTokens(): Long {
        val characters = fullSystemPrompt.length + history.sumOf { message ->
            message.content.sumOf { block ->
                when (block) {
                    is ContentBlock.Text -> block.text.length
                    is ContentBlock.ToolUse -> block.input.toString().length
                    is ContentBlock.ToolResult -> block.content.length
                }
            }
        }
        return characters / 4L
    }

    companion object {
        private val COMPACT_INSTRUCTIONS = """
            Summarize our conversation so far, so that you can continue the work without the full history.
            Include: what the user asked for and why; important decisions; the files you read or changed,
            with their paths and what matters about them; commands you ran and their results; and anything
            still unfinished. Keep exact names, paths, and error messages. Don't call any tools.
            Reply with the summary only.
        """.trimIndent()

        private const val COMPACT_THRESHOLD = 0.75
    }
}


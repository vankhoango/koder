package net.khoango.koder.agent

import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.Role
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.llm.Usage
import net.khoango.koder.tools.ToolRegistry

class Agent (
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val systemPrompt: String,
    private val maxSteps: Int = 25,
    private val onEvent: (AgentEvent) -> Unit = {},
    ) {

    private val history = mutableListOf<Message>()

    var usage: Usage = Usage.ZERO
        private set

    suspend fun run(userInput: String) {
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
        usage = Usage.ZERO
    }

    private suspend fun send(turn: MutableList<Message>): Message {
        val response = llm.send(systemPrompt, turn, tools.definitions)
        usage += response.usage
        turn += response.message
        if (response.message.text.isNotEmpty()) {
            onEvent(AgentEvent.AssistantText(response.message.text))
        }
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
}


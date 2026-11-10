package net.khoango.koder.agent

import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.Role
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.llm.Usage
import net.khoango.koder.tools.ToolRegistry

class Agent (val llm: LlmClient, val tools: ToolRegistry, val systemPrompt: String) {

    private val history = mutableListOf<Message>()

    var usage: Usage = Usage.ZERO
        private set

    suspend fun run(userInput: String) {
        val turn = mutableListOf(Message.user(userInput))
        val first = send(turn)

        if (first.toolUses.isNotEmpty()) {
            turn += executeTools(first.toolUses)
            send(turn)
        }

        if (turn.last().toolUses.isEmpty()) {
            history += turn
        } else {
            println("(The model wants to use more tools. That's the next step, so this exchange isn't saved.)")
        }
    }

    fun reset() {
        history.clear()
        usage = Usage.ZERO
    }

    private suspend fun send(turn: MutableList<Message>): Message {
        val response = llm.send(systemPrompt, turn, tools.definitions)
        usage += response.usage
        turn += response.message
        if (response.message.text.isNotEmpty()) {
            println(response.message.text)
        }
        return response.message
    }

    private suspend fun executeTools(calls: List<ContentBlock.ToolUse>): Message {
        val results = calls.map { call ->
            println("[Tool call] ${call.name} ${call.input}")
            tools.execute(call).also { result ->
                val label = if (result.isError) "tool error" else "tool result"
                println("[$label] ${result.content.preview()}")
            }
        }
        return Message(Role.USER, results)
    }
}

private fun String.preview(maxLength: Int = 200): String {
    val oneLine = replace("\n", " ⏎ ")
    return if (oneLine.length <= maxLength) oneLine else oneLine.take(maxLength) + "…"
}
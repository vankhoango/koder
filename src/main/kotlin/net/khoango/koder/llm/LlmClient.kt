package net.khoango.koder.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.single
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.ToolDefinition

interface LlmClient : AutoCloseable {
    fun stream(
        system: String,
        messages: List<Message>,
        tools: List<ToolDefinition> = emptyList(),
    ): Flow<LlmStreamEvent>

    override fun close() {}
}

suspend fun LlmClient.send(
    system: String,
    messages: List<Message>,
    tools: List<ToolDefinition> = emptyList(),
): LlmResponse {
    val completed = mutableListOf<LlmResponse>()
    stream(system, messages, tools).collect { event ->
        if (event is LlmStreamEvent.Completed) completed += event.response
    }
    return completed.singleOrNull()
        ?: error("Expected exactly one Completed event from stream(), but got ${completed.size}.")
}
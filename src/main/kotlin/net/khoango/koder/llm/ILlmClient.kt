package net.khoango.koder.llm

import kotlinx.coroutines.flow.Flow
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
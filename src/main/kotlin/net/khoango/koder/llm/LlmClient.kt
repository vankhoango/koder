package net.khoango.koder.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.single
import kotlinx.serialization.Serializable
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.ToolDefinition


class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class LlmConfig(val baseUrl: String, val apiKey: String, val model: String) {
    override fun toString() = "LlmConfig(baseUrl=$baseUrl, model=$model, apiKey=***)"
}

@Serializable
data class Usage(val inputTokens: Long, val outputTokens: Long) {
    operator fun plus(other: Usage) = Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
    override fun toString() = "$inputTokens in, $outputTokens out"
    companion object {
        val ZERO = Usage(0L, 0L)
    }
}

data class LlmResponse(val message: Message, val usage: Usage)

sealed interface LlmStreamEvent {
    data class TextDelta(val text: String) : LlmStreamEvent
    data class Completed(val response: LlmResponse) : LlmStreamEvent
}


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
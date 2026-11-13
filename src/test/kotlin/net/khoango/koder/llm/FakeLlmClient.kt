package net.khoango.koder.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.Role
import net.khoango.koder.domain.ToolDefinition

class FakeLlmClient(vararg responses: Message) : LlmClient {
    private val remaining = ArrayDeque(responses.toList())

    val requests = mutableListOf<List<Message>>()

    override fun stream(
        system: String,
        messages: List<Message>,
        tools: List<ToolDefinition>,
    ): Flow<LlmStreamEvent> = flow {
        requests += messages.toList()
        val response = remaining.removeFirstOrNull()
            ?: error("FakeLlmClient has no scripted response left for request #${requests.size}")

        response.text.chunked(5).forEach { emit(LlmStreamEvent.TextDelta(it)) }   // simulate streaming
        emit(LlmStreamEvent.Completed(LlmResponse(response, Usage(10, 5))))
    }
}

/** An assistant message that calls one tool with string parameters. */
fun toolCall(id: String, name: String, vararg params: Pair<String, String>) = Message(
    Role.ASSISTANT,
    listOf(ContentBlock.ToolUse(id, name, buildJsonObject { params.forEach { (key, value) -> put(key, value) } })),
)
package net.khoango.koder.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.Role
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.tools.Tool
import net.khoango.koder.tools.ToolOutput
import net.khoango.koder.tools.stringParam

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

    /** Adds more scripted responses, e.g. after a test has made the fake fail. */
    fun enqueue(vararg responses: Message) {
        remaining.addAll(responses)
    }
}

/** An assistant message that calls one tool with string parameters. */
fun toolCall(id: String, name: String, vararg params: Pair<String, String>) = Message(
    Role.ASSISTANT,
    listOf(ContentBlock.ToolUse(id, name, buildJsonObject { params.forEach { (key, value) -> put(key, value) } })),
)

/** A tool that records its inputs and echoes the "text" parameter. */
private class EchoTool : Tool {
    val inputs = mutableListOf<JsonObject>()

    override val definition = ToolDefinition("echo", "Echoes the given text.", JsonObject(emptyMap()))

    override suspend fun execute(input: JsonObject): ToolOutput {
        inputs += input
        return ToolOutput("echo: ${input.stringParam("text")}")
    }
}
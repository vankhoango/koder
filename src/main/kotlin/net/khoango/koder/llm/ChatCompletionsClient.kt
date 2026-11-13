package net.khoango.koder.llm

import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.core.jsonMapper
import com.openai.errors.OpenAIInvalidDataException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.Role
import net.khoango.koder.domain.ToolDefinition
import kotlin.jvm.optionals.getOrNull
class ChatCompletionsClient(private val config: LlmConfig): LlmClient {

    private val client = OpenAIOkHttpClient.builder()
        .baseUrl(config.baseUrl)
        .apiKey(config.apiKey)
        .maxRetries(0)
        .build()

    override fun stream(
        system: String,
        messages: List<Message>,
        tools: List<ToolDefinition>,
    ): Flow<LlmStreamEvent> = flow {
        val params = buildParams(system, messages, tools).toBuilder()
            .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
            .build()

        val text = StringBuilder()
        val toolCalls = sortedMapOf<Long, ToolCallBuilder>()
        var usage = Usage.ZERO

        // 1. Read all chunks: emit text pieces, and collect everything else.
        client.chat().completions().createStreaming(params).use { response ->
            response.stream().iterator().forEach { chunk ->
                chunk.usage().getOrNull()?.let { u ->
                    //println("[raw usage] $u")
                    usage = Usage(
                        inputTokens = u.promptTokens(),
                        outputTokens = maxOf(u.completionTokens(), u.totalTokens() - u.promptTokens()),
                        cachedInputTokens = u.promptTokensDetails().getOrNull()?.cachedTokens()?.getOrNull() ?: 0,
                    )
                }
                val delta = chunk.choices().firstOrNull()?.delta() ?: return@forEach

                delta.content().getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                    text.append(it)
                    emit(LlmStreamEvent.TextDelta(it))
                }
                delta.toolCalls().getOrNull().orEmpty().forEach { call ->
                    val index = indexOf(call, toolCalls)
                    toolCalls.getOrPut(index) { ToolCallBuilder(index) }.add(call)
                }
            }
        }   // ← the loop and the `use` block end here

        // 2. Build the complete message, once.
        val content = buildList {
            if (text.isNotEmpty()) add(ContentBlock.Text(text.toString()))
            toolCalls.values.forEach { add(it.build()) }
        }

        // 3. Emit Completed, once.
        emit(LlmStreamEvent.Completed(LlmResponse(Message(Role.ASSISTANT, content), usage)))
    }
        .flowOn(Dispatchers.IO)
        .catch { throw it.toLlmException() }

    private fun buildParams(system: String, messages: List<Message>, tools: List<ToolDefinition>): ChatCompletionCreateParams {
        val systemParam = ChatCompletionMessageParam.ofSystem(
            ChatCompletionSystemMessageParam.builder().content(system).build()
        )
        return ChatCompletionCreateParams.builder()
            .model(config.model)
            .messages(listOf(systemParam) + messages.flatMap { it.toParams() })
            .apply { tools.forEach { addTool(it.toSdkTool()) } }
            .build()
    }

    /**
     * Finds which tool call a streamed fragment belongs to. Uses the standard `index` field when present;
     * some servers (e.g. Gemini's compatibility layer) omit it, so fall back to the call's ID.
     */
    private fun indexOf(call: ChatCompletionChunk.Choice.Delta.ToolCall, toolCalls: Map<Long, ToolCallBuilder>): Long {
        call._index().asKnown().getOrNull()?.let { return it }

        val id = call.id().getOrNull()
            ?: return (toolCalls.size - 1L).coerceAtLeast(0)          // no ID: continues the last call

        return toolCalls.entries.firstOrNull { it.value.id == id }?.key   // a call we've already started
            ?: toolCalls.size.toLong()                                     // a new call
    }

    override fun close() = client.close()
}

private class ToolCallBuilder(private val index: Long) {
    var id = ""
        private set
    private var name = ""
    private val arguments = StringBuilder()
    private var metadata: JsonObject? = null

    fun add(delta: ChatCompletionChunk.Choice.Delta.ToolCall) {
        delta.id().getOrNull()?.let { id = it }
        delta.function().getOrNull()?.let { function ->
            function.name().getOrNull()?.let { name = it }
            function.arguments().getOrNull()?.let { arguments.append(it) }
        }
        delta._additionalProperties().toJsonObjectOrNull()?.let { metadata = it }
    }

    fun build() = ContentBlock.ToolUse(
        id = id.ifEmpty { "call_$index" },   // some local servers omit IDs
        name = name,
        input = parseArguments(arguments.toString()),
        providerMetadata = metadata,
    )
}

private val RETRYABLE_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)
private fun Throwable.toLlmException(): Throwable = when (this) {
    is OpenAIServiceException -> LlmException(
        "API error (HTTP ${statusCode()}): $message",
        this,
        retryable = statusCode() in RETRYABLE_STATUS_CODES,
    )
    is OpenAIIoException -> LlmException("Network error: $message", this)
    is OpenAIInvalidDataException -> LlmException("The server sent data in an unexpected format: $message", this)
    else -> this
}

private fun JsonElement.toJavaValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonObject -> mapValues { (_, v) -> v.toJavaValue() }
    is JsonArray -> map { it.toJavaValue() }
}

private fun JsonObject.toFuntionParameters(): FunctionParameters {
    val builder = FunctionParameters.builder()
    forEach { key, value ->
        builder.putAdditionalProperty(key, JsonValue.from(value.toJavaValue()))
    }
    return builder.build()
}

private fun ToolDefinition.toSdkTool(): ChatCompletionFunctionTool {
    return ChatCompletionFunctionTool.builder()
        .function(
            FunctionDefinition.builder()
                .name(name)
                .description(description)
                .parameters(inputSchema.toFuntionParameters())
                .build()
        )
        .build()
}

private val sdkJsonMapper = jsonMapper()

private fun parseArguments(raw: String): JsonObject {
    return runCatching {
        Json.parseToJsonElement(raw).jsonObject
    }.getOrElse { JsonObject(emptyMap()) }
}

private fun Map<String, JsonValue>.toJsonObjectOrNull(): JsonObject? =
    if (isEmpty()) null
    else Json.parseToJsonElement(sdkJsonMapper.writeValueAsString(this)).jsonObject

private fun ChatCompletionMessageFunctionToolCall.toToolUse(): ContentBlock.ToolUse {
    return ContentBlock.ToolUse(
        id = id(),
        name = function().name(),
        input = parseArguments(function().arguments()),
        providerMetadata = _additionalProperties().toJsonObjectOrNull(),
    )
}

private fun Message.toParams(): List<ChatCompletionMessageParam> = when (role) {
    Role.USER -> {
        val results = content.filterIsInstance<ContentBlock.ToolResult>()
        if (results.isNotEmpty()) {
            results.map { it.toToolParam() }
        } else {
            listOf(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(text).build()))
        }
    }
    Role.ASSISTANT -> listOf(ChatCompletionMessageParam.ofAssistant(toAssistantParam()))
}

private fun Message.toAssistantParam(): ChatCompletionAssistantMessageParam {
    val builder = ChatCompletionAssistantMessageParam.builder()
    if (text.isNotEmpty()) builder.content(text)
    toolUses.forEach { builder.addToolCall(it.toSdkToolCall()) }
    return builder.build()
}

private fun ContentBlock.ToolUse.toSdkToolCall(): ChatCompletionMessageToolCall {
    val builder = ChatCompletionMessageFunctionToolCall.builder()
        .id(id)
        .function(
            ChatCompletionMessageFunctionToolCall.Function.builder()
                .name(name)
                .arguments(input.toString())
                .build()
        )
    // Send provider-specific data back exactly as we received it (e.g. Gemini's thought signature).
    providerMetadata?.forEach { (key, value) ->
        builder.putAdditionalProperty(key, JsonValue.from(value.toJavaValue()))
    }
    return ChatCompletionMessageToolCall.ofFunction(builder.build())
}

private fun ContentBlock.ToolResult.toToolParam(): ChatCompletionMessageParam =
    ChatCompletionMessageParam.ofTool(
        ChatCompletionToolMessageParam.builder()
            .toolCallId(toolUseId)
            .content(if (isError) "Error: $content" else content)
            .build()
    )
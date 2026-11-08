package net.khoango.koder.llm

import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.core.jsonMapper
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.Role
import net.khoango.koder.domain.ToolDefinition
import kotlin.jvm.optionals.getOrNull

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class LlmConfig(val baseUrl: String, val apiKey: String, val model: String) {
    override fun toString() = "LlmConfig(baseUrl=$baseUrl, model=$model, apiKey=***)"
}

data class Usage(val inputTokens: Long, val outputTokens: Long) {
    operator fun plus(other: Usage) = Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
    override fun toString() = "$inputTokens in, $outputTokens out"
    companion object {
        val ZERO = Usage(0L, 0L)
    }
}

data class LlmResponse(val message: Message, val usage: Usage)

class LlmClient(private val config: LlmConfig): AutoCloseable {

    private val client = OpenAIOkHttpClient.builder()
        .baseUrl(config.baseUrl)
        .apiKey(config.apiKey)
        .build()

    suspend fun send(system: String, messages: List<Message>, tools: List<ToolDefinition> = emptyList()): LlmResponse {

        val systemParam = ChatCompletionMessageParam.ofSystem(
            ChatCompletionSystemMessageParam.builder().content(system).build()
        )

        val params = ChatCompletionCreateParams.builder()
            .model(config.model)
            .messages(listOf(systemParam) + messages.map { it.toParams() })
            .apply {
                tools.forEach { tool ->
                    addTool(tool.toSdkTool())
                }
            }
            .build()

        try {
            val completion = client.async().chat().completions().create(params).await()
            val sdkMessage = completion.choices().firstOrNull()?.message()

            val text = sdkMessage?.content()?.getOrNull().orEmpty()
            val toolCalls = sdkMessage?.toolCalls()?.getOrNull().orEmpty()
            val usage = completion.usage().getOrNull()

            val content = buildList {
                if (text.isNotEmpty()) {
                    add(ContentBlock.Text(text))
                }
                toolCalls.mapNotNull {
                    it.function().getOrNull()
                }.forEach {
                    add(it.toToolUse())
                }
            }

            return LlmResponse(
                Message(Role.ASSISTANT, content),
                Usage (
                    usage?.promptTokens() ?: 0L,
                    usage?.completionTokens() ?: 0L
                )
            )

        } catch (e: OpenAIServiceException) {
            throw LlmException("API error (HTTP ${e.statusCode()}): ${e.message}", e)
        } catch (e: OpenAIIoException) {
            throw LlmException("Network error: ${e.message}", e)
        }
    }

    override fun close() = client.close()
}

private fun Message.toParams(): ChatCompletionMessageParam = when (this.role) {
    Role.USER -> ChatCompletionMessageParam.ofUser(
        ChatCompletionUserMessageParam.builder().content(this.text).build(),
    )
    Role.ASSISTANT -> ChatCompletionMessageParam.ofAssistant(
        ChatCompletionAssistantMessageParam.builder().content(this.text).build()
    )
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
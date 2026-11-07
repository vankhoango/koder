package net.khoango.koder.llm

import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import kotlinx.coroutines.future.await
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.Role
import kotlin.jvm.optionals.getOrNull

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class LlmConfig(val baseUrl: String, val apiKey: String, val model: String) {
    override fun toString() = "LlmConfig(baseUrl=$baseUrl, model=$model, apiKey=***)"
}

class LlmClient(private val config: LlmConfig): AutoCloseable {

    private val client = OpenAIOkHttpClient.builder()
        .baseUrl(config.baseUrl)
        .apiKey(config.apiKey)
        .build()

    suspend fun send(prompt: String): String {
        val messages = listOf(Message.user(prompt))

        val params = ChatCompletionCreateParams.builder()
            .model(config.model)
            .messages(messages.map { it.toParams() })
            .build()

        try {
            val completion = client.async().chat().completions().create(params).await()
            return completion.choices().firstOrNull()?.message()?.content()?.getOrNull() ?: ""
        } catch (e: OpenAIServiceException) {
            throw LlmException("API error (HTTP ${e.statusCode()}): ${e.message}", e)
        } catch (e: OpenAIIoException) {
            throw LlmException("Network error: ${e.message}", e)
        }
    }

    override fun close() = client.close()
}

fun Message.toParams(): ChatCompletionMessageParam = when (this.role) {
    Role.USER -> ChatCompletionMessageParam.ofUser(
        ChatCompletionUserMessageParam.builder().content(this.text).build(),
    )
    Role.ASSISTANT -> ChatCompletionMessageParam.ofAssistant(
        ChatCompletionAssistantMessageParam.builder().content(this.text).build()
    )
}
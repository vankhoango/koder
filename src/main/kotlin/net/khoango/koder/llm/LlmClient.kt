package net.khoango.koder.llm

import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.future.await
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
        val params = ChatCompletionCreateParams.builder()
            .model(config.model)
            .addUserMessage(prompt)
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
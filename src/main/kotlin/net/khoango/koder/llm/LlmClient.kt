package net.khoango.koder.llm

import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlin.jvm.optionals.getOrNull

private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

fun callLlm(apiKey: String, model: String, prompt: String): String {
    val client = OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(BASE_URL)
        .build()

    try {
        val params = ChatCompletionCreateParams.builder()
            .model(model)
            .addUserMessage(prompt)
            .build()

        val completion = client.chat().completions().create(params)
        return completion.choices().firstOrNull()?.message()?.content()?.getOrNull() ?: ""
    } finally {
        client.close()
    }
}
package net.khoango.koder.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import sun.invoke.util.ValueConversions.ignore

private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
private val jsonFormat = Json { ignoreUnknownKeys = true }


suspend fun callLlm(apiKey: String, model: String, prompt: String): String {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonFormat)
        }
    }
    client.use { httpClient ->
        val request = GenerateContentRequest(
            contents = listOf(LlmContent(role = "user", parts = listOf(LlmPart(text = prompt)))),
        )
        val response = httpClient.post("$BASE_URL/models/$model:generateContent") {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        val body = response.body<GenerateContentResponse>()
        val parts = body.candidates.firstOrNull()?.content?.parts.orEmpty()
        return parts.mapNotNull { it.text }.joinToString("")
    }
}
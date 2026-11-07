package net.khoango.koder.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

suspend fun callLlm(apiKey: String, model: String, prompt: String): String {
    HttpClient(CIO).use { http ->
        val response = http.post("$BASE_URL/models/$model:generateContent") {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody("""{"contents":[{"role":"user","parts":[{"text":"$prompt"}]}]}""")
        }
        return "${response.status}\n${response.bodyAsText()}"
    }
}
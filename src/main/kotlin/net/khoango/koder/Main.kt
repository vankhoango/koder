package net.khoango.koder

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.llm.LlmConfig
import net.khoango.koder.llm.LlmException


class Koder: CliktCommand(name="koder") {
    private val prompt by argument().optional()
    private val model by option("--model", envvar = "KODER_MODEL").default("gemini-3.5-flash")
    private val baseUrl by option("--baseUrl", envvar = "KODER_BASE_URL")
        .default("https://generativelanguage.googleapis.com/v1beta/openai/")

    override fun run(): Unit = runBlocking {
        val apiKey = System.getenv("KODER_API_KEY") ?:
            throw PrintMessage("KODER_API_KEY is not set", statusCode = 1, printError = true)

        val llmConfig = LlmConfig(baseUrl, apiKey, model)
        LlmClient(llmConfig).use { client ->
            chatLoop(client, prompt)
        }
    }
}

private suspend fun chatLoop(llm: LlmClient, firstPrompt: String? = null) {
    var input = firstPrompt
    while (true) {
        if (input == null) {
            print("\n> ")
            System.out.flush()
            input = readlnOrNull()?.trim() ?: break
        }

        if (input == "/exit") break

        if (input.isNotBlank()) {
            try {
                println(llm.send(input))
            } catch (e: LlmException) {
                println("Error: ${e.message}")
            }
        }
        input = null
    }
}

fun main(args: Array<String>) = Koder().main(args)
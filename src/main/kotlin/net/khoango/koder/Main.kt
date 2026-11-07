package net.khoango.koder

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import net.khoango.koder.domain.Message
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.llm.LlmConfig
import net.khoango.koder.llm.LlmException
import net.khoango.koder.llm.Usage


private const val SYSTEM_PROMPT =
    "You are Koder, a helpful coding assistant running in the user's terminal. Be concise."

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
    val history = mutableListOf<Message>()
    var input = firstPrompt
    var sessionUsage  = Usage.ZERO

    while (true) {
        if (input == null) {
            print("\n> ")
            System.out.flush()
            input = readlnOrNull()?.trim() ?: break
        }

        if (input == "/exit") break

        if (input == "/reset") {
            history.clear()
            sessionUsage = Usage.ZERO
            println("Conversation cleared.")
            input = null
            continue
        }

        if (input.isNotBlank()) {
            val request = Message.user(input)
            try {
                val response = llm.send(history + request)
                // The history only contains the complete exchanges.
                // A message is added only after its request has succeeded.
                history += request
                history += response.message
                sessionUsage += response.usage

                println(response.message.text)
                println("[tokens: ${response.usage} | session: $sessionUsage]")
            } catch (e: LlmException) {
                println("Error: ${e.message}")
            }
        }
        input = null
    }
}

fun main(args: Array<String>) = Koder().main(args)
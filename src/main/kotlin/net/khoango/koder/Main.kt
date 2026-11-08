package net.khoango.koder

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.domain.listToolDefinition
import net.khoango.koder.domain.readToolDefinition
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.llm.LlmConfig
import net.khoango.koder.llm.LlmException
import net.khoango.koder.llm.Usage
import net.khoango.koder.tools.ReadFileTool
import kotlin.collections.emptyMap
import kotlin.io.path.Path
import kotlin.io.path.absolute


private const val SYSTEM_PROMPT =
    "You are Koder, a helpful coding assistant running in the user's terminal. Be concise."

private val TOOLS = listOf<ToolDefinition>(readToolDefinition, listToolDefinition)

class Koder: CliktCommand(name="koder") {
    private val prompt by argument().optional()
    private val model by option("--model", envvar = "KODER_MODEL").default("gemini-3.5-flash")
    private val baseUrl by option("--baseUrl", envvar = "KODER_BASE_URL")
        .default("https://generativelanguage.googleapis.com/v1beta/openai/")

    private val noTools by option("--no-tools", help = "Don't advertise any tools to the model").flag()

    override fun run(): Unit = runBlocking {
        val apiKey = System.getenv("KODER_API_KEY") ?:
            throw PrintMessage("KODER_API_KEY is not set", statusCode = 1, printError = true)

        val llmConfig = LlmConfig(baseUrl, apiKey, model)
        val tools = if (noTools) emptyList<ToolDefinition>() else TOOLS
        LlmClient(llmConfig).use { client ->
            chatLoop(client,  prompt, tools)
        }
    }
}

private suspend fun chatLoop(llm: LlmClient, firstPrompt: String? = null, tools: List<ToolDefinition>) {
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
                val response = llm.send(SYSTEM_PROMPT,history + request, tools)
                sessionUsage += response.usage

                if (response.message.text.isNotEmpty()) println(response.message.text)
                response.message.toolUses.forEach { println("[tool call] ${it.name} ${it.input}") }

                // The history only contains the complete exchanges.
                // A message is added only after its request has succeeded.
                if (response.message.toolUses.isEmpty()) {
                    history += request
                    history += response.message
                } else {
                    println("(Tools can't run yet, so this exchange isn't saved to the history.)")
                }
            } catch (e: LlmException) {
                println("Error: ${e.message}")
            }
        }
        input = null
    }
}

fun main(args: Array<String>) {
    Koder().main(args)
}
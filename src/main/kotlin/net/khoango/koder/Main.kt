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
import net.khoango.koder.tools.ToolRegistry
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
import kotlin.io.path.Path
import kotlin.io.path.absolute


private const val SYSTEM_PROMPT =
    "You are Koder, a helpful coding assistant running in the user's terminal. Be concise."

class Koder: CliktCommand(name="koder") {
    private val prompt by argument().optional()
    private val model by option("--model", envvar = "KODER_MODEL").default("gemini-3.5-flash")
    private val baseUrl by option("--baseUrl", envvar = "KODER_BASE_URL")
        .default("https://generativelanguage.googleapis.com/v1beta/openai/")

    private val noTools by option("--no-tools", help = "Don't advertise any tools to the model").flag()

    override fun run(): Unit = runBlocking {
        val apiKey = System.getenv("KODER_API_KEY") ?:
            throw PrintMessage("KODER_API_KEY is not set", statusCode = 1, printError = true)

        val projectRoot = Path("").absolute()
        val registry = if (noTools) ToolRegistry(emptyList())
            else ToolRegistry(listOf(
                ReadFileTool(projectRoot)
            ))


        val llmConfig = LlmConfig(baseUrl, apiKey, model)
        LlmClient(llmConfig).use { client ->
            chatLoop(client,  prompt, registry)
        }
    }
}

private suspend fun chatLoop(llm: LlmClient, firstPrompt: String? = null, registry: ToolRegistry) {
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
            val userMessage = Message.user(input)
            try {
                val response = llm.send(SYSTEM_PROMPT, history + userMessage, registry.definitions)
                sessionUsage += response.usage

                if (response.message.text.isNotEmpty()) println(response.message.text)

                for (call in response.message.toolUses) {
                    println("[tool call] ${call.name} ${call.input}")
                    val result = registry.execute(call)
                    val label = if (result.isError) "tool error" else "tool result"
                    println("[$label] ${result.content.preview()}")
                }

                if (response.message.toolUses.isEmpty()) {
                    history += userMessage
                    history += response.message
                } else {
                    println("(Results aren't sent back to the model yet, so this exchange isn't saved.)")
                }
                println("[tokens: ${response.usage} | session: $sessionUsage]")
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

private fun String.preview(maxLength: Int = 200): String {
    val oneLine = replace("\n", " ⏎ ")
    return if (oneLine.length <= maxLength) oneLine else oneLine.take(maxLength) + "…"
}
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
import net.khoango.koder.domain.Role
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
                val turn = mutableListOf(userMessage)

                val first = llm.send(SYSTEM_PROMPT, history + turn, registry.definitions)
                sessionUsage += first.usage
                turn += first.message
                if (first.message.text.isNotEmpty()) println(first.message.text)

                if (first.message.toolUses.isNotEmpty()) {
                    val results = first.message.toolUses.map { call ->
                        println("[tool call] ${call.name} ${call.input}")
                        registry.execute(call).also { result ->
                            val label = if (result.isError) "tool error" else "tool result"
                            println("[$label] ${result.content.preview()}")
                        }
                    }
                    turn += Message(Role.USER, results)

                    val second = llm.send(SYSTEM_PROMPT, history + turn, registry.definitions)
                    sessionUsage += second.usage
                    turn += second.message
                    if (second.message.text.isNotEmpty()) println(second.message.text)
                }

                if (turn.last().toolUses.isEmpty()) {
                    history += turn
                } else {
                    println("(The model wants to use more tools. That's Lesson 1.4, so this exchange isn't saved.)")
                }
                println("[session: $sessionUsage]")
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
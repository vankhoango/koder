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
import net.khoango.koder.agent.Agent
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.llm.LlmConfig
import net.khoango.koder.llm.LlmException
import net.khoango.koder.tools.ListFileTool
import net.khoango.koder.tools.ReadFileTool
import net.khoango.koder.tools.ToolRegistry
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
                ReadFileTool(projectRoot),
                ListFileTool(projectRoot),
            ))


        val llmConfig = LlmConfig(baseUrl, apiKey, model)
        LlmClient(llmConfig).use { client ->
            val agent = Agent(client, registry, SYSTEM_PROMPT)
            chatLoop(agent, prompt)
        }
    }
}

private suspend fun chatLoop(agent: Agent, firstPrompt: String? = null) {
    var input = firstPrompt
    while (true) {
        if (input == null) {
            print("\n> ")
            System.out.flush()
            input = readlnOrNull()?.trim() ?: break
        }

        when (input) {
            "/quit" -> break
            "/reset" -> {
                agent.reset()
                println("Conversation cleared")
                input = null
                continue
            }
        }

        if (input.isNotBlank()) {
            try {
                agent.run(input)
                println("[session: ${agent.usage}]")
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

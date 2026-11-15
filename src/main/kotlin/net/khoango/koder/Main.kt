package net.khoango.koder

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.coroutines.runBlocking
import net.khoango.koder.app.KoderApp
import net.khoango.koder.app.KoderSettings
import net.khoango.koder.app.StartupException
import net.khoango.koder.cli.ChatLoop
import net.khoango.koder.cli.askToStartProjectServer
import net.khoango.koder.cli.builtInCommands
import net.khoango.koder.cli.skillCommands
import net.khoango.koder.llm.LlmConfig
import net.khoango.koder.mcp.McpServerConfig
import kotlin.io.path.Path
import kotlin.io.path.absolute

/**
 * Koder's command-line entry point.
 *
 * The defaults use Gemini through its OpenAI-compatible API. To use another provider, such as a local
 * LM Studio server, set the KODER_BASE_URL and KODER_MODEL environment variables, or pass --base-url and --model.
 */
class Koder : CliktCommand(name = "koder") {
    private val prompt by argument().optional()
    private val model by option("--model", envvar = "KODER_MODEL", help = "The model to use")
        .default(DEFAULT_MODEL)
    private val baseUrl by option("--base-url", envvar = "KODER_BASE_URL", help = "The OpenAI-compatible API's base URL")
        .default(DEFAULT_BASE_URL)
    private val noTools by option("--no-tools", help = "Don't advertise any tools to the model").flag()
    private val maxSteps by option("--max-steps", help = "Maximum tool steps per request")
        .int()
        .restrictTo(1..100)
        .default(25)
    private val yolo by option("--yolo", help = "Allow every action without asking (dangerous actions are still blocked)").flag()
    private val contextLimit by option(
        "--context-limit", envvar = "KODER_CONTEXT_LIMIT",
        help = "The model's context window in tokens. Enables automatic compaction.",
    ).int().restrictTo(min = 1_000)
    private val resume by option("--resume", help = "Continue the most recent session in this project").flag()
    private val mcp by option("--mcp", help = "An MCP server to connect, as name=command. Can be repeated.").multiple()

    override fun run(): Unit = runBlocking {
        val apiKey = System.getenv("KODER_API_KEY")
            ?: throw PrintMessage("KODER_API_KEY is not set", statusCode = 1, printError = true)

        val settings = KoderSettings(
            llm = LlmConfig(baseUrl, apiKey, model),
            maxSteps = maxSteps,
            contextLimit = contextLimit,
            noTools = noTools,
            yolo = yolo,
            resume = resume,
            mcpServers = parseMcpOptions(mcp),
        )

        val app = try {
            KoderApp.start(settings, Path("").absolute(), approveProjectServer = ::askToStartProjectServer)
        } catch (e: StartupException) {
            throw PrintMessage(e.message ?: "Koder could not start.", statusCode = 1, printError = true)
        }

        app.use {
            val builtIn = builtInCommands(it)
            val reserved = builtIn.map { command -> command.name }.toSet() + setOf("help", "quit", "exit")
            ChatLoop(it.agent, builtIn + skillCommands(it, reserved)).run(prompt)
        }
    }

    /** Turns each `--mcp name=command` value into a server configuration. */
    private fun parseMcpOptions(values: List<String>): List<McpServerConfig> =
        values.map { spec ->
            val name = spec.substringBefore('=', missingDelimiterValue = "").trim()
            val command = spec.substringAfter('=').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (name.isEmpty() || command.isEmpty()) {
                throw PrintMessage(
                    "Invalid --mcp value '$spec'. Use name=command, e.g. demo=py server.py",
                    statusCode = 1,
                    printError = true,
                )
            }
            McpServerConfig(name, command)
        }

    private companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
    }
}

fun main(args: Array<String>) = Koder().main(args)
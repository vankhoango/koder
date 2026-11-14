package net.khoango.koder

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.coroutines.runBlocking
import net.khoango.koder.app.KoderApp
import net.khoango.koder.app.KoderSettings
import net.khoango.koder.app.StartupException
import net.khoango.koder.cli.ChatLoop
import net.khoango.koder.cli.builtInCommands
import net.khoango.koder.llm.LlmConfig
import net.khoango.koder.skills.skillCommands
import kotlin.io.path.Path
import kotlin.io.path.absolute

class Koder : CliktCommand(name = "koder") {
    private val prompt by argument().optional()
    private val model by option("--model", envvar = "KODER_MODEL")
        //.default("gemini-3.5-flash")
        .default("google/gemma-4-12b-qat")
    private val baseUrl by option("--base-url", envvar = "KODER_BASE_URL")
        //.default("https://generativelanguage.googleapis.com/v1beta/openai/")
        .default("http://192.168.1.60:1234/v1")
    private val noTools by option("--no-tools", help = "Don't advertise any tools to the model").flag()
    private val maxSteps by option("--max-steps", help = "Maximum tool steps per request").int().restrictTo(1..100).default(25)
    private val yolo by option("--yolo", help = "Allow every action without asking (dangerous actions are still blocked)").flag()
    private val contextLimit by option(
        "--context-limit", envvar = "KODER_CONTEXT_LIMIT",
        help = "The model's context window in tokens. Enables automatic compaction.",
    ).int().restrictTo(min = 1_000).default(16384)
    private val resume by option("--resume", help = "Continue the most recent session in this project").flag()

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
        )

        val app = try {
            KoderApp.start(settings, Path("").absolute())
        } catch (e: StartupException) {
            throw PrintMessage(e.message ?: "Koder could not start.", statusCode = 1, printError = true)
        }

        app.use {
            val builtIn = builtInCommands(it)
            val reserved = builtIn.map { command -> command.name }.toSet() + setOf("help", "quit", "exit")
            ChatLoop(it.agent, builtIn + skillCommands(it, reserved)).run(prompt)
        }
    }
}

fun main(args: Array<String>) = Koder().main(args)
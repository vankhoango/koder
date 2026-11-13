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
import net.khoango.koder.agent.Agent
import net.khoango.koder.agent.AgentEvent
import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.Message
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.llm.LlmConfig
import net.khoango.koder.llm.LlmException
import net.khoango.koder.llm.LlmStreamEvent
import net.khoango.koder.permissions.Answer
import net.khoango.koder.permissions.PermissionPolicy
import net.khoango.koder.tools.EditFileTool
import net.khoango.koder.tools.FileTracker
import net.khoango.koder.tools.FindFilesTool
import net.khoango.koder.tools.ListFileTool
import net.khoango.koder.tools.ReadFileTool
import net.khoango.koder.tools.RunCommandTool
import net.khoango.koder.tools.SearchTextTool
import net.khoango.koder.tools.ToolRegistry
import net.khoango.koder.tools.WriteFileTool
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.time.Duration.Companion.seconds


private var SYSTEM_PROMPT = """
    You are Koder, a helpful coding assistant running in the user's terminal. Be concise.
    If the user gives you an exact file path, use it directly; only explore with list_files when you don't know where something is.
    Before using tools, check whether the conversation already contains the answer.
    """.trimIndent()

private fun askPermission(call: ContentBlock.ToolUse): Answer {
    val scope = if (call.name == "run_command") "this exact command" else "all ${call.name} calls"
    print("Allow ${call.name}? [y]es / [a]lways ($scope, this session) / [N]o ")
    System.out.flush()
    return when (readlnOrNull()?.trim()?.lowercase()) {
        "y", "yes" -> Answer.YES
        "a", "always" -> Answer.ALWAYS
        else -> Answer.NO
    }
}

class Koder: CliktCommand(name="koder") {
    private val prompt by argument().optional()
    private val model by option("--model", envvar = "KODER_MODEL")
        //.default("gemini-3.5-flash")
        .default("google/gemma-4-12b-qat")
    private val baseUrl by option("--baseUrl", envvar = "KODER_BASE_URL")
        //.default("https://generativelanguage.googleapis.com/v1beta/openai/")
        .default("http://192.168.1.60:1234/v1")

    private val yolo by option("--yolo", help = "Allow every action without asking (dangerous actions are still blocked)").flag()

    private val noTools by option("--no-tools", help = "Don't advertise any tools to the model").flag()

    private val maxSteps by option("--max-steps", help = "Maximum tool steps per request")
        .int()
        .restrictTo(1..100)
        .default(25)

    override fun run(): Unit = runBlocking {
        val apiKey = System.getenv("KODER_API_KEY") ?:
            throw PrintMessage("KODER_API_KEY is not set", statusCode = 1, printError = true)

        val projectRoot = Path("").absolute()
        val fileTracker = FileTracker()
        val tools = if (noTools) emptyList() else listOf(
            ReadFileTool(projectRoot, fileTracker),
            ListFileTool(projectRoot),
            WriteFileTool(projectRoot, fileTracker),
            EditFileTool(projectRoot, fileTracker),
            RunCommandTool(projectRoot),
            FindFilesTool(projectRoot),
            SearchTextTool(projectRoot),
        )
        if (yolo) println("⚠ --yolo: all actions are allowed without asking. Dangerous actions are still blocked.")
        val registry = ToolRegistry(tools, PermissionPolicy(yolo), ::askPermission)


        val llmConfig = LlmConfig(baseUrl, apiKey, model)
        LlmClient(llmConfig).use { client ->
            val agent = Agent(client, registry, SYSTEM_PROMPT, maxSteps, ::printEvent)
            chatLoop(agent, prompt, fileTracker)
        }
    }
}

private suspend fun chatLoop(agent: Agent, firstPrompt: String? = null, fileTracker: FileTracker) {
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
                fileTracker.clearKnown()
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
                println("\nError: ${e.message}")
            }
        }

        input = null
    }
}

private fun printEvent(event: AgentEvent) = when (event) {
    is AgentEvent.TextDelta -> {
        print(event.text)
        System.out.flush()
    }
    AgentEvent.TextEnd -> println()
    is AgentEvent.ToolCalled -> println("[tool call] ${event.call.name} ${event.call.input}")
    is AgentEvent.ToolFinished -> {
        val label = if (event.result.isError) "tool error" else "tool result"
        println("[$label] ${event.result.content.preview()}")
    }
    is AgentEvent.StepLimitReached -> println("[step limit of ${event.maxSteps} reached]")
    AgentEvent.Abandoned -> println("(Stopped: the model kept requesting tools after the limit. This exchange isn't saved.)")
}

private fun String.preview(maxLength: Int = 200): String {
    val oneLine = replace("\r\n", "\n").replace("\r", "").replace("\n", " ⏎ ")
    return if (oneLine.length <= maxLength) oneLine else oneLine.take(maxLength) + "…"
}

fun main(args: Array<String>) {
    Koder().main(args)
}

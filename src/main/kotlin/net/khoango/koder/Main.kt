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
import net.khoango.koder.domain.Role
import net.khoango.koder.llm.ChatCompletionsClient
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.llm.LlmConfig
import net.khoango.koder.llm.LlmException
import net.khoango.koder.llm.LlmStreamEvent
import net.khoango.koder.permissions.Answer
import net.khoango.koder.permissions.PermissionPolicy
import net.khoango.koder.prompt.INIT_PROMPT
import net.khoango.koder.prompt.buildSystemPrompt
import net.khoango.koder.session.SessionStore
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

    private val contextLimit by option(
        "--context-limit",
        envvar = "KODER_CONTEXT_LIMIT",
        help = "The model's context window in tokens. Enables automatic compaction.",
    ).int().restrictTo(min = 1_000)

    private val resume by option("--resume", help = "Continue the most recent session in this project").flag()

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
        val systemPrompt = buildSystemPrompt(SYSTEM_PROMPT, projectRoot)
        systemPrompt.memoryFiles.forEach { println("[memory: $it]") }

        val resumed = if (resume) {
            try {
                SessionStore.resumeLatest(projectRoot)
                    ?: throw PrintMessage("No saved session found in this project.", statusCode = 1, printError = true)
            } catch (e: IllegalArgumentException) {
                throw PrintMessage("Could not read the latest session: ${e.message}", statusCode = 1, printError = true)
            }
        } else {
            null
        }
        val sessionStore = resumed?.store ?: SessionStore.newSession(projectRoot)

        ChatCompletionsClient(llmConfig).use { client ->
            val agent = Agent(client, registry, systemPrompt.text, maxSteps,
                contextLimit = contextLimit, onStateChanged = { sessionStore.save(it) }) { event ->
                    if (event is AgentEvent.Compacted)
                        fileTracker.clearKnown()
                    printEvent(event)
            }

            resumed?.let {
                agent.restore(it.state)
                println("[resumed ${it.store.file.fileName}: ${it.state.history.size} messages]")
                it.state.history.lastOrNull { message -> message.role == Role.ASSISTANT && message.text.isNotBlank() }
                    ?.let { last -> println("Last answer: ${last.text.lines().first().take(200)}") }
            }

            chatLoop(agent, prompt, fileTracker) {
                val reloaded = buildSystemPrompt(SYSTEM_PROMPT, projectRoot)
                reloaded.memoryFiles.forEach { println("[memory reloaded: $it]") }
                agent.systemPrompt = reloaded.text
            }
        }
    }
}

private suspend fun chatLoop(
    agent: Agent,
    firstPrompt: String? = null,
    fileTracker: FileTracker,
    reloadSystemPrompt: () -> Unit,
) {
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
            "/compact" -> {
                try {
                    if (!agent.compact()) println("Nothing to compact yet.")
                    println("[session: ${agent.usage}]")
                } catch (e: LlmException) {
                    println("Error: ${e.message}")
                }
                input = null
                continue
            }
            "/init" -> {
                try {
                    agent.run(INIT_PROMPT)
                    reloadSystemPrompt()
                    println("[session: ${agent.usage}]")
                } catch (e: LlmException) {
                    println("\nError: ${e.message}")
                }
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
    is AgentEvent.Compacted -> println("[conversation compacted into a ${event.summary.length}-character summary]")
    is AgentEvent.AutoCompacting ->
        println("[context about ${event.usedTokens * 100 / event.limit}% full: compacting the conversation]")
}

private fun String.preview(maxLength: Int = 200): String {
    val oneLine = replace("\r\n", "\n").replace("\r", "").replace("\n", " ⏎ ")
    return if (oneLine.length <= maxLength) oneLine else oneLine.take(maxLength) + "…"
}

fun main(args: Array<String>) {
    Koder().main(args)
}

package net.khoango.koder.prompt

import net.khoango.koder.skills.Skill
import net.khoango.koder.tools.normalizeLineEndings
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.Path

data class SystemPrompt(val text: String, val memoryFiles: List<Path>)
private const val MEMORY_FILE_NAME = "KODER.md"
private const val MAX_MEMORY_CHARS = 20_000

val INIT_PROMPT = """
    Create a KODER.md file in the project root that will help future sessions work in this project.

    First, explore efficiently: look at the top-level files, the build configuration, and the main
    source folders. Use find_files and search_text rather than listing every folder one by one.

    Then write KODER.md with:
    - a short overview of what the project is;
    - the exact commands to build, test, and run it, for the shell named in the environment;
    - the directory structure, and where the key code lives;
    - important conventions you can see in the code.

    Keep it under 60 lines, factual, and specific to this project; don't include generic advice.
    If KODER.md already exists, read it first and improve it, keeping everything that is still correct.
""".trimIndent()

val BASE_SYSTEM_PROMPT = """
    You are Koder, a helpful coding assistant running in the user's terminal. Be concise.
    If the user gives you an exact file path, use it directly; only explore when you don't know where something is.
    Before using tools, check whether the conversation already contains the answer.
""".trimIndent()

fun buildSystemPrompt(base: String, projectRoot: Path, skills: List<Skill> = emptyList()): SystemPrompt {
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    var userMemory = Path(System.getProperty("user.home"), ".koder", MEMORY_FILE_NAME)
    val projectMemory = projectRoot.resolve(MEMORY_FILE_NAME)

    val loaded = mutableListOf<Path>()

    val text = buildString {
        appendLine(base)
        appendLine()
        appendLine("# Environment")
        appendLine("- Operating system: ${System.getProperty("os.name")}")
        appendLine("- Shell used by run_command: ${if (isWindows) "Windows PowerShell" else "bash"}")
        appendLine("- Project root: $projectRoot")
        appendLine("- Today's date: ${LocalDate.now()}")

        readMemory(userMemory)?.let {
            loaded += mutableListOf(userMemory)
            appendLine()
            appendLine("# The user's personal instructions (from ~/.koder/$MEMORY_FILE_NAME)")
            appendLine(it)
        }
        readMemory(projectMemory)?.let {
            loaded += mutableListOf(projectMemory)
            appendLine()
            appendLine("# Project instructions")
            appendLine("The contents of $MEMORY_FILE_NAME are included below; you don't need to read the file.")
            appendLine("If these conflict with the personal instructions, follow these.")
            appendLine(it)
        }
        if (skills.isNotEmpty()) {
            appendLine()
            appendLine("# Skills")
            appendLine("Skills are saved instructions for specific tasks. The user runs a skill by typing /<name>.")
            appendLine("You can't run skills yourself, but when a request clearly matches one, suggest that the user run it.")
            skills.forEach { appendLine("- ${it.name}: ${it.description}") }
        }

        appendLine("- Python command: ${pythonCommand ?: "not installed"}")
    }

    return SystemPrompt(text.trimEnd(), loaded)
}

private fun readMemory(file: Path): String? {
        if (!file.exists() || !file.isRegularFile()) return null
        val content = file.readText(Charsets.UTF_8).normalizeLineEndings().trim()
        if (content.isEmpty()) return null
        if (content.length <= MAX_MEMORY_CHARS) return content
        return content.take(MAX_MEMORY_CHARS) + "\n\n[$MEMORY_FILE_NAME was truncated: it is longer than $MAX_MEMORY_CHARS characters.]"
}

/** The first working Python command, detected once per run, or null if none works. */
private val pythonCommand: String? by lazy {
    listOf("py", "python", "python3").firstOrNull { command ->
        runCatching {
            val process = ProcessBuilder(command, "--version").redirectErrorStream(true).start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0 && process.inputStream.bufferedReader().readText().startsWith("Python")
        }.getOrDefault(false)
    }
}
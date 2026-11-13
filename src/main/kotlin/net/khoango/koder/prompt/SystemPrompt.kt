package net.khoango.koder.prompt

import net.khoango.koder.tools.normalizeLineEndings
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.Path

data class SystemPrompt(val text: String, val memoryFiles: List<Path>)
private const val MEMORY_FILE_NAME = "KODER.md"
private const val MAX_MEMORY_CHARS = 20_000

fun buildSystemPrompt(base: String, projectRoot: Path): SystemPrompt {
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

package net.khoango.koder.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.domain.objectSchema
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes

val editFileDefinition = ToolDefinition(
    name = "edit_file",
    description = """
        Edit an existing file by replacing one exact piece of text with new text.
        old_string must match the file's current content exactly, including whitespace, indentation,
        and line breaks, and it must appear exactly once in the file. If it could appear more than once,
        include a few surrounding lines to make it unique.
        Prefer this over write_file for changing existing files, because only the changed part is sent.
        You must read the file with read_file in this conversation before editing it.
        To create a new file, use write_file.
    """.trimIndent(),
    inputSchema = objectSchema {
        string("path", "Path to the file, relative to the project root.", required = true)
        string("old_string", "The exact text to replace. It must appear exactly once in the file.", required = true)
        string("new_string", "The text to replace it with. Use an empty string to delete old_string.", required = true)
    },
)

class EditFileTool(
    private val projectRoot: Path,
    private val tracker: FileTracker,
) : Tool {

    override val definition = editFileDefinition

    override suspend fun execute(input: JsonObject): ToolOutput {
        val path = input.stringParam("path") ?: return failure("Missing required parameter: path")
        val oldString = input.stringParam("old_string") ?: return failure("Missing required parameter: old_string")
        val newString = input.stringParam("new_string") ?: return failure("Missing required parameter: new_string")
        val file = projectRoot.resolveInside(path)
            ?: return failure("'$path' is outside the project. Use a path relative to the project root.")

        return withContext(Dispatchers.IO) { edit(path, file, oldString, newString) }
    }

    private fun edit(path: String, file: Path, oldString: String, newString: String): ToolOutput {
        if (oldString.isEmpty()) return failure("old_string must not be empty. To create a new file, use write_file.")
        if (!file.exists()) return failure("File not found: '$path'. To create a new file, use write_file.")
        if (file.isDirectory()) return failure("'$path' is a directory, not a file.")

        return try {
            val bytes = file.readBytes()
            tracker.refusalFor(path, file, bytes)?.let { return it }

            val original = bytes.toString(Charsets.UTF_8)
            val lineEnding = LineEnding.detect(original)
            val text = original.normalizeLineEndings()
            val old = oldString.normalizeLineEndings()
            val new = newString.normalizeLineEndings()

            if (old == new) return failure("old_string and new_string are identical, so nothing would change.")

            val matches = text.occurrenceLines(old)
            if (matches.isEmpty()) return failure(notFoundMessage(path, text, old))
            if (matches.size > 1) {
                return failure(
                    "old_string appears ${matches.size} times in '$path', at lines ${matches.toReadableList()}. " +
                            "Include more surrounding text, such as the whole line or a neighboring line, " +
                            "so that it matches exactly one place."
                )
            }

            val updated = lineEnding.applyTo(text.replaceFirst(old, new))
            writeAtomically(file, updated)
            tracker.markKnown(file, updated.toByteArray())

            ToolOutput("Edited '$path' at line ${matches.single()}.")
        } catch (e: IOException) {
            failure("Could not edit '$path': ${e::class.simpleName} ${e.message}")
        }
    }

    private fun notFoundMessage(path: String, text: String, old: String): String {
        val similar = findWithDifferentWhitespace(text, old)
            ?: return "old_string was not found in '$path'. It must match the file's current content exactly, " +
                    "including whitespace and line breaks. Copy the exact text from a read_file result rather than from memory."

        val actual = text.lines().slice(similar).joinToString("\n")
        return "old_string was not found exactly in '$path', but the same text with different whitespace or " +
                "indentation exists at lines ${similar.first + 1}–${similar.last + 1}. The actual text is:\n$actual\n" +
                "Use this exact text, including its whitespace, as old_string."
    }
}

/** Counts non-overlapping occurrences of [sub] in this string. */
internal fun String.countOccurrences(sub: String): Int {
    var count = 0
    var index = indexOf(sub)
    while (index >= 0) {
        count++
        index = indexOf(sub, index + sub.length)
    }
    return count
}

/** Returns the 1-based line number of every non-overlapping occurrence of [sub]. */
internal fun String.occurrenceLines(sub: String): List<Int> {
    val lines = mutableListOf<Int>()
    var index = indexOf(sub)
    while (index >= 0) {
        lines += lineNumberAt(index)
        index = indexOf(sub, index + sub.length)
    }
    return lines
}

private fun String.lineNumberAt(index: Int): Int = (0 until index).count { this[it] == '\n' } + 1

/**
 * Looks for lines matching [old] when leading and trailing whitespace is ignored on each line.
 * Returns the matching 0-based line indexes, or null if there is no such passage.
 */
private fun findWithDifferentWhitespace(text: String, old: String): IntRange? {
    val fileLines = text.lines()
    val oldLines = old.trim('\n').lines().map { it.trim() }
    if (oldLines.all { it.isEmpty() } || oldLines.size > fileLines.size) return null

    for (start in 0..fileLines.size - oldLines.size) {
        if (oldLines.indices.all { fileLines[start + it].trim() == oldLines[it] }) {
            return start until start + oldLines.size
        }
    }
    return null
}

/** Formats numbers like "4", "1 and 4", or "1, 4 and 7". */
private fun List<Int>.toReadableList(): String =
    if (size == 1) "${first()}" else dropLast(1).joinToString(", ") + " and " + last()
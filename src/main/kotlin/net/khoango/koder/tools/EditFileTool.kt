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
        if (oldString == newString) return failure("old_string and new_string are identical, so nothing would change.")
        if (!file.exists()) return failure("File not found: '$path'. To create a new file, use write_file.")
        if (file.isDirectory()) return failure("'$path' is a directory, not a file.")

        return try {
            val bytes = file.readBytes()
            tracker.refusalFor(path, file, bytes)?.let { return it }

            val text = bytes.toString(Charsets.UTF_8)
            val count = text.countOccurrences(oldString)
            if (count == 0) return failure("old_string was not found in '$path'.")
            if (count > 1) return failure("old_string appears $count times in '$path'. It must appear exactly once.")

            val updated = text.replaceFirst(oldString, newString)
            writeAtomically(file, updated)
            tracker.markKnown(file, updated.toByteArray())

            ToolOutput("Edited '$path'.")
        } catch (e: IOException) {
            failure("Could not edit '$path': ${e::class.simpleName} ${e.message}")
        }
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
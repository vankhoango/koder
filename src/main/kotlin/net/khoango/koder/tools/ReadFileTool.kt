package net.khoango.koder.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.domain.readToolDefinition
import java.io.IOException
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class ReadFileTool(private val projectRoot: Path, private val fileTracker: FileTracker): Tool {

    override val definition: ToolDefinition = readToolDefinition

    override suspend fun execute(input: JsonObject): ToolOutput {
        val path = (input["path"] as? JsonPrimitive)?.contentOrNull
            ?: return failure("Missing required parameter: path")

        val file = projectRoot.resolveInside(path)
            ?: return failure("'$path' is outside the project. Use a path relative to the project root.")

        return withContext(Dispatchers.IO) { readText(path, file) }
    }

    private fun readText(path: String, file: Path): ToolOutput {
        if (!file.exists()) return failure("File not found: '$path'. Use list_files to find the correct path.")
        if (file.isDirectory()) return failure("'$path' is a directory. Use list_files to see what it contains.")
        if (file.fileSize() > MAX_BYTES) return failure("'$path' is too large to read (over ${MAX_BYTES / 1_000_000} MB).")

        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return failure("Could not read '$path': ${e.message}")
        }
        if (bytes.contains(0)) return failure("'$path' appears to be a binary file and can't be shown as text.")

        val lines = bytes.toString(Charsets.UTF_8).lines()
        if (lines.size <= MAX_LINES) {
            fileTracker.markKnown(file, bytes)
            return ToolOutput(lines.joinToString("\n"))
        }

        return ToolOutput(
            lines.take(MAX_LINES).joinToString("\n") +
                    "\n\n[Truncated: showing the first $MAX_LINES of ${lines.size} lines.]"
        )
    }

    companion object {
        private const val MAX_BYTES = 1_000_000L
        private const val MAX_LINES = 2_000
    }
}
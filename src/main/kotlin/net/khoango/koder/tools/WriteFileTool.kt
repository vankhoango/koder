package net.khoango.koder.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.domain.objectSchema
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

val writeFileDefinition = ToolDefinition(
    name = "write_file",
    description = """
        Create a new file, or completely replace the contents of an existing file, in the current project.
        Always provide the complete file content; anything you leave out will be lost.
        Missing parent directories are created automatically.
        Before replacing an existing file, read it with read_file so you don't lose anything important.
    """.trimIndent(),
    inputSchema = objectSchema {
        string(
            "path",
            "Path to the file, relative to the project root, e.g. \"src/main/kotlin/Util.kt\".",
            required = true
        )
        string("content", "The complete content to write to the file.", required = true)
    },
)

class WriteFileTool(private val projectRoot: Path) : Tool {

    override val definition = writeFileDefinition

    override suspend fun execute(input: JsonObject): ToolOutput {
        val path = input.stringParam("path")
            ?: return failure("Missing required parameter: path")
        val content = input.stringParam("content")
            ?: return failure("Missing required parameter: content")
        val file = projectRoot.resolveInside(path)
            ?: return failure("'$path' is outside the project. Use a path relative to the project root.")

        return withContext(Dispatchers.IO) {
            write(path, file, content)
        }
    }

    private fun write(path: String, file: Path, content: String): ToolOutput {
        if (file.isDirectory()) return failure("'$path' is a directory, not a file.")

        val existed = file.exists()
        return try {
            file.parent?.createDirectories()
            file.writeText(content)
            val lines = content.lines().size
            ToolOutput(if (existed) "Replaced '$path' ($lines lines)." else "Created '$path' ($lines lines).")
        } catch (e: IOException) {
            failure("Could not write '$path': ${e::class.simpleName} ${e.message}")
        }
    }
}
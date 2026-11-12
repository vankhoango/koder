package net.khoango.koder.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.domain.objectSchema
import java.nio.file.Path
import kotlin.io.path.*


val listToolDefinition = ToolDefinition(
    name = "list_files",
    description = """
        List the files and subdirectories in one directory of the current project.
        Paths in the result are relative to the project root, and directory names end with "/".
        The listing is not recursive: call this tool again on a subdirectory to see what it contains.
        Use this to look at the contents of a single directory. To find files by name anywhere
        in the project, use find_files instead of listing directories one by one.
    """.trimIndent(),
    inputSchema = objectSchema {
        string(
            "path",
            "Directory to list, relative to the project root, e.g. \"src/main\". Omit it to list the project root.",
        )
    },
)

class ListFileTool(projectRoot: Path) : Tool {

    private val root = projectRoot.toAbsolutePath().normalize()

    override val definition: ToolDefinition = listToolDefinition

    override suspend fun execute(input: JsonObject): ToolOutput {
        val path = input.stringParam("path") ?: "."
        val directory = root.resolveInside(path)
            ?: return failure("'$path' is outside the project. Use a path relative to the project root.")

        return withContext(Dispatchers.IO) { listDirectory(path, directory) }
    }

    private fun listDirectory(path: String, directory: Path): ToolOutput {
        if (!directory.exists()) return failure("Directory not found: '$path'. Use find_files to search the project.")
        if (!directory.isDirectory()) return failure("'$path' is a file, not a directory. Use read_file to read it.")

        val entries = directory.listDirectoryEntries()
            .sortedBy { it.name }
            .map {
                val relative = root.relativize(it).invariantSeparatorsPathString
                if (it.isDirectory()) "$relative/" else relative
            }

        return ToolOutput(if (entries.isEmpty()) "(empty directory)" else entries.joinToString("\n"))
    }
}
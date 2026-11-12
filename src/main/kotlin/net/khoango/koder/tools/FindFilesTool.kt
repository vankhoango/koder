package net.khoango.koder.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.domain.objectSchema
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlin.io.path.invariantSeparatorsPathString

val findFilesDefinition = ToolDefinition(
    name = "find_files",
    description = """
        Find files anywhere in the project whose paths match a glob pattern, e.g. "**/*.kt" or "src/**/Agent*.kt".
        Patterns are matched against paths relative to the project root, using "/" as the separator.
        "**" matches any number of directories (including none at the start); "*" matches within one name.
        Generated folders (.git, .gradle, .idea, .kotlin, build) are skipped.
        Use this to find files by name; use list_files to look at a single directory.
        To search inside files, use search_text.
    """.trimIndent(),
    inputSchema = objectSchema {
        string("pattern", "The glob pattern, e.g. \"**/*.kt\".", required = true)
    },
)

class FindFilesTool (projectRoot: Path) : Tool {

    private val root = projectRoot.toAbsolutePath().normalize()

    override val definition: ToolDefinition = findFilesDefinition

    companion object {
        private const val MAX_RESULTS = 200
    }

    override suspend fun execute(input: JsonObject): ToolOutput {
        val pattern = input.stringParam("pattern") ?: return failure("Missing required parameter: pattern")
        val matchers = try {
            globMatchers(pattern)
        } catch (e: IllegalArgumentException) {
            return failure("Invalid glob pattern '$pattern': ${e.message}")
        }

        return withContext(Dispatchers.IO) { find(pattern, matchers) }
    }

    private fun find(pattern: String, matchers: List<PathMatcher>): ToolOutput {
        val matches = mutableListOf<String>()

        walkProjectFiles(root) { file ->
            val relative = root.relativize(file)
            if (matchers.any { it.matches(relative) }) matches += relative.invariantSeparatorsPathString
        }

        if (matches.isEmpty()) return ToolOutput("No files match '$pattern'.")

        matches.sort()
        val shown = matches.take(MAX_RESULTS).joinToString("\n")
        val note = if (matches.size > MAX_RESULTS) {
            "\n[Showing the first $MAX_RESULTS of ${matches.size} matches. Use a more specific pattern.]"
        } else ""
        return ToolOutput(shown + note)
    }
}
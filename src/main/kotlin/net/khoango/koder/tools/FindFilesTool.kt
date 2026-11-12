package net.khoango.koder.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.domain.objectSchema
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.collections.buildList
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

val findFilesDefinition = ToolDefinition(
    name = "find_files",
    description = """
        Find files anywhere in the project whose paths match a glob pattern, e.g. "**/*.kt" or "src/**/Agent*.kt".
        Patterns are matched against paths relative to the project root, using "/" as the separator.
        "**" matches any number of directories (including none at the start); "*" matches within one name.
        Generated folders (.git, .gradle, .idea, .kotlin, build) are skipped.
        Use this to find files by name; use list_files to look at a single directory.
    """.trimIndent(),
    inputSchema = objectSchema {
        string("pattern", "The glob pattern, e.g. \"**/*.kt\".", required = true)
    },
)

class FindFilesTool (projectRoot: Path) : Tool {

    private val root = projectRoot.toAbsolutePath().normalize()

    override val definition: ToolDefinition = findFilesDefinition

    companion object {
        val IGNORED_DIRECTORIES = setOf(".git", ".gradle", ".idea", ".kotlin", "build", "node_modules")
        private const val MAX_RESULTS = 200
    }

    override suspend fun execute(input: JsonObject): ToolOutput {
        val pattern = input.stringParam("pattern") ?: return failure("Missing required parameter: pattern")
        val matchers = try {
            val fileSystem = FileSystems.getDefault()
            buildList<PathMatcher> {
                add(fileSystem.getPathMatcher("glob:$pattern"))
                // Let "**/*.kt" also match files directly in the root, as most tools do.
                if (pattern.startsWith("**/")) add(fileSystem.getPathMatcher("glob:${pattern.removePrefix("**/")}"))
            }
        } catch (e: IllegalArgumentException) {
            return failure("Invalid glob pattern '$pattern': ${e.message}")
        }

        return withContext(Dispatchers.IO) { find(pattern, matchers) }
    }

    private fun find(pattern: String, matchers: List<PathMatcher>): ToolOutput {
        val matches = mutableListOf<String>()

        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.name in IGNORED_DIRECTORIES) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(file)
                if (matchers.any { it.matches(relative) }) matches += relative.invariantSeparatorsPathString
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })

        if (matches.isEmpty()) return ToolOutput("No files match '$pattern'.")

        matches.sort()
        val shown = matches.take(MAX_RESULTS).joinToString("\n")
        val note = if (matches.size > MAX_RESULTS) {
            "\n[Showing the first $MAX_RESULTS of ${matches.size} matches. Use a more specific pattern.]"
        } else ""
        return ToolOutput(shown + note)
    }
}
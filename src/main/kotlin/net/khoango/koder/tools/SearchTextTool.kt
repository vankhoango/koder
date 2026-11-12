package net.khoango.koder.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.domain.objectSchema
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlin.io.path.absolute
import kotlin.io.path.fileSize
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readBytes

val searchTextDefinition = ToolDefinition(
    name = "search_text",
    description = """
        Search the contents of the project's text files with a regular expression, and return the matching
        lines as "path:line: text". Use this to find where something is defined or used, e.g. "class Agent\b".
        The pattern uses Java regex syntax: to match ( ) . * and similar characters literally, escape them
        with a backslash, e.g. "fun execute\(".
        To search only certain files, pass a glob in files, e.g. "**/*.kt".
        Generated folders (.git, .gradle, .idea, .kotlin, build) and binary files are skipped.
        Use read_file afterward to see the code around a match.
    """.trimIndent(),
    inputSchema = objectSchema {
        string("pattern", "The regular expression to search for.", required = true)
        string("files", "Optional glob limiting which files are searched, e.g. \"**/*.kt\". Defaults to all files.")
        boolean("ignore_case", "Match regardless of upper and lower case. Defaults to false.")
    },
)

class SearchTextTool(projectRoot: Path) : Tool {

    private val root = projectRoot.toAbsolutePath().normalize()

    override val definition = searchTextDefinition

    override suspend fun execute(input: JsonObject): ToolOutput {
        val pattern = input.stringParam("pattern") ?: return failure("Missing required parameter: pattern")
        val filesGlob = input.stringParam("files")
        val ignoreCase = input.booleanParam("ignore_case") ?: false

        val regex = try {
            if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        } catch (e: IllegalArgumentException) {
            return failure("Invalid regular expression '$pattern': ${e.message}")
        }
        val matchers = try {
            filesGlob?.let { globMatchers(it) }
        } catch (e: IllegalArgumentException) {
            return failure("Invalid files glob '$filesGlob': ${e.message}")
        }

        return withContext(Dispatchers.IO) { search(pattern, regex, matchers) }
    }

    private fun search(pattern: String, regex: Regex, matchers: List<PathMatcher>?): ToolOutput {
        val results = mutableListOf<String>()
        var total = 0

        walkProjectFiles(root) { file ->
            val relative = root.relativize(file)
            if (matchers != null && matchers.none { it.matches(relative) }) return@walkProjectFiles
            val lines = readTextLines(file) ?: return@walkProjectFiles

            lines.forEachIndexed { index, line ->
                if (regex.containsMatchIn(line)) {
                    total++
                    if (results.size < MAX_MATCHES) {
                        results += "${relative.invariantSeparatorsPathString}:${index + 1}: ${line.trim().take(MAX_LINE_LENGTH)}"
                    }
                }
            }
        }

        if (results.isEmpty()) return ToolOutput("No matches for '$pattern'.")

        val note = if (total > MAX_MATCHES) {
            "\n[Showing the first $MAX_MATCHES of $total matching lines. Use a more specific pattern or a files glob.]"
        } else ""
        return ToolOutput(results.joinToString("\n") + note)
    }

    /** Returns the file's lines, or null if it's too large, binary, or unreadable. */
    private fun readTextLines(file: Path): List<String>? = try {
        if (file.fileSize() > MAX_FILE_BYTES) null
        else file.readBytes().takeUnless { 0.toByte() in it }?.toString(Charsets.UTF_8)?.lines()
    } catch (e: IOException) {
        null
    }

    companion object {
        private const val MAX_MATCHES = 100
        private const val MAX_LINE_LENGTH = 200
        private const val MAX_FILE_BYTES = 1_000_000L
    }
}
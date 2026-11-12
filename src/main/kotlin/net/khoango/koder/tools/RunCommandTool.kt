package net.khoango.koder.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.domain.objectSchema
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RunCommandTool(
    private val projectRoot: Path,
    private val approve: suspend (String) -> Boolean,
) : Tool {

    companion object {
        private const val MAX_OUTPUT_CHARS = 10_000
    }

    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val shellName = if (isWindows) "Windows PowerShell" else "bash"

    override val definition = ToolDefinition(
        name = "run_command",
        description = """
            Run a shell command in the project root and return its exit code and output (stdout and stderr combined).
            The shell is $shellName, so use its syntax.
            Use this to build the project, run tests, or run git commands.
            The user must approve each command before it runs, and may decline.
            Commands can't read input, so don't run interactive programs such as editors or prompts.
            To read, list, or edit files, use the file tools instead of shell commands.
        """.trimIndent(),
        inputSchema = objectSchema {
            string("command", "The command to run.", required = true)
        },
    )

    override suspend fun execute(input: JsonObject): ToolOutput {
        val command = input.stringParam("command") ?: return failure("Missing required parameter: command")
        if (!approve(command)) {
            return failure("The user declined to run this command. Ask the user how to proceed, or try a different approach.")
        }
        return withContext(Dispatchers.IO) { runCommand(command) }
    }

    private fun runCommand(command: String): ToolOutput {
        val outputFile = createTempFile("koder-command-", ".log")
        val scriptFile = if (isWindows) createTempFile("koder-command-", ".ps1") else null
        try {
            val shellCommand = if (scriptFile != null) {
                scriptFile.writeText("\uFEFF" + powerShellScript(command))   // the BOM tells PowerShell 5.1 the file is UTF-8
                listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString())
            } else {
                listOf("bash", "-c", command)
            }

            val process = ProcessBuilder(shellCommand)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start()
            process.outputStream.close()

            val exitCode = process.waitFor()
            val text = outputFile.readText(Charsets.UTF_8).normalizeLineEndings().ifBlank { "(no output)" }
            return ToolOutput("Exit code: $exitCode\n${limitOutput(text)}", isError = exitCode != 0)
        } finally {
            runCatching { outputFile.deleteIfExists() }
            runCatching { scriptFile?.deleteIfExists() }
        }
    }

    private fun powerShellScript(command: String): String =
        "\$ProgressPreference = 'SilentlyContinue'\n" +
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n" +
                command + "\n" +
                "if (-not \$?) { if (\$LASTEXITCODE) { exit \$LASTEXITCODE } else { exit 1 } }\n"

    private fun limitOutput(text: String): String {
        if (text.length <= MAX_OUTPUT_CHARS) return text
        return "[Output truncated: showing the last $MAX_OUTPUT_CHARS of ${text.length} characters.]\n" +
                text.takeLast(MAX_OUTPUT_CHARS)
    }

    private fun shellCommand(command: String): List<String> {
        if (!isWindows) {
            return listOf("bash", "-c", command)
        }
        // Make PowerShell write UTF-8, then pass the script Base64-encoded to avoid all quoting problems.
        val script = "\$ProgressPreference = 'SilentlyContinue'\n" +
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n" +
                command
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
    }
}
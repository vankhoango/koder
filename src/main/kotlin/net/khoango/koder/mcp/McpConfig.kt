package net.khoango.koder.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

enum class ConfigSource { USER, PROJECT }

/** One MCP server to connect: a name chosen by the user, the command that starts it, and its environment. */
data class McpServerConfig(
    val name: String,
    val command: List<String>,
    val env: Map<String, String> = emptyMap(),
    val source: ConfigSource = ConfigSource.USER,
)

data class McpConfigResult(val servers: List<McpServerConfig>, val warnings: List<String>)

private val VARIABLE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""")

/**
 * Replaces `${NAME}` in the server's command and environment with environment variables.
 * Returns the expanded configuration and the names of any missing variables.
 */
fun McpServerConfig.expandVariables(
    environment: Map<String, String> = System.getenv(),
): Pair<McpServerConfig, Set<String>> {
    val missing = mutableSetOf<String>()
    fun expand(text: String) = VARIABLE.replace(text) { match ->
        val name = match.groupValues[1]
        environment[name] ?: "".also { missing += name }
    }
    return copy(command = command.map(::expand), env = env.mapValues { (_, value) -> expand(value) }) to missing
}

@Serializable
private data class McpConfigFile(val mcpServers: Map<String, McpServerEntry> = emptyMap())

@Serializable
private data class McpServerEntry(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val disabled: Boolean = false,
)

private val configJson = Json { ignoreUnknownKeys = true }

/**
 * Loads personal servers from `~/.koder/mcp.json`, then project servers from `.koder/mcp.json`,
 * which replace personal servers with the same name.
 */
fun loadMcpConfig(
    projectRoot: Path,
    userHome: Path = Path(System.getProperty("user.home")),
): McpConfigResult {
    val servers = linkedMapOf<String, McpServerConfig>()
    val warnings = mutableListOf<String>()

    val files = listOf(
        userHome.resolve(".koder").resolve("mcp.json") to ConfigSource.USER,
        projectRoot.resolve(".koder").resolve("mcp.json") to ConfigSource.PROJECT,
    )

    for ((file, source) in files) {
        if (!file.exists()) continue
        val config = try {
            configJson.decodeFromString<McpConfigFile>(file.readText())
        } catch (e: SerializationException) {
            warnings += "Skipped $file: ${e.message}"
            continue
        } catch (e: IllegalArgumentException) {
            warnings += "Skipped $file: ${e.message}"
            continue
        }

        for ((name, entry) in config.mcpServers) {
            if (entry.disabled) {
                servers.remove(name)
                continue
            }
            servers[name] = McpServerConfig(
                name = name,
                command = listOf(resolveCommand(entry.command, projectRoot, source)) + entry.args,
                env = entry.env,
                source = source,
            )
        }
    }
    return McpConfigResult(servers.values.toList(), warnings)
}

/**
 * Project commands with a path, like "mcp-demo/.venv/Scripts/python", are resolved against the project root,
 * so they work whatever folder Koder was started from. Plain names like "py" are looked up in PATH as usual.
 */
private fun resolveCommand(command: String, projectRoot: Path, source: ConfigSource): String {
    val hasPath = '/' in command || '\\' in command
    if (!hasPath || source != ConfigSource.PROJECT || Path(command).isAbsolute) return command
    return projectRoot.resolve(command).normalize().toString()
}
package net.khoango.koder.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.khoango.koder.agent.Agent
import net.khoango.koder.agent.AgentEvent
import net.khoango.koder.agent.SubagentRunner
import net.khoango.koder.cli.askPermission
import net.khoango.koder.cli.printEvent
import net.khoango.koder.cli.printResumed
import net.khoango.koder.cli.printRetry
import net.khoango.koder.cli.printSubagentEvent
import net.khoango.koder.llm.ChatCompletionsClient
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.llm.LlmConfig
import net.khoango.koder.llm.RetryingLlmClient
import net.khoango.koder.mcp.ConfigSource
import net.khoango.koder.mcp.HttpTransport
import net.khoango.koder.mcp.McpClient
import net.khoango.koder.mcp.McpException
import net.khoango.koder.mcp.McpServerConfig
import net.khoango.koder.mcp.McpTool
import net.khoango.koder.mcp.expandVariables
import net.khoango.koder.mcp.loadMcpConfig
import net.khoango.koder.permissions.PermissionPolicy
import net.khoango.koder.prompt.BASE_SYSTEM_PROMPT
import net.khoango.koder.prompt.buildSystemPrompt
import net.khoango.koder.session.SessionStore
import net.khoango.koder.skills.Skill
import net.khoango.koder.skills.loadSkills
import net.khoango.koder.tools.EditFileTool
import net.khoango.koder.tools.FileTracker
import net.khoango.koder.tools.FindFilesTool
import net.khoango.koder.tools.ListFilesTool
import net.khoango.koder.tools.ReadFileTool
import net.khoango.koder.tools.RunCommandTool
import net.khoango.koder.tools.SearchTextTool
import net.khoango.koder.tools.Tool
import net.khoango.koder.tools.ToolRegistry
import net.khoango.koder.tools.UseSkillTool
import net.khoango.koder.tools.WriteFileTool
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

data class KoderSettings(
    val llm: LlmConfig,
    val maxSteps: Int,
    val contextLimit: Int?,
    val noTools: Boolean,
    val yolo: Boolean,
    val resume: Boolean,
    val mcpServers: List<McpServerConfig> = emptyList(),
)

/** A problem that prevents Koder from starting, with a message for the user. */
class StartupException(message: String) : Exception(message)

class KoderApp private constructor(
    val agent: Agent,
    val projectRoot: Path,
    val fileTracker: FileTracker,
    val skills: List<Skill>,
    val subagents: SubagentRunner,
    private val modelCanUseSkills: Boolean,
    private val client: LlmClient,
    private val mcpClients: List<McpClient>,
    private val scope: CoroutineScope,
) : AutoCloseable {

    fun reloadSystemPrompt() {
        val reloaded = buildSystemPrompt(BASE_SYSTEM_PROMPT, projectRoot, skills, modelCanUseSkills)
        reloaded.memoryFiles.forEach { println("[memory reloaded: $it]") }
        agent.systemPrompt = reloaded.text
    }

    override fun close() {
        mcpClients.forEach { runCatching { it.close() } }
        scope.cancel()
        client.close()
    }

    companion object {
        suspend fun start(
            settings: KoderSettings,
            projectRoot: Path,
            approveProjectServer: (McpServerConfig) -> Boolean = { false },
        ): KoderApp {
            val fileTracker = FileTracker()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val skillResult = loadSkills(projectRoot)
            skillResult.warnings.forEach { println("[warning] $it") }
            if (skillResult.skills.isNotEmpty()) println("[skills: ${skillResult.skills.joinToString { it.name }}]")

            val mcpConnections = if (settings.noTools) emptyList() else {
                val config = loadMcpConfig(projectRoot)
                config.warnings.forEach { println("[warning] $it") }

                // Servers from --mcp replace configured servers with the same name.
                val servers = (config.servers.associateBy { it.name } + settings.mcpServers.associateBy { it.name }).values
                servers
                    .filter { it.source != ConfigSource.PROJECT || approveProjectServer(it) }
                    .mapNotNull { connectMcpServer(it, scope, projectRoot) }
            }

            lateinit var subagents: SubagentRunner

            val tools = if (settings.noTools) emptyList() else buildList {
                add(ReadFileTool(projectRoot, fileTracker))
                add(ListFilesTool(projectRoot))
                add(WriteFileTool(projectRoot, fileTracker))
                add(EditFileTool(projectRoot, fileTracker))
                add(RunCommandTool(projectRoot))
                add(FindFilesTool(projectRoot))
                add(SearchTextTool(projectRoot))
                if (skillResult.skills.isNotEmpty()) {
                    add(UseSkillTool(skillResult.skills, projectRoot) { skill, input -> subagents.run(skill, input) })
                }
                mcpConnections.forEach { (_, mcpTools) -> addAll(mcpTools) }
            }
            val modelCanUseSkills = tools.any { it is UseSkillTool }

            if (settings.yolo) println("⚠ --yolo: all actions are allowed without asking. Dangerous actions are still blocked.")
            val registry = ToolRegistry(tools, PermissionPolicy(settings.yolo), ::askPermission)

            val systemPrompt = buildSystemPrompt(BASE_SYSTEM_PROMPT, projectRoot, skillResult.skills, modelCanUseSkills)
            systemPrompt.memoryFiles.forEach { println("[memory: $it]") }

            val resumed = if (settings.resume) resumeLatest(projectRoot) else null
            val sessionStore = resumed?.store ?: SessionStore.newSession(projectRoot)

            val client = RetryingLlmClient(ChatCompletionsClient(settings.llm), onRetry = ::printRetry)
            val agent = Agent(
                client, registry, systemPrompt.text, settings.maxSteps,
                contextLimit = settings.contextLimit,
                onStateChanged = sessionStore::save,
            ) { event ->
                if (event is AgentEvent.Compacted) fileTracker.clearKnown()
                printEvent(event)
            }

            subagents = SubagentRunner(
                llm = client,
                tools = registry,
                projectRoot = projectRoot,
                systemPrompt = { agent.systemPrompt },
                maxSteps = settings.maxSteps,
                onEvent = ::printSubagentEvent,
                onUsage = { agent.addUsage(it) },
            )

            resumed?.let {
                agent.restore(it.state)
                printResumed(it)
            }

            return KoderApp(
                agent, projectRoot, fileTracker, skillResult.skills, subagents, modelCanUseSkills, client,
                mcpClients = mcpConnections.map { it.first },
                scope = scope,
            )

        }

        private fun httpUri(url: String): URI {
            val uri = try {
                URI(url)
            } catch (e: URISyntaxException) {
                throw McpException("'$url' isn't a valid URL.")
            }
            if (uri.scheme !in setOf("http", "https")) throw McpException("'$url' must start with http:// or https://.")
            return uri
        }

        /** Connects one MCP server and lists its tools, or prints a warning and returns null. */
        private suspend fun connectMcpServer(
            server: McpServerConfig,
            scope: CoroutineScope,
            projectRoot: Path,
        ): Pair<McpClient, List<Tool>>? {
            val (expanded, missing) = server.expandVariables()
            if (missing.isNotEmpty()) {
                println("[warning] MCP server '${server.name}': these environment variables aren't set: ${missing.joinToString()}")
            }

            val client = try {
                if (expanded.url != null) {
                    McpClient.connect(HttpTransport(httpUri(expanded.url), expanded.headers))
                } else {
                    McpClient.connect(expanded.command, scope, projectRoot, expanded.env)
                }
            } catch (e: McpException) {
                println("[warning] MCP server '${server.name}' was skipped: ${e.message}")
                return null
            }

            return try {
                val tools = client.listTools().map { McpTool(server.name, client, it) }
                println("[mcp: ${server.name}: ${tools.joinToString { it.definition.name }}]")
                client to tools
            } catch (e: McpException) {
                client.close()
                println("[warning] MCP server '${server.name}' was skipped: ${e.message}")
                null
            }
        }

        private fun resumeLatest(projectRoot: Path): SessionStore.Resumed =
            try {
                SessionStore.resumeLatest(projectRoot)
                    ?: throw StartupException("No saved session found in this project.")
            } catch (e: IllegalArgumentException) {
                throw StartupException("Could not read the latest session: ${e.message}")
            }
    }
}
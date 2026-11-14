package net.khoango.koder.app

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
import net.khoango.koder.tools.ToolRegistry
import net.khoango.koder.tools.UseSkillTool
import net.khoango.koder.tools.WriteFileTool
import java.nio.file.Path

data class KoderSettings(
    val llm: LlmConfig,
    val maxSteps: Int,
    val contextLimit: Int?,
    val noTools: Boolean,
    val yolo: Boolean,
    val resume: Boolean,
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
) : AutoCloseable {

    fun reloadSystemPrompt() {
        val reloaded = buildSystemPrompt(BASE_SYSTEM_PROMPT, projectRoot, skills, modelCanUseSkills)
        reloaded.memoryFiles.forEach { println("[memory reloaded: $it]") }
        agent.systemPrompt = reloaded.text
    }

    override fun close() = client.close()

    companion object {
        fun start(settings: KoderSettings, projectRoot: Path): KoderApp {
            val fileTracker = FileTracker()

            val skillResult = loadSkills(projectRoot)
            skillResult.warnings.forEach { println("[warning] $it") }
            if (skillResult.skills.isNotEmpty()) println("[skills: ${skillResult.skills.joinToString { it.name }}]")

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

            return KoderApp(agent, projectRoot, fileTracker, skillResult.skills, subagents, modelCanUseSkills, client)
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
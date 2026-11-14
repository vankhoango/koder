package net.khoango.koder.agent

import net.khoango.koder.domain.Role
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.llm.Usage
import net.khoango.koder.skills.Skill
import net.khoango.koder.skills.skillMessage
import net.khoango.koder.tools.ToolRegistry
import java.nio.file.Path

/** Runs a skill in a fresh agent and returns its final report. */
class SubagentRunner(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val projectRoot: Path,
    private val systemPrompt: () -> String,
    private val maxSteps: Int,
    private val onEvent: (skillName: String, event: AgentEvent) -> Unit,
    private val onUsage: (Usage) -> Unit = {},
) {
    suspend fun run(skill: Skill, input: String): String {
        val subagent = Agent(
            llm,
            tools.restrictedTo(skill.allowedTools, excluded = setOf("use_skill")),
            systemPrompt() + SUBAGENT_ROLE,
            maxSteps,
        ) { event -> onEvent(skill.name, event) }

        onEvent(skill.name, AgentEvent.SubagentStarted)
        try {
            subagent.run(skillMessage(skill, projectRoot, input, invokedByModel = true))
        } finally {
            onUsage(subagent.usage)
            onEvent(skill.name, AgentEvent.SubagentFinished)
        }

        val report = subagent.snapshot().history
            .lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }
            ?.text
        return report ?: "(The subagent finished without a report.)"
    }

    companion object {
        private val SUBAGENT_ROLE = """


            # Your role
            You are a subagent working for another agent. Complete the task in the next message.
            Your final answer is your report: it will be passed to the other agent, and nothing else you did will be.
            Make it complete and concise.
        """.trimIndent()
    }
}
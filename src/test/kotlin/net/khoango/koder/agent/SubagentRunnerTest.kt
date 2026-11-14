package net.khoango.koder.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.llm.FakeLlmClient
import net.khoango.koder.llm.Usage
import net.khoango.koder.llm.toolCall
import net.khoango.koder.permissions.Answer
import net.khoango.koder.permissions.PermissionPolicy
import net.khoango.koder.skills.Skill
import net.khoango.koder.tools.Tool
import net.khoango.koder.tools.ToolOutput
import net.khoango.koder.tools.ToolRegistry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SubagentRunnerTest {

    @TempDir
    lateinit var root: Path

    private val usages = mutableListOf<Usage>()

    private fun runner(llm: FakeLlmClient) = SubagentRunner(
        llm = llm,
        tools = ToolRegistry(
            listOf(NamedTool("read_file"), NamedTool("write_file"), NamedTool("use_skill")),
            PermissionPolicy(),
            askUser = { Answer.YES },
        ),
        projectRoot = root,
        systemPrompt = { "Test system prompt" },
        maxSteps = 10,
        onEvent = { _, _ -> },
        onUsage = { usages += it },
    )

    @Test
    fun `returns the last answer as the report and counts its usage`() = runTest {
        val llm = FakeLlmClient(
            toolCall("call_1", "read_file", "path" to "Main.kt"),
            Message.assistant("Report: all good."),
        )

        val report = runner(llm).run(Skill("review", "Review code.", "Review the code.", root), "")

        assertEquals("Report: all good.", report)
        assertEquals(Usage(20, 10), usages.single())   // two requests of (10 in, 5 out) each
    }

    @Test
    fun `offers only the allowed tools, and never use_skill`() = runTest {
        val llm = FakeLlmClient(Message.assistant("Done."))
        val skill = Skill("review", "Review code.", "Review the code.", root, allowedTools = setOf("read_file", "use_skill"))

        runner(llm).run(skill, "")

        assertEquals(listOf("read_file"), llm.toolNames.single())
    }

    @Test
    fun `starts with an empty history every time`() = runTest {
        val llm = FakeLlmClient(Message.assistant("First report."), Message.assistant("Second report."))
        val runner = runner(llm)
        val skill = Skill("review", "Review code.", "Review the code.", root)

        runner.run(skill, "")
        runner.run(skill, "")

        assertEquals(1, llm.requests[1].size)   // only the skill message, nothing from the first run
    }
}

/** A tool that does nothing; only its name matters for these tests. */
private class NamedTool(name: String) : Tool {
    override val definition = ToolDefinition(name, "A test tool.", JsonObject(emptyMap()))
    override suspend fun execute(input: JsonObject) = ToolOutput("ok")
}
package net.khoango.koder.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.llm.FakeLlmClient
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.llm.toolCall
import net.khoango.koder.permissions.Answer
import net.khoango.koder.permissions.PermissionPolicy
import net.khoango.koder.tools.Tool
import net.khoango.koder.tools.ToolOutput
import net.khoango.koder.tools.ToolRegistry
import net.khoango.koder.tools.stringParam
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentTest {

    private val events = mutableListOf<AgentEvent>()

    @Test
    fun `streams a text answer`() = runTest {
        val llm = FakeLlmClient(Message.assistant("Hello! How can I help?"))

        agent(llm).run("Hi")

        val streamed = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello! How can I help?", streamed)
        assertEquals(AgentEvent.TextEnd, events.last())
        assertEquals(listOf(Message.user("Hi")), llm.requests.single())
    }

    @Test
    fun `sends the previous turns with each new request`() = runTest {
        val llm = FakeLlmClient(Message.assistant("First answer"), Message.assistant("Second answer"))
        val agent = agent(llm)

        agent.run("First question")
        agent.run("Second question")

        assertEquals(
            listOf(
                Message.user("First question"),
                Message.assistant("First answer"),
                Message.user("Second question"),
            ),
            llm.requests[1],
        )
    }

    @Test
    fun `executes a tool call and sends the result back`() = runTest {
        val echo = EchoTool()
        val llm = FakeLlmClient(
            toolCall("call_1", "echo", "text" to "hello"),
            Message.assistant("The tool said hello."),
        )

        agent(llm, listOf(echo)).run("Use the echo tool")

        assertEquals("hello", echo.inputs.single().stringParam("text"))
        assertEquals(
            ContentBlock.ToolResult("call_1", "echo", "echo: hello"),
            llm.requests[1].last().content.single(),
        )
        assertTrue(events.any { it is AgentEvent.ToolCalled })
        assertTrue(events.any { it is AgentEvent.ToolFinished })
    }

    @Test
    fun `stops at the step limit and asks the model for a summary`() = runTest {
        val echo = EchoTool()
        val llm = FakeLlmClient(
            toolCall("call_1", "echo", "text" to "one"),
            toolCall("call_2", "echo", "text" to "two"),
            Message.assistant("Summary of what I found."),
        )

        agent(llm, listOf(echo), maxSteps = 1).run("Do a long task")

        assertEquals(1, echo.inputs.size)   // the second call was not executed
        assertTrue(AgentEvent.StepLimitReached(1) in events)

        val refusal = llm.requests[2].last().content.single() as ContentBlock.ToolResult
        assertEquals("call_2", refusal.toolUseId)
        assertTrue(refusal.isError)
        assertContains(refusal.content, "limit")
    }

    @Test
    fun `abandons the turn when the model ignores the limit, without saving it`() = runTest {
        val llm = FakeLlmClient(
            toolCall("call_1", "echo", "text" to "one"),
            toolCall("call_2", "echo", "text" to "two"),
            toolCall("call_3", "echo", "text" to "three"),
            Message.assistant("OK"),
        )
        val agent = agent(llm, listOf(EchoTool()), maxSteps = 1)

        agent.run("Do a long task")
        assertEquals(AgentEvent.Abandoned, events.last())

        agent.run("Next question")
        assertEquals(listOf(Message.user("Next question")), llm.requests.last())
    }

    @Test
    fun `keeps the history unchanged when a request fails mid-turn`() = runTest {
        val llm = FakeLlmClient(toolCall("call_1", "echo", "text" to "hello"))   // no second response: the next request fails
        val agent = agent(llm, listOf(EchoTool()))

        assertFailsWith<IllegalStateException> { agent.run("First question") }

        llm.enqueue(Message.assistant("Recovered."))
        agent.run("Second question")
        assertEquals(listOf(Message.user("Second question")), llm.requests.last())
    }

    @Test
    fun `compact replaces the history with a summary`() = runTest {
        val llm = FakeLlmClient(
            Message.assistant("First answer"),
            Message.assistant("Summary: the user asked a first question."),
            Message.assistant("Second answer"),
        )
        val agent = agent(llm)

        agent.run("First question")
        assertTrue(agent.compact())
        agent.run("Second question")

        assertEquals(listOf(Message.user("Second question")), llm.requests.last())
        assertTrue(events.any { it is AgentEvent.Compacted })
    }

    @Test
    fun `compacts automatically when the context is nearly full`() = runTest {
        val llm = FakeLlmClient(
            Message.assistant("First answer"),
            Message.assistant("Summary of the first exchange."),
            Message.assistant("Second answer"),
        )
        val agent = agent(llm, contextLimit = 10)

        agent.run("First question")
        agent.run("Second question")

        assertTrue(events.any { it is AgentEvent.AutoCompacting })
        assertEquals(listOf(Message.user("Second question")), llm.requests.last())
    }

    @Test
    fun `does not compact while there is enough room`() = runTest {
        val llm = FakeLlmClient(Message.assistant("First answer"), Message.assistant("Second answer"))
        val agent = agent(llm, contextLimit = 1_000)

        agent.run("First question")
        agent.run("Second question")

        assertTrue(events.none { it is AgentEvent.Compacted })
        assertEquals(3, llm.requests.last().size)   // the full history was sent
    }

    private fun agent(llm: LlmClient, tools: List<Tool> = emptyList(), maxSteps: Int = 25, contextLimit: Int? = null) =
        Agent(
            llm,
            ToolRegistry(tools, PermissionPolicy(), askUser = { Answer.YES }),
            "Test system prompt",
            maxSteps,
            contextLimit,
        ) { events += it }


}

/** A tool that records its inputs and echoes the "text" parameter. */
private class EchoTool : Tool {
    val inputs = mutableListOf<JsonObject>()

    override val definition = ToolDefinition("echo", "Echoes the given text.", JsonObject(emptyMap()))

    override suspend fun execute(input: JsonObject): ToolOutput {
        inputs += input
        return ToolOutput("echo: ${input.stringParam("text")}")
    }
}
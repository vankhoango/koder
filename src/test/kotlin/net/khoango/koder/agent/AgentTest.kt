package net.khoango.koder.agent

import kotlinx.coroutines.test.runTest
import net.khoango.koder.domain.Message
import net.khoango.koder.llm.FakeLlmClient
import net.khoango.koder.llm.LlmClient
import net.khoango.koder.tools.Tool
import net.khoango.koder.tools.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentTest {

    private val events = mutableListOf<AgentEvent>()

    private fun agent(llm: LlmClient, tools: List<Tool> = emptyList(), maxSteps: Int = 25) =
        Agent(llm, ToolRegistry(tools), "Test system prompt", maxSteps) { events += it }

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
}
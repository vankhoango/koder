package net.khoango.koder.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.toString

class McpClientTest {

    private val demoPython = Path("mcp-demo/.venv/Scripts/python.exe").absolute()
    private val demoServer = Path("mcp-demo/server.py").absolute()
    private val fakeServer = Path("src/test/resources/fake_mcp_server.py").absolute()

    @Test
    fun `connects to a modern server`() = runBlocking {
        assumeTrue(demoPython.exists(), "The demo server's virtual environment isn't set up.")

        McpClient.connect(listOf(demoPython.toString(), demoServer.toString()), this).use { client ->
            assertEquals("koder-demo", client.info.name)
        }
    }

    @Test
    fun `rejects a server that isn't modern`() = runBlocking {
        val error = assertFailsWith<McpException> {
            McpClient.connect(listOf("py", fakeServer.toString()), this)
        }

        assertContains(error.message.orEmpty(), "older, handshake-based protocol")
    }

    @Test
    fun `reports a command that can't start`() = runBlocking {
        val error = assertFailsWith<McpException> {
            McpClient.connect(listOf("no-such-program-for-koder"), this)
        }

        assertContains(error.message.orEmpty(), "Could not start")
    }

    @Test
    fun `lists the tools of the demo server`() = runBlocking {
        assumeTrue(demoPython.exists(), "The demo server's virtual environment isn't set up.")

        McpClient.connect(listOf(demoPython.toString(), demoServer.toString()), this).use { client ->
            val tools = client.listTools()

            assertEquals(setOf("add", "word_count"), tools.map { it.name }.toSet())

            val add = tools.first { it.name == "add" }
            assertEquals("Add two whole numbers.", add.description)
            assertEquals(setOf("a", "b"), (add.inputSchema["properties"] as JsonObject).keys)
        }
    }

    @Test
    fun `calls a tool and reads its text result`() = runBlocking {
        assumeTrue(demoPython.exists(), "The demo server's virtual environment isn't set up.")

        McpClient.connect(listOf(demoPython.toString(), demoServer.toString()), this).use { client ->
            val result = client.callTool("add", buildJsonObject { put("a", 17); put("b", 25) })

            assertEquals("42", result.text)
            assertFalse(result.isError)
        }
    }

    @Test
    fun `reports an unknown tool as an error`() = runBlocking {
        assumeTrue(demoPython.exists(), "The demo server's virtual environment isn't set up.")

        McpClient.connect(listOf(demoPython.toString(), demoServer.toString()), this).use { client ->
            val outcome = runCatching { client.callTool("no_such_tool", JsonObject(emptyMap())) }

            assertTrue(outcome.isFailure || outcome.getOrThrow().isError, "An unknown tool must not look like a success.")
        }
    }

    @Test
    fun `an MCP tool works like any other Koder tool`() = runBlocking {
        assumeTrue(demoPython.exists(), "The demo server's virtual environment isn't set up.")

        McpClient.connect(listOf(demoPython.toString(), demoServer.toString()), this).use { client ->
            val info = client.listTools().first { it.name == "word_count" }
            val tool = McpTool("demo", client, info)

            val output = tool.execute(buildJsonObject { put("text", "the quick brown fox") })

            assertEquals("mcp__demo__word_count", tool.definition.name)
            assertEquals("4", output.content)
            assertFalse(output.isError)
        }
    }
}
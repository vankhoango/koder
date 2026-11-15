package net.khoango.koder.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
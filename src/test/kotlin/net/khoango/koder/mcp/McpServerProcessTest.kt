package net.khoango.koder.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class JsonRpcConnectionTest {

    private val fakeServer = Path("src/test/resources/fake_mcp_server.py").absolute().toString()

    @Test
    fun `matches each response to its request`() = runBlocking {
        McpServerProcess.start(listOf("py", fakeServer), this).use { server ->
            val first = async {
                server.connection.request("test/echo", buildJsonObject { put("n", 1) })
            }
            val second = async {
                server.connection.request("test/echo", buildJsonObject { put("n", 2) })
            }
            assertEquals("1",
                first.await().jsonObject["echo"]?.jsonObject?.get("n")?.jsonPrimitive?.content
            )
            assertEquals("2",
                second.await().jsonObject["echo"]?.jsonObject?.get("n")?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun `turns error responses into exceptions`() = runBlocking {
        McpServerProcess.start(listOf("py", fakeServer), this).use { server ->
            val error = assertFailsWith<McpException> {
                server.connection.request("test/fail", buildJsonObject {})
            }

            assertEquals(-32000, error.code)
            assertEquals("Failure on purpose", error.message)
        }
    }

    @Test
    fun `fails immediately when the server stops`() = runBlocking {
        McpServerProcess.start(listOf("py", fakeServer), this).use { server ->
            val error = assertFailsWith<McpException> {
                server.connection.request("test/exit", buildJsonObject {}, timeout = 20.seconds)
            }

            assertContains(error.message.orEmpty(), "closed the connection")
        }
    }

    @Test
    fun `ignores notifications from the server`() = runBlocking {
        McpServerProcess.start(listOf("py", fakeServer), this).use { server ->
            val result = server.connection.request("test/notify-first", buildJsonObject { put("n", 3) })

            assertEquals("3", result.jsonObject["echo"]?.jsonObject?.get("n")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `stops a server that ignores its closed input`() = runBlocking {
        val server = McpServerProcess.start(listOf("py", fakeServer, "stubborn"), this, shutdownTimeout = 1.seconds)

        server.close()

        assertFalse(server.process.isAlive)
    }
}
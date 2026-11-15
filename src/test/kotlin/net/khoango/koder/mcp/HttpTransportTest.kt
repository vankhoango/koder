package net.khoango.koder.mcp

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class HttpTransportTest {

    private lateinit var server: HttpServer
    private val receivedHeaders = CopyOnWriteArrayList<Headers>()

    /** Starts a server on a free port that always answers with the given status, type, and body. */
    private fun startServer(status: Int, contentType: String, body: String): URI {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            receivedHeaders += exchange.requestHeaders
            exchange.requestBody.readAllBytes()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return URI("http://127.0.0.1:${server.address.port}/mcp")
    }

    @AfterTest
    fun stopServer() {
        if (::server.isInitialized) server.stop(0)
    }

    private val toolCallParams = buildJsonObject {
        put("name", "add")
        put("_meta", buildJsonObject { put("io.modelcontextprotocol/protocolVersion", "2026-07-28") })
    }

    @Test
    fun `sends the required headers and reads a JSON result`() = runBlocking {
        val url = startServer(200, "application/json", """{"jsonrpc":"2.0","id":1,"result":{"ok":true}}""")

        val result = HttpTransport(url).use { it.request("tools/call", toolCallParams, 5.seconds) }

        assertEquals("true", result.jsonObject["ok"]?.jsonPrimitive?.content)
        val headers = receivedHeaders.single()
        assertEquals("2026-07-28", headers.getFirst("MCP-Protocol-Version"))
        assertEquals("tools/call", headers.getFirst("Mcp-Method"))
        assertEquals("add", headers.getFirst("Mcp-Name"))
    }

    @Test
    fun `keeps the JSON-RPC error code of an HTTP 400 response`() = runBlocking {
        val url = startServer(
            400, "application/json",
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32022,"message":"Unsupported protocol version","data":{"supported":["2027-01-01"]}}}""",
        )

        val error = assertFailsWith<McpException> {
            HttpTransport(url).use { it.request("server/discover", toolCallParams, 5.seconds) }
        }

        assertEquals(-32022, error.code)
    }

    @Test
    fun `encodes header values that aren't plain ASCII`() {
        assertEquals("add", encodeHeaderValue("add"))
        assertEquals("=?base64?SGVsbG8sIOS4lueVjA==?=", encodeHeaderValue("Hello, 世界"))
        assertEquals("=?base64?IHBhZGRlZCA=?=", encodeHeaderValue(" padded "))
    }

    @Test
    fun `reads the final response from an event stream`() {
        val lines = listOf(
            """data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":50}}""",
            "",
            ": keep-alive",
            "",
            "event: message",
            """data: {"jsonrpc":"2.0","id":3,""",
            """data: "result":{"done":true}}""",
            "",
        )

        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":3,\n\"result\":{\"done\":true}}", readFinalSseMessage(lines.iterator()))
    }

    @Test
    fun `fails when the stream ends without a response`() {
        val lines = listOf("""data: {"jsonrpc":"2.0","method":"notifications/progress"}""", "")

        assertFailsWith<McpException> { readFinalSseMessage(lines.iterator()) }
    }

    @Test
    fun `handles a streamed response from the server`() = runBlocking {
        val stream = """
            data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":50}}

            data: {"jsonrpc":"2.0","id":1,"result":{"ok":true}}

        """.trimIndent() + "\n"
        val url = startServer(200, "text/event-stream", stream)

        val result = HttpTransport(url).use { it.request("tools/call", toolCallParams, 5.seconds) }

        assertEquals("true", result.jsonObject["ok"]?.jsonPrimitive?.content)
    }
}
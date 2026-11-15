package net.khoango.koder.mcp

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpConfigTest {

    @TempDir
    lateinit var temp: Path

    private val project by lazy { temp.resolve("project") }
    private val home by lazy { temp.resolve("home") }

    private fun writeConfig(root: Path, json: String) {
        root.resolve(".koder").createDirectories().resolve("mcp.json").writeText(json)
    }

    @Test
    fun `reads servers with their arguments and environment`() {
        writeConfig(project, """
            { "mcpServers": { "demo": { "command": "py", "args": ["server.py"], "env": { "KEY": "x" } } } }
        """.trimIndent())

        val server = loadMcpConfig(project, home).servers.single()

        assertEquals("demo", server.name)
        assertEquals(listOf("py", "server.py"), server.command)
        assertEquals(mapOf("KEY" to "x"), server.env)
        assertEquals(ConfigSource.PROJECT, server.source)
    }

    @Test
    fun `project servers replace personal servers with the same name`() {
        writeConfig(home, """{ "mcpServers": { "demo": { "command": "personal" } } }""")
        writeConfig(project, """{ "mcpServers": { "demo": { "command": "project" } } }""")

        assertEquals(listOf("project"), loadMcpConfig(project, home).servers.single().command)
    }

    @Test
    fun `resolves relative project commands against the project root`() {
        writeConfig(project, """{ "mcpServers": { "demo": { "command": "mcp-demo/python" } } }""")

        val command = loadMcpConfig(project, home).servers.single().command.first()

        assertEquals(project.resolve("mcp-demo/python").normalize().toString(), command)
    }

    @Test
    fun `reports a broken file as a warning`() {
        writeConfig(project, """{ "mcpServers": { "demo": { "args": ["missing command"] } } }""")

        val result = loadMcpConfig(project, home)

        assertTrue(result.servers.isEmpty())
        assertTrue(result.warnings.single().contains("Skipped"))
    }

    @Test
    fun `fills in environment variables and reports missing ones`() {
        val server = McpServerConfig(
            name = "weather",
            command = listOf("py", "--token", "\${TOKEN}"),
            env = mapOf("API_KEY" to "\${API_KEY}", "REGION" to "\${REGION}"),
        )

        val (expanded, missing) = server.expandVariables(mapOf("TOKEN" to "t-123", "API_KEY" to "k-456"))

        assertEquals(listOf("py", "--token", "t-123"), expanded.command)
        assertEquals(mapOf("API_KEY" to "k-456", "REGION" to ""), expanded.env)
        assertEquals(setOf("REGION"), missing)
    }

    @Test
    fun `reads a remote server with headers`() {
        writeConfig(project, """
            { "mcpServers": { "tickets": { "type": "http", "url": "https://t.example.com/mcp",
              "headers": { "Authorization": "Bearer ${'$'}{TOKEN}" } } } }
        """.trimIndent())

        val server = loadMcpConfig(project, home).servers.single()
        val (expanded, _) = server.expandVariables(mapOf("TOKEN" to "secret"))

        assertEquals("https://t.example.com/mcp", expanded.url)
        assertEquals(mapOf("Authorization" to "Bearer secret"), expanded.headers)
        assertTrue(expanded.command.isEmpty())
    }

    @Test
    fun `skips a server with both a command and a url, but keeps the others`() {
        writeConfig(project, """
            { "mcpServers": {
                "broken": { "command": "py", "url": "https://example.com/mcp" },
                "fine": { "command": "py" } } }
        """.trimIndent())

        val result = loadMcpConfig(project, home)

        assertEquals(listOf("fine"), result.servers.map { it.name })
        assertTrue(result.warnings.single().contains("broken"))
    }
}
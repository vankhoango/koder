package net.khoango.koder.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class McpToolTest {

    @Test
    fun `builds a prefixed name`() {
        assertEquals("mcp__demo__add", mcpToolName("demo", "add"))
    }

    @Test
    fun `replaces characters that model APIs don't accept`() {
        assertEquals("mcp__my_server__files_read", mcpToolName("my server", "files.read"))
    }

    @Test
    fun `limits the name to 64 characters`() {
        assertEquals(64, mcpToolName("demo", "x".repeat(100)).length)
    }

}
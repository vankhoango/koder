package net.khoango.koder.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadFileToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var root: Path
    private lateinit var tool: ReadFileTool

    @BeforeTest
    fun setUp() {
        root = tempDir.resolve("project").createDirectories()
        tool = ReadFileTool(root)
    }

    private fun read(path: String): ToolOutput = runBlocking {
        tool.execute(buildJsonObject { put("path", path) })
    }

    @Test
    fun `reads a text file`() {
        root.resolve("hello.txt").writeText("Hello\nWorld")

        val output = read("hello.txt")

        assertFalse(output.isError)
        assertEquals("Hello\nWorld", output.content)
    }

    @Test
    fun `reads a file in a subdirectory`() {
        root.resolve("src/main").createDirectories()
        root.resolve("src/main/App.kt").writeText("fun main() {}")

        val output = read("src/main/App.kt")

        assertFalse(output.isError)
        assertEquals("fun main() {}", output.content)
    }

    @Test
    fun `reports a missing path parameter`() {
        val output = runBlocking { tool.execute(JsonObject(emptyMap())) }

        assertTrue(output.isError)
        assertContains(output.content, "Missing required parameter")
    }

    @Test
    fun `reports a file that does not exist`() {
        val output = read("missing.txt")

        assertTrue(output.isError)
        assertContains(output.content, "File not found")
    }

    @Test
    fun `refuses to escape the project with dot-dot`() {
        tempDir.resolve("secret.txt").writeText("TOP SECRET")

        val output = read("../secret.txt")

        assertTrue(output.isError)
        assertContains(output.content, "outside the project")
        assertFalse(output.content.contains("TOP SECRET"))
    }

    @Test
    fun `refuses an absolute path outside the project`() {
        val secret = tempDir.resolve("secret.txt")
        secret.writeText("TOP SECRET")

        val output = read(secret.toString())

        assertTrue(output.isError)
        assertFalse(output.content.contains("TOP SECRET"))
    }

    @Test
    fun `allows dot-dot that stays inside the project`() {
        root.resolve("src").createDirectories()
        root.resolve("hello.txt").writeText("Hello")

        val output = read("src/../hello.txt")

        assertFalse(output.isError)
        assertEquals("Hello", output.content)
    }

    @Test
    fun `reports a directory`() {
        root.resolve("src").createDirectories()

        val output = read("src")

        assertTrue(output.isError)
        assertContains(output.content, "is a directory")
    }

    @Test
    fun `refuses a binary file`() {
        root.resolve("data.bin").writeBytes(byteArrayOf(0x50, 0x4B, 0x00, 0x03))

        val output = read("data.bin")

        assertTrue(output.isError)
        assertContains(output.content, "binary")
    }

    @Test
    fun `refuses a file that is too large`() {
        root.resolve("big.txt").writeText("x".repeat(1_000_001))

        val output = read("big.txt")

        assertTrue(output.isError)
        assertContains(output.content, "too large")
    }

    @Test
    fun `truncates a long file and says so`() {
        root.resolve("long.txt").writeText((1..2_500).joinToString("\n") { "line $it" })

        val output = read("long.txt")

        assertFalse(output.isError)
        assertContains(output.content, "line 2000")
        assertFalse(output.content.contains("line 2001"))
        assertContains(output.content, "[Truncated: showing the first 2000 of 2500 lines.]")
    }
}
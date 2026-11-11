package net.khoango.koder.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteFileToolTest {

    @TempDir
    lateinit var root: Path

    private val tracker = FileTracker()
    private lateinit var read: ReadFileTool
    private lateinit var write: WriteFileTool

    @BeforeTest
    fun setUp() {
        read = ReadFileTool(root, tracker)
        write = WriteFileTool(root, tracker)
    }

    private fun call(tool: Tool, vararg params: Pair<String, String>): ToolOutput = runBlocking {
        tool.execute(buildJsonObject { params.forEach { (key, value) -> put(key, value) } })
    }

    @Test
    fun `creates a new file without reading`() {
        val output = call(write, "path" to "notes/new.md", "content" to "Hello")

        assertFalse(output.isError)
        assertEquals("Hello", root.resolve("notes/new.md").readText())
    }

    @Test
    fun `refuses to overwrite a file that was never read`() {
        val file = root.resolve("todo.md")
        file.writeText("Original")

        val output = call(write, "path" to "todo.md", "content" to "Changed")

        assertTrue(output.isError)
        assertContains(output.content, "haven't read it")
        assertEquals("Original", file.readText())
    }

    @Test
    fun `refuses to overwrite a file that changed after it was read`() {
        val file = root.resolve("todo.md")
        file.writeText("- Item 1")
        call(read, "path" to "todo.md")
        file.appendText("\n- Item 2")   // simulates an edit by the user

        val output = call(write, "path" to "todo.md", "content" to "- Item 1\n- Item 3")

        assertTrue(output.isError)
        assertContains(output.content, "has changed")
        assertEquals("- Item 1\n- Item 2", file.readText())
    }

    @Test
    fun `allows overwriting after reading again`() {
        val file = root.resolve("todo.md")
        file.writeText("- Item 1")
        call(read, "path" to "todo.md")
        file.appendText("\n- Item 2")
        call(read, "path" to "todo.md")   // the model re-reads and sees Item 2

        val output = call(write, "path" to "todo.md", "content" to "- Item 1\n- Item 2\n- Item 3")

        assertFalse(output.isError)
        assertEquals("- Item 1\n- Item 2\n- Item 3", file.readText())
    }
}
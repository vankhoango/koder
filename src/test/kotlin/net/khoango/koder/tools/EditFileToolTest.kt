package net.khoango.koder.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EditFileToolTest {

    @TempDir
    lateinit var root: Path

    private val tracker = FileTracker()
    private lateinit var read: ReadFileTool
    private lateinit var edit: EditFileTool

    @BeforeTest
    fun setUp() {
        read = ReadFileTool(root, tracker)
        edit = EditFileTool(root, tracker)
    }

    private fun call(tool: Tool, vararg params: Pair<String, String>): ToolOutput = runBlocking {
        tool.execute(buildJsonObject { params.forEach { (key, value) -> put(key, value) } })
    }

    @Test
    fun `edits a CRLF file using LF in old_string and keeps CRLF`() {
        val file = root.resolve("todo.md")
        file.writeText("- Item 1\r\n- Item 2\r\n- Item 3\r\n")
        call(read, "path" to "todo.md")

        val output = call(edit, "path" to "todo.md", "old_string" to "- Item 2\n", "new_string" to "- Item two\n")

        assertFalse(output.isError, output.content)
        assertEquals("- Item 1\r\n- Item two\r\n- Item 3\r\n", file.readText())
    }

    @Test
    fun `allows a second edit right after the first one`() {
        val file = root.resolve("todo.md")
        file.writeText("- Item 1\r\n- Item 2\r\n")
        call(read, "path" to "todo.md")

        call(edit, "path" to "todo.md", "old_string" to "Item 1", "new_string" to "Item one")
        val second = call(edit, "path" to "todo.md", "old_string" to "Item 2", "new_string" to "Item two")

        assertFalse(second.isError, second.content)
        assertEquals("- Item one\r\n- Item two\r\n", file.readText())
    }

    @Test
    fun `reports the line numbers of multiple matches`() {
        val file = root.resolve("todo.md")
        file.writeText("- Item 1\n- Item 2\n- Item 3\n- Remember: Item 1 is urgent\n")
        call(read, "path" to "todo.md")

        val output = call(edit, "path" to "todo.md", "old_string" to "Item 1", "new_string" to "Item one")

        assertTrue(output.isError)
        assertContains(output.content, "appears 2 times")
        assertContains(output.content, "at lines 1 and 4")
    }

    @Test
    fun `shows the actual text when only the indentation differs`() {
        val file = root.resolve("App.kt")
        file.writeText("fun main() {\n    println(\"Hi\")\n}\n")
        call(read, "path" to "App.kt")

        // The file uses four spaces; the model wrongly uses a tab.
        val output = call(edit, "path" to "App.kt", "old_string" to "\tprintln(\"Hi\")", "new_string" to "\tprintln(\"Hello\")")

        assertTrue(output.isError, output.content)
        assertContains(output.content, "different whitespace or indentation")
        assertContains(output.content, "at lines 2–2")
        assertContains(output.content, "    println(\"Hi\")")
    }
}
package net.khoango.koder.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.assertEquals

class ListFileToolTest {

    @TempDir
    lateinit var tempDir: Path
    lateinit var root: Path
    lateinit var listFilesTool: ListFilesTool

    @BeforeTest
    fun setUp() {
        root = tempDir.resolve("myproject")
        Files.createDirectory(root)
        listFilesTool = ListFilesTool(root)
    }

    @AfterTest
    fun tearDown() {

    }

    @Test
    fun `list files and subdirectories`() = runTest {
        Files.createDirectory(root.resolve("directory1"))
        Files.createDirectory(root.resolve("directory1/subdirectory1"))
        Files.createDirectory(root.resolve("directory2"))
        Files.createFile(root.resolve("file1.txt"))
        Files.createFile(root.resolve("file2.txt"))
        val tool = ListFilesTool(root)
        val input = buildJsonObject { put("path", "./") }
        val output = tool.execute(input)
        val expected = """
            directory1/
            directory2/
            file1.txt
            file2.txt
        """.trimIndent()
        assertEquals(expected, output.content)
    }
}
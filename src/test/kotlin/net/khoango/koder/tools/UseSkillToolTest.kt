package net.khoango.koder.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.khoango.koder.skills.Skill
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UseSkillToolTest {

    @TempDir
    lateinit var root: Path

    private val tool by lazy {
        UseSkillTool(listOf(Skill("code-review", "Review code.", "Files to review: \$ARGUMENTS", root)), root) { _, _ -> "" }
    }

    @Test
    fun `returns the instructions, framed for the model`() = runTest {
        val output = tool.execute(buildJsonObject { put("name", "code-review"); put("input", "Main.kt") })

        assertFalse(output.isError)
        assertContains(output.content, "Here are the instructions of the skill \"code-review\"")
        assertContains(output.content, "Files to review: Main.kt")
    }

    @Test
    fun `lists the available skills when the name is unknown`() = runTest {
        val output = tool.execute(buildJsonObject { put("name", "codereview") })

        assertTrue(output.isError)
        assertContains(output.content, "Available skills: code-review")
    }
}
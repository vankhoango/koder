package net.khoango.koder.prompt

import net.khoango.koder.skills.Skill
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class SystemPromptTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `advertises skills by name and description only`() {
        val skill = Skill(
            name = "code-review",
            description = "Review Kotlin code.",
            instructions = "SECRET STEP-BY-STEP INSTRUCTIONS",
            directory = root,
        )

        val prompt = buildSystemPrompt("Base prompt", root, listOf(skill)).text

        assertContains(prompt, "- code-review: Review Kotlin code.")
        assertFalse("SECRET STEP-BY-STEP INSTRUCTIONS" in prompt)
    }

    @Test
    fun `has no skills section when there are no skills`() {
        val prompt = buildSystemPrompt("Base prompt", root).text

        assertFalse("# Skills" in prompt)
    }
}
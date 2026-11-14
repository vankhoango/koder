package net.khoango.koder.skills

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkillTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the skill message contains the instructions and a relative directory`() {
        val skill = Skill("code-review", "Review code.", "| Step | Action |\n| 1 | Read |", root.resolve(".koder/skills/code-review"))

        val message = skillMessage(skill, root)

        assertContains(message, "directory=\".koder/skills/code-review\"")
        assertContains(message, "| Step | Action |\n| 1 | Read |")   // Markdown tables survive
    }

    @Test
    fun `parses a valid SKILL file`() {
        val text = "---\nname: code-review\ndescription: \"Review code: carefully.\"\n---\n# Steps\nDo it."

        val skill = parseSkill(text, root).getOrThrow()

        assertEquals("code-review", skill.name)
        assertEquals("Review code: carefully.", skill.description)
        assertEquals("# Steps\nDo it.", skill.instructions)
    }

    @Test
    fun `replaces the arguments placeholder`() {
        val skill = Skill("code-review", "Review code.", "Files to review: \$ARGUMENTS", root)

        assertContains(skillMessage(skill, root, "Main.kt Agent.kt"), "Files to review: Main.kt Agent.kt")
        assertContains(skillMessage(skill, root, ""), "Files to review: (none given)")
    }

    @Test
    fun `appends the argument when the skill has no placeholder`() {
        val skill = Skill("commit-message", "Write a commit message.", "Write the message.", root)

        val message = skillMessage(skill, root, "in German")

        assertContains(message, "The user's input for this skill: in German")
    }

    @Test
    fun `reads the argument hint`() {
        val text = "---\nname: code-review\ndescription: Review code.\nargument-hint: [files to review]\n---\nDo it."

        assertEquals("[files to review]", parseSkill(text, root).getOrThrow().argumentHint)
    }

    @Test
    fun `stacked skills keep their order and the conflict rule`() {
        val review = Skill("code-review", "Review code.", "Review: \$ARGUMENTS", root)
        val style = Skill("kotlin-style", "Style rules.", "Never use !!.", root)

        val message = skillMessage(listOf(review, style), root, "Main.kt")

        assertContains(message, "in this order: code-review, kotlin-style")
        assertContains(message, "follow the skill listed later")
        assertTrue(message.indexOf("Review: Main.kt") < message.indexOf("Never use !!."))
    }

    @Test
    fun `rejects stacks that are too large`() {
        val huge = Skill("huge", "Too big.", "x".repeat(30_001), root)

        assertFailsWith<IllegalArgumentException> { skillMessage(listOf(huge), root) }
    }
}
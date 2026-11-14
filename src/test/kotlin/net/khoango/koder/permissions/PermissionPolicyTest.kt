package net.khoango.koder.permissions

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.khoango.koder.domain.ContentBlock
import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionPolicyTest {

    private val policy = PermissionPolicy()

    private fun command(text: String) =
        policy.decide(ContentBlock.ToolUse("id", "run_command", buildJsonObject { put("command", text) }))

    @Test
    fun `allows safe read-only commands`() {
        assertEquals(Decision.ALLOW, command("git status"))
        assertEquals(Decision.ALLOW, command("git diff src/Main.kt"))
        assertEquals(Decision.ALLOW, command(".\\gradlew test"))
    }

    @Test
    fun `asks when a safe command is chained with another one`() {
        assertEquals(Decision.ASK, command("git status; Remove-Item -Recurse src"))
        assertEquals(Decision.ASK, command("git log | Out-File log.txt"))
        assertEquals(Decision.ASK, command("git status && rm -rf src"))
    }

    @Test
    fun `asks for unknown commands`() {
        assertEquals(Decision.ASK, command("Remove-Item -Recurse build"))
        assertEquals(Decision.ASK, command("git statuses"))
    }

    @Test
    fun `denies dangerous commands`() {
        assertEquals(Decision.DENY, command("Format-Volume -DriveLetter C"))
        assertEquals(Decision.DENY, command("git push --force origin master"))
        assertEquals(Decision.DENY, command("Remove-Item -Recurse -Force C:\\"))
        assertEquals(Decision.DENY, command("rm -rf ~"))
    }

    @Test
    fun `allows reading but asks before writing`() {
        val read = ContentBlock.ToolUse("id", "read_file", buildJsonObject { put("path", "a.txt") })
        val write = ContentBlock.ToolUse("id", "write_file", buildJsonObject { put("path", "a.txt") })
        assertEquals(Decision.ALLOW, policy.decide(read))
        assertEquals(Decision.ASK, policy.decide(write))
    }

    @Test
    fun `always allows only the exact command for the rest of the session`() {
        val approved = ContentBlock.ToolUse("id", "run_command", buildJsonObject { put("command", "git commit -m \"Fix\"") })
        policy.allowForSession(approved)

        assertEquals(Decision.ALLOW, command("git commit -m \"Fix\""))
        assertEquals(Decision.ASK, command("git commit -m \"Other\""))
    }

    @Test
    fun `yolo allows everything except dangerous commands`() {
        val yoloPolicy = PermissionPolicy(yolo = true)
        val write = ContentBlock.ToolUse("id", "write_file", buildJsonObject { put("path", "a.txt") })
        val format = ContentBlock.ToolUse("id", "run_command", buildJsonObject { put("command", "Format-Volume -DriveLetter C") })

        assertEquals(Decision.ALLOW, yoloPolicy.decide(write))
        assertEquals(Decision.DENY, yoloPolicy.decide(format))
    }

    private fun runCommand(text: String) =
        ContentBlock.ToolUse("id", "run_command", buildJsonObject { put("command", text) })

    @Test
    fun `always approves a skill script with any arguments`() {
        policy.allowForSession(runCommand("py .koder/skills/test-report/test_report.py"))

        assertEquals(Decision.ALLOW, command("py .koder/skills/test-report/test_report.py build/test-results/test"))
        assertEquals(Decision.ALLOW, command("python .koder\\skills\\test-report\\test_report.py"))
    }

    @Test
    fun `never treats chained or outside scripts as skill scripts`() {
        policy.allowForSession(runCommand("py .koder/skills/test-report/test_report.py"))

        assertEquals(Decision.ASK, command("py .koder/skills/test-report/test_report.py; Remove-Item -Recurse src"))
        assertEquals(Decision.ASK, command("py build/other.py"))
        assertEquals(Decision.ASK, command("py .koder/skills/../../evil.py"))
    }

    @Test
    fun `describes the scope of always`() {
        assertEquals("this skill script, with any arguments", alwaysScope(runCommand("py .koder/skills/a/b.py --x")))
        assertEquals("this exact command", alwaysScope(runCommand("py build/other.py")))
        assertEquals("all write_file calls", alwaysScope(ContentBlock.ToolUse("id", "write_file", buildJsonObject {})))
    }
}
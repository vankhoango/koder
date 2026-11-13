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
}
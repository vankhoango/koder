package net.khoango.koder.permissions

import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.tools.stringParam

enum class Decision {ALLOW, ASK, DENY}
enum class Answer { YES, NO, ALWAYS }

private val CHAINING = Regex("""[;&|<>`\n]|\$\(""")

private val PYTHON_SCRIPT = Regex(
    """(?:py|python|python3)(?:\s+-3)?\s+"?([^\s"]+\.py)"?(?:\s+.*)?""",
    RegexOption.IGNORE_CASE,
)

/**
 * If [command] runs a Python script inside `.koder/skills/` (with any arguments, and nothing chained),
 * returns the script's normalized path; otherwise null.
 */
internal fun skillScriptOf(command: String): String? {
    if (CHAINING.containsMatchIn(command)) return null
    val match = PYTHON_SCRIPT.matchEntire(command.trim()) ?: return null
    val script = match.groupValues[1].replace('\\', '/').removePrefix("./")
    return script.takeIf { it.startsWith(".koder/skills/") && ".." !in it }
}

/** Describes what answering "always" would approve for [call], for the permission question. */
fun alwaysScope(call: ContentBlock.ToolUse): String {
    if (call.name != "run_command") return "all ${call.name} calls"
    val command = call.input.stringParam("command").orEmpty()
    return if (skillScriptOf(command) != null) "this skill script, with any arguments" else "this exact command"
}

class PermissionPolicy(private val yolo: Boolean = false) {

    private val sessionRules = mutableSetOf<String>()

    fun decide(call: ContentBlock.ToolUse): Decision {
        val base = baseDecision(call)
        return when {
            base != Decision.ASK -> base          // ALLOW and DENY are never changed
            yolo -> Decision.ALLOW
            ruleKey(call) in sessionRules -> Decision.ALLOW
            else -> Decision.ASK
        }
    }

    private fun baseDecision(call: ContentBlock.ToolUse): Decision = when (call.name) {
        "read_file", "list_files", "find_files", "search_text" -> Decision.ALLOW
        "run_command" -> decideCommand(call.input.stringParam("command").orEmpty().trim())
        else -> Decision.ASK
    }

    fun allowForSession(call: ContentBlock.ToolUse) {
        sessionRules += ruleKey(call)
    }

    private fun ruleKey(call: ContentBlock.ToolUse): String {
        if (call.name != "run_command") return call.name
        val command = call.input.stringParam("command").orEmpty().trim()
        return skillScriptOf(command)?.let { "skill-script:$it" } ?: "run_command:$command"
    }

    private fun decideCommand(command: String): Decision {
        if (DANGEROUS.any { it.containsMatchIn(command) }) return Decision.DENY
        if (CHAINING.containsMatchIn(command)) return Decision.ASK
        if (SAFE_COMMANDS.any { command.equals(it, ignoreCase = true) || command.startsWith("$it ", ignoreCase = true) }) {
            return Decision.ALLOW
        }
        return Decision.ASK
    }

    companion object {
        private val SAFE_COMMANDS = listOf(
            "git status", "git diff", "git log", "git show", "git branch",
            "./gradlew test", ".\\gradlew test", "./gradlew build", ".\\gradlew build",
        )
        /** Characters that chain commands, redirect output, or substitute values in PowerShell and bash. */
        private val DANGEROUS = listOf(
            Regex("""\b(Format-Volume|Stop-Computer|Restart-Computer|shutdown|format\s+[a-z]:)""", RegexOption.IGNORE_CASE),
            Regex("""\bgit\s+push\b.*(--force|-f\b)""", RegexOption.IGNORE_CASE),
            Regex("""\b(rm|Remove-Item|del|rd|rmdir)\b.*\s(/|~|[a-z]:\\?|\${'$'}HOME|\${'$'}env:USERPROFILE)(\s|$)""", RegexOption.IGNORE_CASE),
        )
    }
}
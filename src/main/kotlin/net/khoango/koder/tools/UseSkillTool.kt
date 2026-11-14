package net.khoango.koder.tools

import kotlinx.serialization.json.JsonObject
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.domain.objectSchema
import net.khoango.koder.skills.Skill
import net.khoango.koder.skills.skillMessage
import java.nio.file.Path

class UseSkillTool(
    private val skills: List<Skill>,
    private val projectRoot: Path,
    private val runSubagent: suspend (skill: Skill, input: String) -> String,
) : Tool {

    override val definition = ToolDefinition(
        name = "use_skill",
        description = """
            Load the instructions of a skill, then follow them. The available skills are listed in the
            system prompt under "Skills". Call this before starting a task that matches a skill's description.
            A skill only needs to be loaded once per conversation; its instructions stay in the conversation.
        """.trimIndent(),
        inputSchema = objectSchema {
            string("name", "The skill's name, e.g. \"code-review\".", required = true)
            string("input", "Optional input for the skill, such as the files to work on.")
        },
    )

    override suspend fun execute(input: JsonObject): ToolOutput {
        val name = input.stringParam("name") ?: return failure("Missing required parameter: name")
        val skill = skills.find { it.name == name }
            ?: return failure("Unknown skill '$name'. Available skills: ${skills.joinToString { it.name }}.")

        val skillInput = input.stringParam("input").orEmpty()

        if (skill.runInSubagent) {
            val report = runSubagent(skill, skillInput)
            return ToolOutput("The skill \"$name\" was carried out by a subagent. Its report:\n\n$report")
        }

        return ToolOutput(skillMessage(skill, projectRoot, skillInput, invokedByModel = true))
    }
}
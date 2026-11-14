package net.khoango.koder.skills

import net.khoango.koder.tools.normalizeLineEndings
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

data class Skill(
    val name: String,
    val description: String,
    val instructions: String,
    val directory: Path,
    val argumentHint: String? = null,
    val files: List<String> = emptyList(),
    val runInSubagent: Boolean = false,
    val allowedTools: Set<String>? = null,
)

/** Skills that loaded successfully, plus a warning for each one that didn't. */
data class SkillLoadResult(val skills: List<Skill>, val warnings: List<String>)

private val VALID_NAME = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
private const val MAX_DESCRIPTION_LENGTH = 1_024

private const val ARGUMENTS_PLACEHOLDER = "\$ARGUMENTS"

private const val MAX_STACKED_CHARS = 30_000


/** Loads personal skills first, then project skills, which replace personal skills with the same name. */
fun loadSkills(projectRoot: Path): SkillLoadResult {
    val userSkills = Path(System.getProperty("user.home"), ".koder", "skills")
    val projectSkills = projectRoot.resolve(".koder").resolve("skills")

    val skills = linkedMapOf<String, Skill>()
    val warnings = mutableListOf<String>()

    for (root in listOf(userSkills, projectSkills)) {
        if (!root.isDirectory()) continue
        for (directory in root.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }) {
            val file = directory.resolve("SKILL.md")
            if (!file.exists()) continue
            parseSkill(file.readText(), directory).fold(
                onSuccess = { skills[it.name] = it },
                onFailure = { warnings += "Skipped skill in $directory: ${it.message}" },
            )
        }
    }
    return SkillLoadResult(skills.values.toList(), warnings)
}

internal fun parseSkill(text: String, directory: Path): Result<Skill> = runCatching {
    val (fields, body) = parseFrontmatter(text)
        ?: error("SKILL.md must start with a frontmatter block between '---' lines.")

    val name = fields["name"] ?: error("The frontmatter has no 'name'.")
    val description = fields["description"] ?: error("The frontmatter has no 'description'.")

    require(VALID_NAME.matches(name)) { "Invalid name '$name': use lowercase letters, digits, and hyphens." }
    require(description.length <= MAX_DESCRIPTION_LENGTH) { "The description is longer than $MAX_DESCRIPTION_LENGTH characters." }
    require(body.isNotBlank()) { "The skill has no instructions after the frontmatter." }

    val files = directory.listDirectoryEntries()
        .filter { it.isRegularFile() && it.name != "SKILL.md" }
        .map { it.name }
        .sorted()

    val allowedTools = fields["allowed-tools"]
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()

    Skill(
        name, description, body, directory,
        argumentHint = fields["argument-hint"],
        files = files,
        runInSubagent = fields["subagent"] == "true",
        allowedTools = allowedTools,
    )
}

/** Splits "---\nkey: value\n---\nbody" into its fields and its body. Supports simple one-line values only. */
internal fun parseFrontmatter(text: String): Pair<Map<String, String>, String>? {
    val lines = text.normalizeLineEndings().lines()
    if (lines.firstOrNull()?.trim() != "---") return null
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) return null

    val fields = lines.subList(1, end + 1)
        .filter { ':' in it }
        .associate { line ->
            val (key, value) = line.split(':', limit = 2)
            key.trim() to value.trim().removeSurrounding("\"")
        }
    val body = lines.drop(end + 2).joinToString("\n").trim()
    return fields to body
}


/** The message sent when the user runs a single skill. */
fun skillMessage(skill: Skill, projectRoot: Path, argument: String = "", invokedByModel: Boolean = false): String =
    skillMessage(listOf(skill), projectRoot, argument, invokedByModel)


/** The message sent when the user runs one or more skills together. Later skills win in a conflict. */
/** The message sent when the user runs one or more skills together. Later skills win in a conflict. */
fun skillMessage(skills: List<Skill>, projectRoot: Path, argument: String = "", invokedByModel: Boolean = false): String {
    require(skills.isNotEmpty()) { "At least one skill is needed." }

    val totalSize = skills.sumOf { it.instructions.length }
    require(totalSize <= MAX_STACKED_CHARS) {
        "These skills' instructions add up to $totalSize characters, more than the limit of $MAX_STACKED_CHARS."
    }

    val anyPlaceholder = skills.any { ARGUMENTS_PLACEHOLDER in it.instructions }

    return buildString {
        if (skills.size == 1) {
            val name = skills.single().name
            if (invokedByModel) {
                appendLine("Here are the instructions of the skill \"$name\". Follow them for the current task.")
            } else {
                appendLine("The user ran the skill \"$name\". Follow its instructions now.")
            }
        } else {
            appendLine("The user ran these skills together, in this order: ${skills.joinToString { it.name }}.")
            appendLine("Follow all of their instructions now. If they conflict, follow the skill listed later.")
        }

        for (skill in skills) {
            val filesAttribute = if (skill.files.isEmpty()) "" else " files=\"${skill.files.joinToString()}\""
            appendLine()
            appendLine("<skill name=\"${skill.name}\" directory=\"${skill.location(projectRoot)}\"$filesAttribute>")
            appendLine(skill.instructions.replace(ARGUMENTS_PLACEHOLDER, argument.ifBlank { "(none given)" }))
            appendLine("</skill>")
        }

        if (!anyPlaceholder && argument.isNotBlank()) {
            val target = if (skills.size == 1) "this skill" else "these skills"
            appendLine()
            appendLine("The user's input for $target: $argument")
        }
    }.trimEnd()
}

/** The skill's directory relative to the project root, or its full path if it lives elsewhere. */
private fun Skill.location(projectRoot: Path): String =
    if (directory.startsWith(projectRoot)) {
        projectRoot.relativize(directory).invariantSeparatorsPathString
    } else {
        directory.toString()
    }


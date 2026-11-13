package net.khoango.koder.skills

import net.khoango.koder.tools.normalizeLineEndings
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

data class Skill(
    val name: String,
    val description: String,
    val instructions: String,
    val directory: Path,
)

/** Skills that loaded successfully, plus a warning for each one that didn't. */
data class SkillLoadResult(val skills: List<Skill>, val warnings: List<String>)

private val VALID_NAME = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
private const val MAX_DESCRIPTION_LENGTH = 1_024

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

    Skill(name, description, body, directory)
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
package net.khoango.koder.cli

import net.khoango.koder.app.KoderApp
import net.khoango.koder.prompt.INIT_PROMPT
import net.khoango.koder.skills.Skill
import net.khoango.koder.skills.skillMessage

class Command(
    val name: String,
    val description: String,
    val argumentHint: String? = null,
    val run: suspend (argument: String) -> Unit,
)

fun builtInCommands(app: KoderApp): List<Command> = listOf(
    Command("reset", "Start a new conversation") {
        app.agent.reset()
        app.fileTracker.clearKnown()
        println("Conversation cleared.")
    },
    Command("compact", "Summarize the conversation to free up context") {
        if (!app.agent.compact()) println("Nothing to compact yet.")
    },
    Command("init", "Explore the project and write KODER.md") {
        app.agent.run(INIT_PROMPT)
        app.reloadSystemPrompt()
    },
    Command("skills", "List the available skills") {
        if (app.skills.isEmpty()) println("No skills found. Add one in .koder/skills/<name>/SKILL.md.")
        app.skills.forEach { println("/${it.name}: ${it.description}") }
    },
    Command("preview", "Show the message a skill would send, without running it", "<skill> [+skill…] [input]") { argument ->
        val name = argument.substringBefore(' ').removePrefix("/")
        val skill = app.skills.find { it.name == name }
        if (skill == null) {
            println("Unknown skill: $name. Type /skills to see the available skills.")
            return@Command
        }
        val (_, message) = prepareSkillMessage(app, skill, argument.substringAfter(' ', "")) ?: return@Command
        println(message)
    },
)

/** One command per skill. Skills whose names clash with existing commands are skipped with a warning. */
fun skillCommands(app: KoderApp, reservedNames: Set<String>): List<Command> =
    app.skills.mapNotNull { skill ->
        if (skill.name in reservedNames) {
            println("[warning] The skill '${skill.name}' has the same name as a built-in command, so it is ignored.")
            return@mapNotNull null
        }

        Command(skill.name, "(skill) ${skill.description}", skill.argumentHint) { argument ->
            val (stack, message) = prepareSkillMessage(app, skill, argument) ?: return@Command

            if (stack.any { it.runInSubagent }) {
                if (stack.size > 1) {
                    println("Skills that run in a subagent can't be stacked yet. Run /${skill.name} on its own.")
                    return@Command
                }
                val report = app.subagents.run(skill, splitStackedSkills(argument).second)
                app.agent.run(
                    "The user ran the skill \"${skill.name}\", which was carried out by a subagent. " +
                            "Its report:\n\n$report\n\nPresent this report to the user."
                )
                return@Command
            }

            println("[skills: ${stack.joinToString(" + ") { it.name }}]")
            app.agent.run(message)
        }
    }

/** Resolves "+added" skills and builds the message, or prints a problem and returns null. */
private fun prepareSkillMessage(app: KoderApp, skill: Skill, argument: String): Pair<List<Skill>, String>? {
    val (addedNames, rest) = splitStackedSkills(argument)

    val unknown = addedNames.filter { name -> app.skills.none { it.name == name } }
    if (unknown.isNotEmpty()) {
        println("Unknown skill: ${unknown.joinToString { "+$it" }}. Type /skills to see the available skills.")
        return null
    }

    val stack = (listOf(skill) + addedNames.map { name -> app.skills.first { it.name == name } })
        .distinctBy { it.name }

    return try {
        stack to skillMessage(stack, app.projectRoot, rest)
    } catch (e: IllegalArgumentException) {
        println(e.message)
        null
    }
}

/** Splits "+a +b rest of the text" into (["a", "b"], "rest of the text"). */
internal fun splitStackedSkills(argument: String): Pair<List<String>, String> {
    val words = argument.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val added = words.takeWhile { it.startsWith("+") && it.length > 1 }
    val rest = words.drop(added.size).joinToString(" ")
    return added.map { it.removePrefix("+") } to rest
}
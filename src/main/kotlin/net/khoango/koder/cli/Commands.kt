package net.khoango.koder.cli

import net.khoango.koder.app.KoderApp
import net.khoango.koder.prompt.INIT_PROMPT

class Command(
    val name: String,
    val description: String,
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
)
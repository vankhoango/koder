package net.khoango.koder.cli

import net.khoango.koder.agent.Agent
import net.khoango.koder.llm.LlmException

class ChatLoop(private val agent: Agent, builtIn: List<Command>) {

    private val commands: List<Command> =
        builtIn + Command("help", "List the available commands") { printHelp() }

    suspend fun run(firstPrompt: String?) {
        var pending = firstPrompt
        while (true) {
            val line = pending ?: readInput() ?: break
            pending = null

            if (line == "/quit" || line == "/exit") break
            if (line.isBlank()) continue

            val usageBefore = agent.usage
            try {
                if (line.startsWith("/")) runCommand(line) else agent.run(line)
            } catch (e: LlmException) {
                println("\nError: ${e.message}")
            }
            if (agent.usage != usageBefore) println("[session: ${agent.usage}]")
        }
    }

    private fun readInput(): String? {
        print("\n> ")
        System.out.flush()
        return readlnOrNull()?.trim()
    }

    private suspend fun runCommand(line: String) {
        val name = line.removePrefix("/").substringBefore(' ')
        val argument = line.substringAfter(' ', missingDelimiterValue = "").trim()

        val command = commands.find { it.name == name }
        if (command == null) {
            println("Unknown command: /$name. Type /help to see the available commands.")
        } else {
            command.run(argument)
        }
    }

    private fun printHelp() {
        println("/quit: Exit Koder")
        commands.forEach { println("/${it.name}: ${it.description}") }
    }
}
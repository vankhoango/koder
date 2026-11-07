package net.khoango.koder

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import kotlinx.coroutines.runBlocking
import net.khoango.koder.llm.callLlm

class Koder: CliktCommand(name="koder") {
    private val prompt by argument().optional()
    override fun run(): Unit = runBlocking {
        val apiKey = System.getenv("OPENROUTER_API_KEY") ?:
            throw PrintMessage("OPENROUTER_API_KEY is not set", statusCode = 1, printError = true)
        val modelName = System.getenv("OPENROUTER_MODEL_NAME") ?:
        throw PrintMessage("OPENROUTER_MODEL_NAME is not set", statusCode = 1, printError = true)
        echo("koder is ready (API key ${apiKey.length} chars)")
        val response = callLlm(apiKey, modelName, prompt ?: "Hello!")
        println(response)
    }
}

fun main(args: Array<String>) = Koder().main(args)
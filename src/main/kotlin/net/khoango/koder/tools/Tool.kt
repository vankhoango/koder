package net.khoango.koder.tools

import kotlinx.serialization.json.JsonObject
import net.khoango.koder.domain.ToolDefinition

data class ToolOutput(val content: String, val isError: Boolean = false)

fun failure(message: String) = ToolOutput(message, isError = true)

interface Tool {
    val definition: ToolDefinition
    suspend fun execute(input: JsonObject): ToolOutput
}
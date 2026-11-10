package net.khoango.koder.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.khoango.koder.domain.ToolDefinition

data class ToolOutput(val content: String, val isError: Boolean = false)

fun failure(message: String) = ToolOutput(message, isError = true)

internal fun JsonObject.stringParam(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

interface Tool {
    val definition: ToolDefinition
    suspend fun execute(input: JsonObject): ToolOutput
}



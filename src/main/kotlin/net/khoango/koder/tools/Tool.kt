package net.khoango.koder.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import net.khoango.koder.domain.ToolDefinition

data class ToolOutput(val content: String, val isError: Boolean = false)

fun failure(message: String) = ToolOutput(message, isError = true)

internal fun JsonObject.stringParam(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.booleanParam(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

interface Tool {
    val definition: ToolDefinition
    suspend fun execute(input: JsonObject): ToolOutput
}

/** Formats a count with a word in singular or plural, e.g. "1 line", "3 lines". */
internal fun Int.pluralize(word: String): String =
    if (this == 1) "$this $word" else "$this ${word}s"



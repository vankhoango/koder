package net.khoango.koder.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

val readToolDefinition = ToolDefinition (
    name = "read_file",
    description = """
        Read the contents of a text file in the current project.
        Use this whenever you need to see a file before answering questions about it or changing it.
        Never guess a file's contents; read it first.
    """.trimIndent(),
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Path to the file, relative to the project root, e.g. \"src/main/kotlin/Main.kt\".")
            }
        }
        putJsonArray("required") {
            add("path")
        }
    }
)
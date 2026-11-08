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

val listToolDefinition = ToolDefinition (
    name = "list_files",
    description = """
        List the files and subdirectories in a directory of the current project.
        Directory names end with "/". The listing is not recursive: call this tool again
        on a subdirectory to see what it contains.
        Use this to explore the project's structure, and to find the exact path of a file
        before calling read_file. Never guess a path; list the directory first if you're unsure.
    """.trimIndent(),
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                "Directory to list, relative to the project root, e.g. \"src/main\". " +
                        "Omit it to list the project root."
            }
        }
    }
)
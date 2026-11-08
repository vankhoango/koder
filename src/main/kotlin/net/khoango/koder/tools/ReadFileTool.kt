package net.khoango.koder.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.domain.readToolDefinition
import kotlin.io.path.readText

class ReadFileTool(private val projectRoot: Path): Tool {

    override val definition: ToolDefinition = readToolDefinition

    override suspend fun execute(input: JsonObject): ToolOutput {
        val path = (input["path"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolOutput("Missing required parameter: path", isError = true)
        val file = projectRoot.resolve(path)
        return try {
            withContext(Dispatchers.IO) {
                val content = file.readText()
                ToolOutput(content)
            }
        } catch (e: Exception) {
            ToolOutput("Could not read '$path': ${e::class.simpleName} ${e.message}", isError = true)
        }
    }

}
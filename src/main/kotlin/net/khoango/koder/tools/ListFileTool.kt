package net.khoango.koder.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.khoango.koder.domain.ToolDefinition
import net.khoango.koder.domain.listToolDefinition
import net.khoango.koder.domain.readToolDefinition
import java.io.IOException
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class ListFileTool(private val projectRoot: Path): Tool {

    override val definition: ToolDefinition = listToolDefinition

    override suspend fun execute(input: JsonObject): ToolOutput {
        val path = (input["path"] as? JsonPrimitive)?.contentOrNull ?: "./"

        val absolutePath = projectRoot.resolveInside(path)
            ?: return failure("'$path' is outside the project. Use a path relative to the project root.")

        return withContext(Dispatchers.IO) { listFile(absolutePath) }
    }

    private fun listFile(path: Path): ToolOutput {
        if (!path.exists()) return failure("Directory not found: '$path'.")
        if (!path.isDirectory()) return failure("'$path' is not a directory.")

        val fileList = path.listDirectoryEntries().map {
            val relativePath = projectRoot.relativize(it).invariantSeparatorsPathString
            if (it.isDirectory()) "$relativePath/" else relativePath
        }.joinToString (separator = "\n")
        return ToolOutput(fileList)
    }

    companion object {
        private const val MAX_LINES = 2_000
    }
}
package net.khoango.koder.mcp

import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** An MCP server running as a child process, connected over its standard input and output. */
class McpServerProcess private constructor(
    val process: Process,
    val connection: JsonRpcConnection,
    private val shutdownTimeout: Duration,
) : AutoCloseable {

    override fun close() {
        connection.close()                                                       // 1. ask politely
        if (!process.waitFor(shutdownTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {  // 2. wait
            process.descendants().toList().forEach { it.destroyForcibly() }       // 3. force it
            process.destroyForcibly()
            process.waitFor(shutdownTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        fun start(
            command: List<String>,
            scope: CoroutineScope,
            workingDirectory: Path? = null,
            shutdownTimeout: Duration = 5.seconds,
            extraEnvironment: Map<String, String> = emptyMap(),
        ): McpServerProcess {
            val process = ProcessBuilder(command)
                .apply {
                    workingDirectory?.let { directory(it.toFile()) }
                    environment().putAll(extraEnvironment)
                }
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            return McpServerProcess(
                process,
                JsonRpcConnection(process.inputStream, process.outputStream, scope),
                shutdownTimeout,
            )
        }
    }
}

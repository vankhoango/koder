package net.khoango.koder.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.ToolDefinition
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Wraps another client and retries requests that fail with a temporary error before anything was streamed. */
class RetryingLlmClient(
    private val delegate: LlmClient,
    private val maxAttempts: Int = 4,
    private val baseDelay: Duration = 1.seconds,
    private val maxDelay: Duration = 30.seconds,
    private val onRetry: (attempt: Int, wait: Duration, error: LlmException) -> Unit = { _, _, _ -> },
) : LlmClient {

    override fun stream(
        system: String,
        messages: List<Message>,
        tools: List<ToolDefinition>,
    ): Flow<LlmStreamEvent> = flow {
        var attempt = 1
        while (true) {
            var emitted = false
            try {
                delegate.stream(system, messages, tools).collect { event ->
                    emitted = true
                    emit(event)
                }
                return@flow
            } catch (e: LlmException) {
                if (!e.retryable || emitted || attempt >= maxAttempts) throw e
                val wait = backoff(attempt)
                onRetry(attempt, wait, e)
                delay(wait)
                attempt++
            }
        }
    }

    override fun close() = delegate.close()

    private fun backoff(attempt: Int): Duration {
        val exponential = baseDelay * 2.0.pow(attempt - 1)
        val jitter = 1 + Random.nextDouble(0.0, 0.25)
        return minOf(exponential * jitter, maxDelay)
    }
}
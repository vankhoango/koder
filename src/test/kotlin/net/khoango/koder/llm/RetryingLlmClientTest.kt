package net.khoango.koder.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.ToolDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryingLlmClientTest {

    /** Fails with the given errors in order, then succeeds. Optionally emits text before failing. */
    private class FlakyClient(
        private val errors: List<LlmException>,
        private val textBeforeFailure: String? = null,
    ) : LlmClient {
        var calls = 0

        override fun stream(system: String, messages: List<Message>, tools: List<ToolDefinition>): Flow<LlmStreamEvent> = flow {
            val error = errors.getOrNull(calls++)
            if (error != null) {
                textBeforeFailure?.let { emit(LlmStreamEvent.TextDelta(it)) }
                throw error
            }
            emit(LlmStreamEvent.Completed(LlmResponse(Message.assistant("OK"), Usage.ZERO)))
        }
    }

    private val busy = LlmException("HTTP 503", retryable = true)

    @Test
    fun `retries temporary errors and then succeeds`() = runTest {
        val flaky = FlakyClient(listOf(busy, busy))

        val events = RetryingLlmClient(flaky).stream("", emptyList(), emptyList()).toList()

        assertEquals(3, flaky.calls)
        assertEquals(Message.assistant("OK"), (events.single() as LlmStreamEvent.Completed).response.message)
    }

    @Test
    fun `does not retry errors that are not temporary`() = runTest {
        val flaky = FlakyClient(listOf(LlmException("HTTP 400", retryable = false)))

        assertFailsWith<LlmException> { RetryingLlmClient(flaky).stream("", emptyList(), emptyList()).toList() }
        assertEquals(1, flaky.calls)
    }

    @Test
    fun `gives up after the maximum number of attempts`() = runTest {
        val flaky = FlakyClient(List(10) { busy })

        assertFailsWith<LlmException> { RetryingLlmClient(flaky, maxAttempts = 4).stream("", emptyList(), emptyList()).toList() }
        assertEquals(4, flaky.calls)
    }

    @Test
    fun `does not retry once text has been streamed`() = runTest {
        val flaky = FlakyClient(listOf(busy), textBeforeFailure = "Half an answ")

        assertFailsWith<LlmException> { RetryingLlmClient(flaky).stream("", emptyList(), emptyList()).toList() }
        assertEquals(1, flaky.calls)
    }
}
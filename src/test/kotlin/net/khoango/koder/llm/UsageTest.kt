package net.khoango.koder.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class UsageTest {
    @Test
    fun `adding two usages sums both counts`() {
        val total = Usage(10, 5) + Usage(3, 2)
        assertEquals(Usage(13, 7), total)
    }
}
package net.khoango.koder.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandsTest {

    @Test
    fun `splits added skills from the rest of the argument`() {
        assertEquals(listOf("kotlin-style", "security") to "src/Main.kt", splitStackedSkills("+kotlin-style +security src/Main.kt"))
        assertEquals(emptyList<String>() to "add 1+1", splitStackedSkills("add 1+1"))
        assertEquals(emptyList<String>() to "", splitStackedSkills(""))
    }
}
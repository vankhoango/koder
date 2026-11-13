package net.khoango.koder.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.khoango.koder.domain.ContentBlock
import net.khoango.koder.domain.Message
import net.khoango.koder.domain.Role
import net.khoango.koder.llm.Usage
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentStateTest {

    @Test
    fun `survives a round trip through JSON`() {
        val state = AgentState(
            history = listOf(
                Message.user("Read the build file"),
                Message(
                    Role.ASSISTANT,
                    listOf(
                        ContentBlock.Text("Let me look."),
                        ContentBlock.ToolUse(
                            "call_1", "read_file",
                            buildJsonObject { put("path", "build.gradle.kts") },
                            providerMetadata = buildJsonObject { put("signature", "abc") },
                        ),
                    ),
                ),
                Message(Role.USER, listOf(ContentBlock.ToolResult("call_1", "read_file", "plugins { }", isError = false))),
            ),
            summary = "An earlier summary.",
            usage = Usage(1200, 340),
        )

        val restored = Json.decodeFromString<AgentState>(Json.encodeToString(state))

        assertEquals(state, restored)
    }
}
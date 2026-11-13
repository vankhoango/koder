package net.khoango.koder.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class Role {USER, ASSISTANT }

@Serializable
sealed interface ContentBlock {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
        val providerMetadata: JsonObject? = null,
    ): ContentBlock

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolUseId: String,
        val toolName: String,
        val content: String,
        val isError: Boolean = false,
    ) : ContentBlock
}

@Serializable
data class Message(val role: Role, val content: List<ContentBlock>) {
    val text: String
        get() = content.filterIsInstance<ContentBlock.Text>().joinToString("") {
            it.text
        }

    val toolUses: List<ContentBlock.ToolUse>
        get() = content.filterIsInstance<ContentBlock.ToolUse>()

    companion object {
        fun user(text: String) = Message(Role.USER, listOf(ContentBlock.Text(text)))
        fun assistant(text: String) = Message(Role.ASSISTANT, listOf(ContentBlock.Text(text)))
    }
}
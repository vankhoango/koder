package net.khoango.koder.domain

import kotlinx.serialization.json.JsonObject

enum class Role {USER, ASSISTANT }

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
        val providerMetadata: JsonObject? = null,
    ): ContentBlock
}

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
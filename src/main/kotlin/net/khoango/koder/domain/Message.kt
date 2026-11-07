package net.khoango.koder.domain

import io.ktor.http.contentRangeHeaderValue

enum class Role {USER, ASSISTANT }

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
}

data class Message(val role: Role, val content: List<ContentBlock>) {
    val text: String
        get() = content.filterIsInstance<ContentBlock.Text>().joinToString("") {
            it.text
        }

    companion object {
        fun user(text: String) = Message(Role.USER, listOf(ContentBlock.Text(text)))
        fun assistant(text: String) = Message(Role.ASSISTANT, listOf(ContentBlock.Text(text)))
    }
}
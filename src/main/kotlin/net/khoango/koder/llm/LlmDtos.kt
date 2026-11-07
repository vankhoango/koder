package net.khoango.koder.llm

import kotlinx.serialization.Serializable

@Serializable
internal data class GenerateContentRequest(
    val contents: List<LlmContent>,
)

@Serializable
internal data class LlmContent(
    val role: String? = null,
    val parts: List<LlmPart> = emptyList(),
)

@Serializable
internal data class LlmPart(
    val text: String? = null,
)

@Serializable
internal data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList(),
)

@Serializable
internal data class Candidate(
    val content: LlmContent? = null,
    val finishReason: String? = null,
)
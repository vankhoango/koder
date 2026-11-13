package net.khoango.koder.agent

import net.khoango.koder.domain.ContentBlock

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data object TextEnd : AgentEvent
    data class ToolCalled(val call: ContentBlock.ToolUse) : AgentEvent
    data class ToolFinished(val result: ContentBlock.ToolResult) : AgentEvent
    data class StepLimitReached(val maxSteps: Int) : AgentEvent
    data object Abandoned : AgentEvent
}
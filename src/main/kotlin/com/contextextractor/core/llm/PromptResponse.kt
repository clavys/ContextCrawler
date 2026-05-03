package com.contextextractor.core.llm

// Réponse retournée par un LlmClient.
data class PromptResponse(
    val text: String,
    val model: String,
    val finishReason: String? = null,
    val usage: Usage? = null
) {
    data class Usage(val inputTokens: Int, val outputTokens: Int)
}

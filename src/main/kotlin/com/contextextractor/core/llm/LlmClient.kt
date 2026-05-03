package com.contextextractor.core.llm

// PORT vers les LLM. Implémentations à l'étape 8 :
// - AnthropicApiClient (api.anthropic.com)
// - ClaudeCodeClient (CLI locale via ProcessBuilder)
// Voir CLAUDE.md "Étape 8".
interface LlmClient {
    val id: String

    suspend fun complete(req: PromptRequest): PromptResponse
}

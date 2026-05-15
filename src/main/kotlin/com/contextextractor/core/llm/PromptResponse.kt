package com.contextextractor.core.llm

// Réponse retournée par un LlmClient. `error` est non-null UNIQUEMENT en cas
// d'échec du backend (process non trouvé, exit code ≠ 0, timeout, IO error).
// L'appelant DOIT vérifier `error` AVANT de consommer `text` — ils sont
// mutuellement exclusifs sémantiquement (text vide quand error ≠ null).
//
// Pourquoi un champ `error` plutôt qu'une exception : la frontière entre les
// adapters LLM (ProcessBuilder, HTTP client) et l'IDE traverse plusieurs
// threads (Task.Backgroundable + EDT). Retourner un résultat structuré est
// plus simple à propager que de catch/rethrow à chaque hop.
data class PromptResponse(
    val text: String,
    val model: String,
    val finishReason: String? = null,
    val usage: Usage? = null,
    val error: String? = null
) {
    data class Usage(val inputTokens: Int, val outputTokens: Int)
}

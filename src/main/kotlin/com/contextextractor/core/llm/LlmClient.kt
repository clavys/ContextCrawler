package com.contextextractor.core.llm

// PORT vers les LLM — voir ARCHITECTURE.md §5 et CLAUDE.md "Étape 8".
//
// **Pas de `suspend`** : V1 cible un appel blocking-synchrone via
// `Task.Backgroundable` côté IDE. Le ProcessBuilder de ClaudeCodeClient et un
// HTTP client classique sont nativement bloquants ; ajouter des coroutines ici
// n'apporterait que du bruit. Si V2 ajoute du streaming, on introduira un
// second port `LlmStreamingClient` plutôt que de muter celui-ci.
//
// **Gestion d'erreur** : retourner `PromptResponse(error = ...)` plutôt que
// throw — voir doc PromptResponse pour la raison (frontière multi-thread).
//
// V1 livre uniquement `ClaudeCodeClient` (cf décision utilisateur étape 8 :
// pas d'AnthropicApiClient dans V1, abonnement Claude existant via CLI).
interface LlmClient {
    val id: String

    fun complete(req: PromptRequest): PromptResponse
}

package com.contextextractor.core.llm

// Requête envoyée à un LlmClient. Modèle minimal V1 — extension à l'étape 8.
data class PromptRequest(
    val prompt: String,
    val model: String,
    val temperature: Double = 0.2,
    val maxTokens: Int = 4096,
    val systemPrompt: String? = null
)

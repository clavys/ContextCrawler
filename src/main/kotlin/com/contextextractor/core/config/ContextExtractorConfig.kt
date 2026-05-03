package com.contextextractor.core.config

import com.contextextractor.core.strategy.Budget

// Configuration typée du plugin — clé d'entrée des composants core.
// Construite à partir d'un LayeredConfig à l'étape 6.
data class ContextExtractorConfig(
    val strategyId: String = "recursive-deep",
    val outputMode: OutputMode = OutputMode.COPY,
    val templates: TemplateConfig = TemplateConfig(),
    val classification: ClassificationConfig = ClassificationConfig(),
    val llm: LlmConfig = LlmConfig(),
    val prompt: PromptConfig = PromptConfig(),
    val budget: Budget = Budget()
) {
    enum class OutputMode { COPY, LLM_CALL, ASK }

    data class TemplateConfig(
        val dir: String? = null,
        val default: String = "deep-unit-test"
    )

    data class ClassificationConfig(
        val mockSuffixes: List<String> = listOf("Service", "Repository", "Gateway"),
        val dataSuffixes: List<String> = listOf("DTO", "Entity", "Request", "Response", "Command")
    )

    data class LlmConfig(
        val provider: String = "claude",
        val model: String = "claude-sonnet-4-6",
        val temperature: Double = 0.2
    )

    data class PromptConfig(
        val templates: Map<String, String> = emptyMap(),
        val layers: Map<String, String> = emptyMap()
    )
}

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
    val budget: Budget = Budget(),
    // V1.4 — convention équipe sur le code de test généré. Orthogonal au
    // profil LLM (`llm.tuningProfile`) : LLM-profile = patches modèle-specific,
    // testPolicy = conventions org/projet. Cf STRATEGIE.md §0.2 + §6bis.
    val testPolicy: TestPolicyConfig = TestPolicyConfig()
) {
    enum class OutputMode { COPY, LLM_CALL, ASK }

    // V1.4 — strictness Mockito injectée dans le prompt CONSTRAINTS.
    //   STRICT_STUBS : défaut Mockito 4.x + JUnit5 — toute stubbing inutile
    //                  fait planter le test. Pas d'annotation à ajouter.
    //   WARN         : log seulement, le test passe. Convention typique
    //                  legacy Spring (cas Astrea) où les méthodes ont
    //                  plusieurs branches et tous les stubs ne sont pas
    //                  exercés à chaque test.
    //   LENIENT      : silencieux — plus permissif. Utile pour migration.
    enum class MockitoStrictness { STRICT_STUBS, WARN, LENIENT }

    data class TestPolicyConfig(
        val mockitoStrictness: MockitoStrictness = MockitoStrictness.STRICT_STUBS
    )

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
        val temperature: Double = 0.2,
        // Phase 5 — profil de tuning des CONSTRAINTS. Cf
        // `core/prompt/constraints/ConstraintsProfile.kt`. Défaut « qwen »
        // pour rétro-compat avec V1.1.
        val tuningProfile: String = "qwen"
    )

    data class PromptConfig(
        val templates: Map<String, String> = emptyMap(),
        val layers: Map<String, String> = emptyMap()
    )
}

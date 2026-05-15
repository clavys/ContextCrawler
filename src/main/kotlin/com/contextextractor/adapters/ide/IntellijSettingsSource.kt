package com.contextextractor.adapters.ide

import com.contextextractor.core.config.ConfigSource

// Source de config priorité 10 — ContextCrawlerSettings (PersistentStateComponent).
// Voir ARCHITECTURE.md §8.
//
// **Pourquoi un adapter séparé du PersistentStateComponent** :
//   • la sérialisation XML appartient à la plateforme IntelliJ (`@State`
//     annotation, `XmlSerializerUtil`) → coupling IDE inévitable.
//   • la projection State → Map<String, Any?> est de la logique pure → testable
//     en JUnit 5 sans démarrer l'IDE.
//   • cet adapter prend la State en paramètre (et non le service) — le test
//     fournit une `State()` synthétique, le runtime IDE fournit la vraie.
//
// **Mapping des champs** (aligné sur ARCHITECTURE.md §8 et DefaultsConfigSource) :
//   strategy      → top-level "strategy"
//   outputMode    → top-level "outputMode"
//   llmProvider   → "llm.provider"
//   llmModel      → "llm.model"
//
// **Verrou priorité 10** : strictement entre Defaults (0) et YAML (20) — un
// utilisateur peut surcharger via UI, mais un YAML projet versionné l'emporte
// (les conventions d'équipe priment sur les préférences locales).
class IntellijSettingsSource(
    private val state: ContextCrawlerSettings.State
) : ConfigSource {

    override val priority: Int = 10

    override fun load(): Map<String, Any?> = mapOf(
        "strategy" to state.strategy,
        "outputMode" to state.outputMode,
        "llm" to mapOf(
            "provider" to state.llmProvider,
            "model" to state.llmModel
        )
    )
}

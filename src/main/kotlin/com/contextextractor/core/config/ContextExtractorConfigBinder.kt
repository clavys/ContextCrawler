package com.contextextractor.core.config

import com.contextextractor.core.strategy.Budget

// Binder typesafe : LayeredConfig (Map<String, Any?>) → ContextExtractorConfig.
// Voir ARCHITECTURE.md §8 et CLAUDE.md étape 6.
//
// **Périmètre** : assemblage et coercion. Aucune logique métier ; aucune
// dépendance IDE. La construction des sources (`Project` → YAML path,
// `Application` → ContextCrawlerSettings.State) vit dans
// ContextExtractorService (étape 7) — voir doc utilisateur 6-δ : si le binder
// construisait ses sources, il deviendrait IDE-couplé et non testable.
//
// **Validation** : `Budget.validated()` est invoqué ici, à la frontière config →
// composants core. Si le YAML ou les settings produisent un Budget aberrant,
// l'erreur est levée AU MOMENT du bind (= au démarrage du service), avec la
// clé YAML offensante dans le message — pas plus tard pendant la récursion.
//
// **Fallback aux défauts** : `LayeredConfig` retourne `null` quand une clé est
// absente ou de type incompatible. Le binder prend alors le défaut Kotlin de
// la data class — cohérent avec « DefaultsConfigSource est juste un miroir
// de ContextExtractorConfig() » testé en 6-α.
class ContextExtractorConfigBinder(private val layered: LayeredConfig) {

    fun bind(): ContextExtractorConfig {
        val defaults = ContextExtractorConfig()
        return ContextExtractorConfig(
            strategyId = layered.get<String>("strategy") ?: defaults.strategyId,
            outputMode = bindOutputMode(layered.get<String>("outputMode"), defaults.outputMode),
            templates = bindTemplates(defaults.templates),
            classification = bindClassification(defaults.classification),
            llm = bindLlm(defaults.llm),
            prompt = bindPrompt(defaults.prompt),
            // Validation à la frontière — toute valeur dégénérée est attrapée ICI.
            budget = bindBudget(defaults.budget).validated(),
            testPolicy = bindTestPolicy(defaults.testPolicy)
        )
    }

    private fun bindTestPolicy(
        defaults: ContextExtractorConfig.TestPolicyConfig
    ): ContextExtractorConfig.TestPolicyConfig {
        val raw = layered.get<String>("testPolicy.mockitoStrictness")
        val strictness = if (raw == null) defaults.mockitoStrictness else try {
            ContextExtractorConfig.MockitoStrictness.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            // Verrou : valeur enum invalide ne tombe PAS en silence sur un défaut.
            // Cohérent avec bindOutputMode — le diagnostic au démarrage doit
            // pointer la clé fautive et les valeurs valides.
            throw IllegalArgumentException(
                "testPolicy.mockitoStrictness: valeur '$raw' invalide. " +
                    "Valeurs acceptées : ${ContextExtractorConfig.MockitoStrictness.entries.joinToString()}"
            )
        }
        return ContextExtractorConfig.TestPolicyConfig(mockitoStrictness = strictness)
    }

    // ── outputMode : enum strict ─────────────────────────────────────────────

    private fun bindOutputMode(
        raw: String?,
        fallback: ContextExtractorConfig.OutputMode
    ): ContextExtractorConfig.OutputMode {
        if (raw == null) return fallback
        return try {
            ContextExtractorConfig.OutputMode.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            // Verrou : valeur enum invalide ne tombe PAS en silence sur un défaut.
            // Le diagnostic au démarrage doit pointer la clé fautive et les
            // valeurs valides — sinon l'utilisateur cherche pourquoi son
            // outputMode n'est pas pris en compte.
            throw IllegalArgumentException(
                "outputMode: valeur '$raw' invalide. " +
                    "Valeurs acceptées : ${ContextExtractorConfig.OutputMode.entries.joinToString()}"
            )
        }
    }

    // ── templates / classification / llm / prompt — sous-maps ───────────────

    private fun bindTemplates(
        defaults: ContextExtractorConfig.TemplateConfig
    ): ContextExtractorConfig.TemplateConfig {
        return ContextExtractorConfig.TemplateConfig(
            // String? : `null` ou clé absente → conservation du défaut.
            // Une chaîne vide explicite ("") signifie « pas de répertoire custom » :
            // on la NORMALISE en null pour que les composants en aval ne
            // tombent pas dans un Path("") indéfini.
            dir = layered.get<String>("templates.dir")?.takeIf { it.isNotBlank() } ?: defaults.dir,
            default = layered.get<String>("templates.default") ?: defaults.default
        )
    }

    private fun bindClassification(
        defaults: ContextExtractorConfig.ClassificationConfig
    ): ContextExtractorConfig.ClassificationConfig {
        return ContextExtractorConfig.ClassificationConfig(
            mockSuffixes = readStringList("classification.mockSuffixes") ?: defaults.mockSuffixes,
            dataSuffixes = readStringList("classification.dataSuffixes") ?: defaults.dataSuffixes
        )
    }

    private fun bindLlm(
        defaults: ContextExtractorConfig.LlmConfig
    ): ContextExtractorConfig.LlmConfig {
        return ContextExtractorConfig.LlmConfig(
            provider = layered.get<String>("llm.provider") ?: defaults.provider,
            model = layered.get<String>("llm.model") ?: defaults.model,
            // Number couvre Int/Long/Double — un YAML `temperature: 0` (Int)
            // doit produire 0.0 (Double) sans crash.
            temperature = layered.get<Number>("llm.temperature")?.toDouble() ?: defaults.temperature,
            tuningProfile = layered.get<String>("llm.tuningProfile") ?: defaults.tuningProfile
        )
    }

    private fun bindPrompt(
        defaults: ContextExtractorConfig.PromptConfig
    ): ContextExtractorConfig.PromptConfig {
        return ContextExtractorConfig.PromptConfig(
            templates = readStringMap("prompt.templates") ?: defaults.templates,
            layers = readStringMap("prompt.layers") ?: defaults.layers
        )
    }

    // ── Budget : binding + validation atomique ──────────────────────────────

    private fun bindBudget(defaults: Budget): Budget = Budget(
        maxDepth = layered.get<Int>("budget.maxDepth") ?: defaults.maxDepth,
        maxInitDepth = layered.get<Int>("budget.maxInitDepth") ?: defaults.maxInitDepth,
        maxGraphDepth = layered.get<Int>("budget.maxGraphDepth") ?: defaults.maxGraphDepth,
        maxEstimatedTokens = layered.get<Int>("budget.maxEstimatedTokens")
            ?: defaults.maxEstimatedTokens
    )

    // ── Helpers : listes et maps homogènes ──────────────────────────────────

    // Lit une List<*> et garde uniquement les String. Si la liste contient des
    // non-String, on retourne null pour fallback au défaut — mieux qu'un
    // ClassCastException à l'usage. Le YAML qui mixe types pourrait crasher
    // ici si on était stricts, mais c'est un cas limite peu utile à V1.
    private fun readStringList(key: String): List<String>? {
        val raw = layered.get<List<*>>(key) ?: return null
        return raw.filterIsInstance<String>().takeIf { it.size == raw.size }
    }

    // Lit une Map<*, *> et la projette en Map<String, String>. Tolère
    // values null en les ignorant (cohérent avec la sémantique « null = absent »
    // côté LayeredConfig). Si une clé ou valeur n'est pas String, retourne
    // null pour fallback au défaut.
    private fun readStringMap(key: String): Map<String, String>? {
        val raw = layered.get<Map<*, *>>(key) ?: return null
        val out = LinkedHashMap<String, String>(raw.size)
        for ((k, v) in raw) {
            if (k !is String || v !is String) return null
            out[k] = v
        }
        return out
    }
}

package com.contextextractor.config

import com.contextextractor.adapters.config.YamlProjectConfigSource
import com.contextextractor.adapters.ide.ContextCrawlerSettings
import com.contextextractor.adapters.ide.IntellijSettingsSource
import com.contextextractor.core.config.DefaultsConfigSource
import com.contextextractor.core.config.LayeredConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Sous-étape 6-γ — vérifie la logique PURE d'IntellijSettingsSource :
//   • priorité = 10 (entre Defaults=0 et YAML=20) ;
//   • mapping State → Map<String, Any?> aligné §8 (provider/model dans `llm.{}`) ;
//   • défaut outputMode = "COPY" — verrou pivot 6-γ : un premier `runIde`
//     SANS configuration manuelle doit produire un comportement fonctionnel
//     (ExtractContextAction étape 7 route vers PromptCopyDialog par défaut) ;
//   • intégration LayeredConfig : settings écrase defaults, YAML écrase settings.
//
// **Cycle de vie PersistentStateComponent** (annotations @State, sérialisation
// XML, instanciation par le service) → déféré au gate `runIde` manuel,
// cohérent avec la limite 4-α du wiring testFramework Platform pour
// JavaPsiIntrospector. Cette suite teste UNIQUEMENT la projection State → Map,
// qui ne dépend pas de la plateforme.
class IntellijSettingsSourceTest {

    // ── Verrou priorité ──────────────────────────────────────────────────────

    @Test
    fun `priority is 10 — strictly between Defaults (0) and YAML (20)`() {
        val source = IntellijSettingsSource(ContextCrawlerSettings.State())
        assertEquals(10, source.priority)
    }

    // ── Verrou pivot : outputMode default = COPY ─────────────────────────────

    @Test
    fun `default outputMode is COPY — first runIde must work without config`() {
        // VERROU PIVOT 6-γ — sans ce défaut, ExtractContextAction (étape 7)
        // n'a pas de routage par défaut vers PromptCopyDialog et le premier
        // runIde produit un comportement non fonctionnel.
        val state = ContextCrawlerSettings.State()
        assertEquals("COPY", state.outputMode,
            "Le défaut DOIT être COPY (pas ASK ni LLM_CALL) pour que le premier " +
                "runIde soit fonctionnel sans configuration manuelle.")

        // Et le mapping doit l'exposer correctement à LayeredConfig.
        val source = IntellijSettingsSource(state)
        assertEquals("COPY", source.load()["outputMode"])
    }

    // ── Mapping nominal ──────────────────────────────────────────────────────

    @Test
    fun `default state maps to nested structure aligned with DefaultsConfigSource`() {
        val source = IntellijSettingsSource(ContextCrawlerSettings.State())
        val map = source.load()
        // Top-level
        assertEquals("recursive-deep", map["strategy"])
        assertEquals("COPY", map["outputMode"])
        // Nested `llm.{}`  — verrou : provider/model NE sont PAS top-level.
        // Sinon le deep-merge avec DefaultsConfigSource (qui a `llm.provider`)
        // produirait une duplication ambiguë.
        @Suppress("UNCHECKED_CAST")
        val llm = map["llm"] as Map<String, Any?>
        assertEquals("claude", llm["provider"])
        assertEquals("claude-sonnet-4-6", llm["model"])
    }

    @Test
    fun `customized state propagates field by field through the map`() {
        val state = ContextCrawlerSettings.State().apply {
            strategy = "shallow"
            outputMode = "LLM_CALL"
            llmProvider = "openai"
            llmModel = "gpt-4o"
        }
        val map = IntellijSettingsSource(state).load()
        assertEquals("shallow", map["strategy"])
        assertEquals("LLM_CALL", map["outputMode"])
        @Suppress("UNCHECKED_CAST")
        val llm = map["llm"] as Map<String, Any?>
        assertEquals("openai", llm["provider"])
        assertEquals("gpt-4o", llm["model"])
    }

    // ── Intégration : Defaults + Settings (priorité 10 écrase 0) ────────────

    @Test
    fun `settings override defaults via LayeredConfig priority chain`() {
        val state = ContextCrawlerSettings.State().apply {
            llmModel = "claude-opus-4-7"
        }
        val cfg = LayeredConfig(listOf(
            DefaultsConfigSource(),
            IntellijSettingsSource(state)
        ))
        // Settings écrase defaults sur le champ touché.
        assertEquals("claude-opus-4-7", cfg.get<String>("llm.model"))
        // Les autres champs (non touchés par Settings) restent les défauts.
        assertEquals("claude", cfg.get<String>("llm.provider"))
        // Et le budget reste celui des defaults (Settings ne le touche pas).
        assertEquals(6, cfg.get<Int>("budget.maxDepth"))
    }

    // ── Intégration : Defaults + Settings + YAML (chaîne complète 0/10/20) ─

    @Test
    fun `YAML overrides settings, settings override defaults — full chain`() {
        // Vérifie le contrat central : 20 > 10 > 0.
        val state = ContextCrawlerSettings.State().apply {
            outputMode = "LLM_CALL"  // settings (priorité 10)
            llmProvider = "openai"
        }
        val yamlText = """
            outputMode: ASK
        """.trimIndent()
        // YAML override pour outputMode uniquement.
        val yaml = object : com.contextextractor.core.config.ConfigSource {
            override val priority: Int = 20
            override fun load(): Map<String, Any?> =
                YamlProjectConfigSource.parse(yamlText, "test")
        }
        val cfg = LayeredConfig(listOf(
            DefaultsConfigSource(),
            IntellijSettingsSource(state),
            yaml
        ))
        assertEquals("ASK", cfg.get<String>("outputMode"),
            "YAML (20) doit écraser settings (10) sur outputMode")
        assertEquals("openai", cfg.get<String>("llm.provider"),
            "YAML ne touche pas provider, settings (10) doit l'emporter sur defaults (0)")
        assertEquals("claude-sonnet-4-6", cfg.get<String>("llm.model"),
            "Ni YAML ni settings ne touchent model — defaults (0) reste")
    }
}

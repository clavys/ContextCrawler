package com.contextextractor.config

import com.contextextractor.adapters.config.YamlProjectConfigSource
import com.contextextractor.adapters.ide.ContextCrawlerSettings
import com.contextextractor.adapters.ide.IntellijSettingsSource
import com.contextextractor.core.config.ConfigSource
import com.contextextractor.core.config.ContextExtractorConfig
import com.contextextractor.core.config.ContextExtractorConfigBinder
import com.contextextractor.core.config.DefaultsConfigSource
import com.contextextractor.core.config.LayeredConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 6-δ — binder typesafe LayeredConfig → ContextExtractorConfig.
//
// **Périmètre des tests** :
//   • Binding nominal : Defaults seuls → ContextExtractorConfig() ;
//   • Fallback : clé absente → défaut data class ;
//   • Validation Budget : invocation à la frontière, throw avec clé YAML ;
//   • Enum strict : outputMode invalide throw avec liste des valeurs valides ;
//   • Coercion type : Number → Double pour temperature ;
//   • Listes et maps homogènes : binding ou fallback ;
//   • Chaîne complète Defaults + Settings + YAML produit le bon objet typé.
class ContextExtractorConfigBinderTest {

    private fun source(prio: Int, data: Map<String, Any?>): ConfigSource =
        object : ConfigSource {
            override val priority: Int = prio
            override fun load(): Map<String, Any?> = data
        }

    private fun bind(vararg sources: ConfigSource): ContextExtractorConfig =
        ContextExtractorConfigBinder(LayeredConfig(sources.toList())).bind()

    // ── Binding nominal — defaults seuls ────────────────────────────────────

    @Test
    fun `defaults source alone produces ContextExtractorConfig() defaults`() {
        // Verrou : DefaultsConfigSource est un miroir parfait de
        // ContextExtractorConfig() (testé en 6-α). Le binder doit le confirmer.
        val cfg = bind(DefaultsConfigSource())
        assertEquals(ContextExtractorConfig(), cfg,
            "Defaults seuls doivent produire le ContextExtractorConfig() canonique")
    }

    @Test
    fun `empty LayeredConfig falls back to all data class defaults`() {
        // Aucune source → toutes les clés absentes → tous les défauts pris.
        val cfg = ContextExtractorConfigBinder(LayeredConfig(emptyList())).bind()
        assertEquals(ContextExtractorConfig(), cfg)
    }

    // ── Validation Budget atomique à la frontière ───────────────────────────

    @Test
    fun `negative maxDepth in YAML throws at bind time with the YAML key`() {
        // VERROU : le diagnostic est levé AU BIND, pas plus tard pendant la
        // récursion. Message inclut la clé YAML pour pointage immédiat.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            bind(
                DefaultsConfigSource(),
                source(20, mapOf("budget" to mapOf("maxDepth" to -3)))
            )
        }
        assertTrue(ex.message!!.contains("budget.maxDepth"),
            "le message doit pointer la clé YAML 'budget.maxDepth' : ${ex.message}")
        assertTrue(ex.message!!.contains("-3"),
            "le message doit inclure la valeur reçue : ${ex.message}")
    }

    @Test
    fun `oversized maxDepth in YAML is silently clamped, no throw`() {
        // Comportement opposé : sur-max est tolérable (juste sur-allocation).
        val cfg = bind(
            DefaultsConfigSource(),
            source(20, mapOf("budget" to mapOf("maxDepth" to 999)))
        )
        // Clamp à 20 (cf Budget.MAX_DEPTH).
        assertTrue(cfg.budget.maxDepth in 1..20)
    }

    // ── outputMode : enum strict ────────────────────────────────────────────

    @Test
    fun `invalid outputMode value throws with allowed values listed`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            bind(source(0, mapOf("outputMode" to "BOGUS")))
        }
        assertTrue(ex.message!!.contains("BOGUS"),
            "le message doit inclure la valeur fautive : ${ex.message}")
        // Énumération des valeurs acceptées dans le message — verrou
        // d'utilisabilité du diagnostic.
        assertTrue(ex.message!!.contains("COPY") &&
            ex.message!!.contains("LLM_CALL") && ex.message!!.contains("ASK"),
            "le message doit lister les valeurs acceptées : ${ex.message}")
    }

    @Test
    fun `valid outputMode strings parse to enum`() {
        for (mode in listOf("COPY", "LLM_CALL", "ASK")) {
            val cfg = bind(source(0, mapOf("outputMode" to mode)))
            assertEquals(ContextExtractorConfig.OutputMode.valueOf(mode), cfg.outputMode)
        }
    }

    @Test
    fun `outputMode default is COPY when unset — first runIde must work`() {
        // Verrou pivot 6-γ propagé jusqu'au binder : sans config explicite,
        // outputMode = COPY (sinon ExtractContextAction étape 7 n'a pas de
        // routage par défaut).
        val cfg = bind()
        assertEquals(ContextExtractorConfig.OutputMode.COPY, cfg.outputMode)
    }

    // ── Coercion de type : Number → Double ──────────────────────────────────

    @Test
    fun `temperature accepts Int from YAML and coerces to Double`() {
        // YAML `temperature: 0` (Int) doit donner 0.0 sans crash de cast.
        val cfg = bind(source(0, mapOf("llm" to mapOf("temperature" to 0))))
        assertEquals(0.0, cfg.llm.temperature)
    }

    @Test
    fun `temperature accepts Double from YAML directly`() {
        val cfg = bind(source(0, mapOf("llm" to mapOf("temperature" to 0.7))))
        assertEquals(0.7, cfg.llm.temperature)
    }

    // ── Listes et maps homogènes ────────────────────────────────────────────

    @Test
    fun `mockSuffixes list is bound from YAML`() {
        val cfg = bind(source(0, mapOf(
            "classification" to mapOf("mockSuffixes" to listOf("Adapter", "Connector"))
        )))
        assertEquals(listOf("Adapter", "Connector"), cfg.classification.mockSuffixes)
    }

    @Test
    fun `heterogeneous list falls back to default rather than throwing`() {
        // Liste qui mélange String et Int → le binder retombe sur le défaut.
        // C'est un cas limite peu utile : on préfère un démarrage stable à un
        // crash de cast à un endroit difficile à diagnostiquer.
        val cfg = bind(
            DefaultsConfigSource(),
            source(20, mapOf(
                "classification" to mapOf("mockSuffixes" to listOf("Service", 42))
            ))
        )
        // Reste celui des défauts (Service, Repository, Gateway).
        assertEquals(listOf("Service", "Repository", "Gateway"),
            cfg.classification.mockSuffixes)
    }

    @Test
    fun `prompt templates map is bound when all values are strings`() {
        val cfg = bind(source(0, mapOf(
            "prompt" to mapOf("templates" to mapOf(
                "system" to "my/sys.md",
                "constraints" to "my/cons.md"
            ))
        )))
        assertEquals(mapOf("system" to "my/sys.md", "constraints" to "my/cons.md"),
            cfg.prompt.templates)
    }

    // ── Templates dir : blanc traité comme absent ──────────────────────────

    @Test
    fun `templates dir blank string is treated as null (no custom dir)`() {
        // YAML `templates.dir: ""` ne doit pas produire un Path("") indéfini ;
        // le binder normalise en null.
        val cfg = bind(source(0, mapOf("templates" to mapOf("dir" to "  "))))
        assertNull(cfg.templates.dir)
    }

    // ── Chaîne complète Defaults + Settings + YAML ─────────────────────────

    @Test
    fun `full chain produces typed config with proper override propagation`() {
        // Verrou central de l'étape 6 : Defaults + Settings + YAML →
        // ContextExtractorConfig avec la priorité 20 > 10 > 0 respectée.
        val state = ContextCrawlerSettings.State().apply {
            outputMode = "LLM_CALL"
            llmProvider = "openai"
        }
        val yamlText = """
            outputMode: ASK
            budget:
              maxDepth: 10
        """.trimIndent()
        val yaml = object : ConfigSource {
            override val priority: Int = 20
            override fun load(): Map<String, Any?> =
                YamlProjectConfigSource.parse(yamlText, "test")
        }
        val cfg = bind(
            DefaultsConfigSource(),
            IntellijSettingsSource(state),
            yaml
        )
        // YAML écrase Settings sur outputMode.
        assertEquals(ContextExtractorConfig.OutputMode.ASK, cfg.outputMode)
        // Settings écrase Defaults sur provider (YAML ne le touche pas).
        assertEquals("openai", cfg.llm.provider)
        // YAML écrase Defaults sur maxDepth.
        assertEquals(10, cfg.budget.maxDepth)
        // maxDtoCount non touché → reste défaut Budget().maxDtoCount = 15.
        assertEquals(15, cfg.budget.maxDtoCount)
        // Validation Budget passée (10 ∈ [1,20]) — ne throw pas.
    }
}

package com.contextextractor.config

import com.contextextractor.core.config.DefaultsConfigSource
import com.contextextractor.core.config.LayeredConfig
import com.contextextractor.core.strategy.Budget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 6-α — vérifie que DefaultsConfigSource :
//   • a la priorité 0 ;
//   • expose les valeurs par défaut ARCHITECTURE.md §8 ;
//   • produit un Budget par défaut qui PASSE la validation (pas de surprise
//     au démarrage avec une config par défaut).
class DefaultsConfigSourceTest {

    private val source = DefaultsConfigSource()

    @Test
    fun `priority is 0 — strictly below IDE settings (10) and YAML (20)`() {
        assertEquals(0, source.priority)
    }

    @Test
    fun `top-level keys cover all ContextExtractorConfig fields`() {
        val data = source.load()
        // Verrou : si on ajoute un champ à ContextExtractorConfig, ce test
        // attire l'attention sur la nécessité de l'exposer ici aussi.
        val expectedKeys = setOf(
            "strategy", "outputMode", "templates",
            "classification", "llm", "prompt", "budget"
        )
        assertEquals(expectedKeys, data.keys,
            "DefaultsConfigSource doit exposer EXACTEMENT les clés top-level " +
                "de ContextExtractorConfig — sinon le binder produira un défaut " +
                "incohérent avec la version typée")
    }

    @Test
    fun `default budget map values match the data class defaults`() {
        // Verrou : le YAML défaut doit donner le même Budget que `Budget()` direct.
        // Sinon un YAML vide produirait un Budget différent du défaut Kotlin.
        @Suppress("UNCHECKED_CAST")
        val budget = source.load()["budget"] as Map<String, Int>
        val defaultBudget = Budget()
        assertEquals(defaultBudget.maxDepth, budget["maxDepth"])
        assertEquals(defaultBudget.maxInitDepth, budget["maxInitDepth"])
        assertEquals(defaultBudget.maxGraphDepth, budget["maxGraphDepth"])
        assertEquals(defaultBudget.maxEstimatedTokens, budget["maxEstimatedTokens"])
    }

    @Test
    fun `defaults pass Budget validation without throwing or clamping`() {
        // Si les valeurs par défaut ne passent pas validated(), le plugin
        // crashe au démarrage avec une config 100% défaut → impardonnable.
        val defaultBudget = Budget()
        val validated = defaultBudget.validated()
        assertEquals(defaultBudget, validated)
    }

    @Test
    fun `LayeredConfig with only DefaultsConfigSource exposes the full default tree`() {
        val cfg = LayeredConfig(listOf(source))
        assertEquals("recursive-deep", cfg.get<String>("strategy"))
        assertEquals(6, cfg.get<Int>("budget.maxDepth"))
        assertEquals("claude", cfg.get<String>("llm.provider"))
        assertTrue(cfg.get<List<*>>("classification.mockSuffixes")!!.contains("Service"))
    }
}

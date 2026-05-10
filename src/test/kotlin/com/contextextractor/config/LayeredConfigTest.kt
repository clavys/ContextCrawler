package com.contextextractor.config

import com.contextextractor.core.config.ConfigSource
import com.contextextractor.core.config.LayeredConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 6-α — vérifie que LayeredConfig :
//   • applique l'ordre des priorités (plus haut écrase plus bas) ;
//   • effectue un deep-merge récursif sur les Map imbriquées ;
//   • préserve les clés présentes en overlay seulement (insertion) ;
//   • supprime les clés via valeur null en overlay (mécanisme de désactivation) ;
//   • accepte les clés pointées (`"a.b.c"`) en lecture typesafe ;
//   • retourne null sur type mismatch sans crash.
class LayeredConfigTest {

    private fun source(prio: Int, data: Map<String, Any?>): ConfigSource =
        object : ConfigSource {
            override val priority: Int = prio
            override fun load(): Map<String, Any?> = data
        }

    // ── Priorité : 20 > 10 > 0 (verrou central de l'étape 6) ─────────────────

    @Test
    fun `higher priority overrides lower — 20 wins over 10 wins over 0`() {
        val cfg = LayeredConfig(listOf(
            source(0,  mapOf("provider" to "default")),
            source(10, mapOf("provider" to "ide")),
            source(20, mapOf("provider" to "yaml"))
        ))
        // YAML écrase IDE écrase defaults — comportement central documenté
        // dans ARCHITECTURE.md §8.
        assertEquals("yaml", cfg.get<String>("provider"))
    }

    @Test
    fun `priority order does not depend on insertion order in the list`() {
        // Les sources sont triées par priorité INTERNE, pas par position.
        val cfg = LayeredConfig(listOf(
            source(20, mapOf("k" to "yaml")),
            source(0,  mapOf("k" to "default")),
            source(10, mapOf("k" to "ide"))
        ))
        assertEquals("yaml", cfg.get<String>("k"))
    }

    // ── Insertion : clé en overlay seulement ────────────────────────────────

    @Test
    fun `key present only in overlay is inserted, not lost`() {
        // Verrou demandé en 6-α : « overlay ajoute une clé ».
        val cfg = LayeredConfig(listOf(
            source(0,  mapOf("a" to 1)),
            source(20, mapOf("b" to 2))
        ))
        assertEquals(1, cfg.get<Int>("a"))
        assertEquals(2, cfg.get<Int>("b"),
            "une clé de l'overlay absente du base doit être conservée")
    }

    @Test
    fun `key present only in base is preserved when overlay does not touch it`() {
        val cfg = LayeredConfig(listOf(
            source(0,  mapOf("untouched" to "kept")),
            source(20, mapOf("other" to "x"))
        ))
        assertEquals("kept", cfg.get<String>("untouched"))
    }

    // ── Suppression via null (mécanisme de désactivation) ───────────────────

    @Test
    fun `null in overlay removes the key from the merged result`() {
        // Verrou demandé en 6-α : « overlay supprime via null ».
        // Permet à un YAML de désactiver une option par défaut.
        val cfg = LayeredConfig(listOf(
            source(0,  mapOf("a" to 1, "b" to 2)),
            source(20, mapOf("a" to null))
        ))
        assertFalse("a" in cfg.raw(),
            "la clé 'a' doit avoir été supprimée par le null overlay")
        assertEquals(2, cfg.raw()["b"],
            "les autres clés non touchées doivent être conservées")
    }

    @Test
    fun `null on absent key is a no-op (does not break)`() {
        val merged = LayeredConfig.deepMerge(
            base = mapOf("only" to 1),
            overlay = mapOf<String, Any?>("absent" to null)
        )
        assertEquals(mapOf("only" to 1), merged)
    }

    // ── Deep-merge récursif sur Map imbriquée ───────────────────────────────

    @Test
    fun `nested maps are merged recursively, scalar overlay wins per leaf`() {
        val cfg = LayeredConfig(listOf(
            source(0,  mapOf("budget" to mapOf("maxDepth" to 6, "maxDtoCount" to 15))),
            source(20, mapOf("budget" to mapOf("maxDepth" to 8)))
        ))
        // maxDepth écrasé à 8, maxDtoCount préservé à 15 (verrou demandé :
        // une clé absente de l'overlay ne disparaît pas du résultat).
        assertEquals(8, cfg.get<Int>("budget.maxDepth"))
        assertEquals(15, cfg.get<Int>("budget.maxDtoCount"))
    }

    @Test
    fun `nested maps insert new keys from overlay without losing base keys`() {
        // Verrou demandé : overlay ajoute des sous-clés en plus.
        val cfg = LayeredConfig(listOf(
            source(0,  mapOf("budget" to mapOf("maxDepth" to 6))),
            source(20, mapOf("budget" to mapOf("maxDtoCount" to 20)))
        ))
        assertEquals(6, cfg.get<Int>("budget.maxDepth"),
            "clé du base seule doit être conservée")
        assertEquals(20, cfg.get<Int>("budget.maxDtoCount"),
            "nouvelle clé du overlay doit être insérée")
    }

    // ── Listes et scalaires : override total (pas de concaténation V1) ──────

    @Test
    fun `list overlay replaces base list entirely — no concatenation`() {
        val cfg = LayeredConfig(listOf(
            source(0,  mapOf("classification" to mapOf(
                "mockSuffixes" to listOf("Service", "Repository")
            ))),
            source(20, mapOf("classification" to mapOf(
                "mockSuffixes" to listOf("Gateway")
            )))
        ))
        // V1 : pas de concaténation. Le YAML qui surcharge prend la main entière.
        // (CLAUDE.md hors scope : merge "intelligent" de listes.)
        val list = cfg.get<List<*>>("classification.mockSuffixes")!!
        assertEquals(listOf("Gateway"), list)
    }

    // ── Lookup : type mismatch et chemin inexistant retournent null ────────

    @Test
    fun `lookup on non-existent key returns null without throwing`() {
        val cfg = LayeredConfig(listOf(source(0, mapOf("a" to 1))))
        assertNull(cfg.get<String>("does.not.exist"),
            "clé inexistante doit retourner null, pas crasher")
    }

    @Test
    fun `lookup with wrong type returns null instead of casting blindly`() {
        val cfg = LayeredConfig(listOf(source(0, mapOf("a" to 1))))
        // 1 est Int — get<String> doit retourner null sans ClassCastException.
        assertNull(cfg.get<String>("a"))
    }

    @Test
    fun `lookup over a scalar with too-deep path returns null gracefully`() {
        val cfg = LayeredConfig(listOf(source(0, mapOf("a" to 1))))
        assertNull(cfg.get<Int>("a.too.deep"))
    }

    // ── raw() : exposition du merge brut ────────────────────────────────────

    @Test
    fun `raw exposes the merged structure for binders`() {
        val cfg = LayeredConfig(listOf(
            source(0,  mapOf("provider" to "x")),
            source(20, mapOf("provider" to "y", "model" to "claude"))
        ))
        val raw = cfg.raw()
        assertEquals("y", raw["provider"])
        assertEquals("claude", raw["model"])
    }

    // ── Idempotence : merge sans source = empty ────────────────────────────

    @Test
    fun `empty source list yields empty merged config`() {
        val cfg = LayeredConfig(emptyList())
        assertTrue(cfg.raw().isEmpty())
        assertNull(cfg.get<String>("anything"))
    }
}

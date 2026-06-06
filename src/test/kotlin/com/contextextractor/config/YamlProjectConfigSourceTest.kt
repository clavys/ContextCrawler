package com.contextextractor.config

import com.contextextractor.adapters.config.YamlProjectConfigSource
import com.contextextractor.core.config.DefaultsConfigSource
import com.contextextractor.core.config.LayeredConfig
import com.contextextractor.core.strategy.Budget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// Sous-étape 6-β — vérifie que YamlProjectConfigSource :
//   • a la priorité 20 (au-dessus IDE=10 au-dessus Defaults=0) ;
//   • lit `.contextextractor.yml` à la racine projet, y compris fichier absent
//     ou vide (cas dégradés graceful) ;
//   • normalise SnakeYAML Long → Int quand la valeur tient dans Int (verrou
//     pivot demandé en 6-β : un YAML avec `200000` doit produire un Int 200000,
//     pas un Long que LayeredConfig.get<Int>() ignorerait silencieusement) ;
//   • s'intègre avec LayeredConfig pour produire un Budget.maxEstimatedTokens
//     valide à partir d'une valeur YAML qui aurait normalement été parsée Long.
class YamlProjectConfigSourceTest {

    // ── Priorité ─────────────────────────────────────────────────────────────

    @Test
    fun `priority is 20 — strictly above IDE settings (10) and Defaults (0)`(
        @TempDir tmp: Path
    ) {
        val source = YamlProjectConfigSource(tmp.resolve("any.yml"))
        assertEquals(20, source.priority)
    }

    // ── Cas dégradés graceful ────────────────────────────────────────────────

    @Test
    fun `missing file returns empty map without throwing`(@TempDir tmp: Path) {
        val source = YamlProjectConfigSource(tmp.resolve("does-not-exist.yml"))
        assertEquals(emptyMap<String, Any?>(), source.load(),
            "fichier absent doit retourner un Map vide, pas crasher")
    }

    @Test
    fun `empty file returns empty map`(@TempDir tmp: Path) {
        val file = tmp.resolve(".contextextractor.yml").also { Files.writeString(it, "") }
        assertEquals(emptyMap<String, Any?>(), YamlProjectConfigSource(file).load())
    }

    @Test
    fun `comments-only file returns empty map`(@TempDir tmp: Path) {
        val file = tmp.resolve(".contextextractor.yml").also {
            Files.writeString(it, """
                # commentaire seul
                # rien d'autre
            """.trimIndent())
        }
        assertEquals(emptyMap<String, Any?>(), YamlProjectConfigSource(file).load())
    }

    @Test
    fun `malformed YAML throws with the file path in the message`(@TempDir tmp: Path) {
        val file = tmp.resolve(".contextextractor.yml").also {
            Files.writeString(it, "key: [unclosed")
        }
        val ex = assertThrows(IllegalStateException::class.java) {
            YamlProjectConfigSource(file).load()
        }
        // Le message DOIT mentionner le chemin du fichier — sinon l'utilisateur
        // ne sait pas où chercher (verrou : pas d'erreur opaque au démarrage).
        assertTrue(ex.message!!.contains(file.toString()) || ex.message!!.contains(".contextextractor"),
            "le message d'erreur doit mentionner le fichier YAML : ${ex.message}")
    }

    @Test
    fun `non-mapping root such as plain list is rejected explicitly`(@TempDir tmp: Path) {
        val file = tmp.resolve(".contextextractor.yml").also {
            Files.writeString(it, "- a\n- b\n")
        }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            YamlProjectConfigSource(file).load()
        }
        assertTrue(ex.message!!.contains("mapping"),
            "le message doit expliquer qu'on attend un mapping YAML")
    }

    // ── Verrou pivot 6-β : Long → Int ────────────────────────────────────────

    @Test
    fun `value 200000 in YAML parses as Int (not Long) so get(Int) works`() {
        // VERROU PIVOT — sans la normalisation, SnakeYAML retourne potentiellement
        // un Long pour 200000, et LayeredConfig.get<Int>() retournerait null.
        val raw = YamlProjectConfigSource.parse(
            "budget:\n  maxEstimatedTokens: 200000\n",
            "test"
        )
        @Suppress("UNCHECKED_CAST")
        val budget = raw["budget"] as Map<String, Any?>
        val token = budget["maxEstimatedTokens"]
        assertTrue(token is Int,
            "200000 doit être normalisé en Int (était: ${token?.javaClass?.name})")
        assertEquals(200000, token)
    }

    @Test
    fun `large value above Short_MAX_VALUE in Int range stays Int`() {
        // Verrou explicite demandé : valeur > 32767 (Short.MAX_VALUE) doit
        // rester Int, pas être perdue comme Long.
        val raw = YamlProjectConfigSource.parse(
            "budget:\n  maxEstimatedTokens: 50000\n",
            "test"
        )
        @Suppress("UNCHECKED_CAST")
        val budget = raw["budget"] as Map<String, Any?>
        assertTrue(budget["maxEstimatedTokens"] is Int)
    }

    @Test
    fun `value above Int_MAX_VALUE stays Long (no narrowing wrap-around)`() {
        // Si le YAML déborde Int, on NE doit PAS faire un narrowing silencieux.
        // Mieux : laisser Long → le binder Budget.validated() refusera l'extrême
        // via son contrat min/max (clamp à 200_000).
        val raw = YamlProjectConfigSource.parse(
            "value: 5000000000\n",
            "test"
        )
        val v = raw["value"]
        assertTrue(v is Long,
            "valeur > Int.MAX_VALUE doit rester Long sans wrap-around (était: ${v?.javaClass?.name})")
        assertEquals(5_000_000_000L, v)
    }

    @Test
    fun `negative Long that fits in Int is also normalized`() {
        val raw = YamlProjectConfigSource.parse("v: -100000\n", "test")
        assertTrue(raw["v"] is Int)
        assertEquals(-100000, raw["v"])
    }

    // ── Intégration LayeredConfig (Defaults + YAML) ──────────────────────────

    @Test
    fun `YAML overrides default budget value when assembled with LayeredConfig`(
        @TempDir tmp: Path
    ) {
        val file = tmp.resolve(".contextextractor.yml").also {
            Files.writeString(it, """
                budget:
                  maxDepth: 12
                  maxEstimatedTokens: 200000
            """.trimIndent())
        }
        val cfg = LayeredConfig(listOf(
            DefaultsConfigSource(),
            YamlProjectConfigSource(file)
        ))
        // La priorité 20 (YAML) écrase la priorité 0 (defaults).
        assertEquals(12, cfg.get<Int>("budget.maxDepth"))
        assertEquals(200_000, cfg.get<Int>("budget.maxEstimatedTokens"))
        // Les autres clés du Budget restent les défauts (verrou demandé : on
        // ne PERD pas les clés non touchées par l'overlay).
        // V1.2 — maxDtoCount supprimé, on vérifie maxGraphDepth à la place.
        assertEquals(4, cfg.get<Int>("budget.maxGraphDepth"))
    }

    @Test
    fun `YAML budget passes through Budget validated correctly`(@TempDir tmp: Path) {
        // Contrat de bout en bout : YAML → LayeredConfig → Budget construit
        // via les valeurs lues → Budget.validated() ne throw pas.
        val file = tmp.resolve(".contextextractor.yml").also {
            Files.writeString(it, """
                budget:
                  maxDepth: 10
                  maxEstimatedTokens: 50000
            """.trimIndent())
        }
        val cfg = LayeredConfig(listOf(
            DefaultsConfigSource(),
            YamlProjectConfigSource(file)
        ))
        val budget = Budget(
            maxDepth = cfg.get<Int>("budget.maxDepth")!!,
            maxInitDepth = cfg.get<Int>("budget.maxInitDepth")!!,
            maxGraphDepth = cfg.get<Int>("budget.maxGraphDepth")!!,
            maxEstimatedTokens = cfg.get<Int>("budget.maxEstimatedTokens")!!
        )
        // Aucune des valeurs ci-dessus n'est < 0 — validated() doit produire
        // un Budget identique (ou clampé au max).
        val validated = budget.validated()
        assertEquals(10, validated.maxDepth)
        assertEquals(50_000, validated.maxEstimatedTokens)
    }

    @Test
    fun `YAML can null out a default value to remove it`(@TempDir tmp: Path) {
        // Mécanisme de désactivation testé en 6-α — ici on l'exerce
        // via le pipeline YAML complet : `templates: dir: ~` doit retirer la clé.
        val file = tmp.resolve(".contextextractor.yml").also {
            Files.writeString(it, """
                templates:
                  dir: ~
            """.trimIndent())
        }
        val cfg = LayeredConfig(listOf(
            DefaultsConfigSource(),
            YamlProjectConfigSource(file)
        ))
        // La clé `templates.dir` a été supprimée ; lookup retourne null.
        assertNull(cfg.get<String>("templates.dir"),
            "YAML `~` (null) doit retirer la clé du défaut")
        // Mais `templates.default` reste, car non touché par l'overlay.
        assertEquals("deep-unit-test", cfg.get<String>("templates.default"))
    }

    @Test
    fun `lists in YAML override defaults entirely (no concatenation)`(@TempDir tmp: Path) {
        val file = tmp.resolve(".contextextractor.yml").also {
            Files.writeString(it, """
                classification:
                  mockSuffixes:
                    - Adapter
            """.trimIndent())
        }
        val cfg = LayeredConfig(listOf(
            DefaultsConfigSource(),
            YamlProjectConfigSource(file)
        ))
        val list = cfg.get<List<*>>("classification.mockSuffixes")!!
        assertEquals(listOf("Adapter"), list,
            "liste YAML écrase la liste défaut, pas de concaténation V1")
    }

    // ── Robustesse des clés ─────────────────────────────────────────────────

    @Test
    fun `non-String keys at root are rejected with a precise message`() {
        // YAML autorise des clés non-string. CLAUDE.md impose camelCase string.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            YamlProjectConfigSource.parse("42: foo\n", "test")
        }
        assertTrue(ex.message!!.contains("camelCase") || ex.message!!.contains("String"),
            "le message doit expliquer la convention de clé : ${ex.message}")
    }
}

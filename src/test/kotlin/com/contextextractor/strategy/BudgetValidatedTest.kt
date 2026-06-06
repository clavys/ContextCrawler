package com.contextextractor.strategy

import com.contextextractor.core.strategy.Budget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 6-α — vérifie le contrat de validation Budget :
//   • valeur < min sain → IllegalArgumentException avec clé YAML + valeur reçue ;
//   • valeur > max sain → clamp silencieux ;
//   • Budget() défaut produit un Budget validé sans changement ;
//   • le crash silencieux qui motive la validation (maxDepth=0) est verrouillé.
class BudgetValidatedTest {

    // ── Defaults : doit valider sans rien changer ────────────────────────────

    @Test
    fun `default Budget passes validation unchanged`() {
        val original = Budget()
        val validated = original.validated()
        assertEquals(original, validated,
            "le Budget par défaut doit être valide sans aucun clamp")
    }

    // ── Throw sur sous-min ───────────────────────────────────────────────────

    @Test
    fun `negative maxDepth throws with explicit YAML key and received value`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Budget(maxDepth = -3).validated()
        }
        // Vérifie les deux verrous demandés : le message DOIT contenir la clé
        // YAML ET la valeur reçue (sinon le diagnostic au démarrage est inutilisable).
        assertTrue(ex.message!!.contains("budget.maxDepth"),
            "le message d'erreur doit mentionner la clé YAML 'budget.maxDepth'")
        assertTrue(ex.message!!.contains("-3"),
            "le message d'erreur doit inclure la valeur reçue (-3)")
    }

    @Test
    fun `zero maxDepth throws — would silently abort recursion at SUT root`() {
        // Verrou pivot du contrat : maxDepth=0 ferait crasher silencieusement
        // la récursion (rappelé en 6-α par l'utilisateur). DOIT throw.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Budget(maxDepth = 0).validated()
        }
        assertTrue(ex.message!!.contains("budget.maxDepth"),
            "le message d'erreur doit pointer la clé YAML")
        assertTrue(ex.message!!.contains("0"),
            "le message d'erreur doit inclure la valeur reçue (0)")
    }

    @Test
    fun `negative maxEstimatedTokens throws with the right yaml key`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Budget(maxEstimatedTokens = -1).validated()
        }
        assertTrue(ex.message!!.contains("budget.maxEstimatedTokens"),
            "le message doit pointer la bonne clé YAML, pas une autre")
    }

    @Test
    fun `tiny maxEstimatedTokens throws — under 100 produces empty prompt`() {
        // Seuil min documenté = 100 (sous ça : prompt vide).
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Budget(maxEstimatedTokens = 50).validated()
        }
        assertTrue(ex.message!!.contains("budget.maxEstimatedTokens"))
    }

    @Test
    fun `negative count fields are rejected with their key`() {
        // Verrou : chaque clé YAML doit produire son propre message
        // d'erreur — pas un message générique « budget invalid ».
        // V1.2 — maxDtoCount/maxMockCount/maxInternalLogicCount supprimés
        // (cf RAPPORT_CONTEXT §9 défaut #3 : V1.2 n'évince plus de résultats
        // par cap). Les cas restants sont les profondeurs structurelles.
        val negativeCases = listOf(
            "budget.maxInitDepth" to { Budget(maxInitDepth = -1).validated() },
            "budget.maxGraphDepth" to { Budget(maxGraphDepth = 0).validated() }
            // ↑ maxGraphDepth min=1 (BFS doit pouvoir descendre d'au moins 1)
        )
        for ((expectedKey, action) in negativeCases) {
            val ex = assertThrows(IllegalArgumentException::class.java) { action() }
            assertTrue(ex.message!!.contains(expectedKey),
                "le message pour $expectedKey doit le contenir, était: ${ex.message}")
        }
    }

    // ── Clamp sur sur-max (silencieux) ───────────────────────────────────────

    @Test
    fun `oversized positive maxDepth is clamped silently`() {
        // Verrou : valeur dégénérée positive ne throw pas, juste sur-allouée.
        // Comportement explicite côté utilisateur : pas de surprise au runtime.
        val budget = Budget(maxDepth = 999).validated()
        assertTrue(budget.maxDepth <= 20,
            "maxDepth doit être clampé à un max raisonnable (≤ 20)")
        assertTrue(budget.maxDepth > 0,
            "le clamp ne doit pas introduire une valeur 0 inutilisable")
    }

    @Test
    fun `oversized maxEstimatedTokens is clamped silently to a workable max`() {
        val budget = Budget(maxEstimatedTokens = 10_000_000).validated()
        assertTrue(budget.maxEstimatedTokens in 100..200_000,
            "maxEstimatedTokens hors plage clampé à 200_000 max, était: ${budget.maxEstimatedTokens}")
    }

    // V1.2 — Test "oversized counts are clamped silently" supprimé.
    // Les caps maxDtoCount/maxMockCount/maxInternalLogicCount ont été retirés
    // de Budget (cf RAPPORT_CONTEXT §9 défaut #3) : V1.2 n'évince plus les
    // résultats par cap quantitatif — seul `maxDepth` borne le crawl PASSE 1.

    // ── Cas limite : maxInitDepth=0 est ACCEPTÉ ──────────────────────────────

    @Test
    fun `maxInitDepth zero is allowed — disables init protocol recursion`() {
        // 0 est sémantiquement valide pour maxInitDepth (= pas de récursion BLOC 7).
        // Documente la frontière entre 0-acceptable (init désactivable) et
        // 0-rejeté (maxDepth qui crashe).
        val budget = Budget(maxInitDepth = 0).validated()
        assertEquals(0, budget.maxInitDepth)
    }
}

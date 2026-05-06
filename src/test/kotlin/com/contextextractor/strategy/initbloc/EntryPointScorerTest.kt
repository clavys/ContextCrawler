package com.contextextractor.strategy.initbloc

import com.contextextractor.core.model.init.InitPathKind
import com.contextextractor.strategies.recursive.initbloc.EntryPointScorer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4e-γ — verrouille les 6 poids littéraux du scorer §4.4.
// Chaque test isole un poids en mettant tous les autres à zéro / neutre.
// Si la spec change, la table-driven explose en clair plutôt que de masquer
// la dérive dans les tests intégrés du finder.
class EntryPointScorerTest {

    @Test
    fun `baseline transitif tout zéro returnsVoid produit un score de zéro`() {
        val s = EntryPointScorer.score(
            depth = 0, parametersRequired = 0, externalCalls = 0,
            sideEffects = 0, returnsVoid = true, kind = InitPathKind.PUBLIC_TRANSITIF
        )
        assertEquals(0, s)
    }

    @Test
    fun `depth pèse 100 par cran`() {
        val cas = listOf(0 to 0, 1 to 100, 2 to 200, 4 to 400)
        cas.forEach { (depth, expected) ->
            val s = EntryPointScorer.score(
                depth = depth, parametersRequired = 0, externalCalls = 0,
                sideEffects = 0, returnsVoid = true, kind = InitPathKind.PUBLIC_TRANSITIF
            )
            assertEquals(expected, s, "depth=$depth")
        }
    }

    @Test
    fun `parametersRequired pèse 30 par paramètre`() {
        val s = EntryPointScorer.score(
            depth = 0, parametersRequired = 3, externalCalls = 0,
            sideEffects = 0, returnsVoid = true, kind = InitPathKind.PUBLIC_TRANSITIF
        )
        assertEquals(90, s)
    }

    @Test
    fun `externalCalls pèse 20 et sideEffects pèse 50 par item`() {
        val s = EntryPointScorer.score(
            depth = 0, parametersRequired = 0, externalCalls = 4, // 4 * 20 = 80
            sideEffects = 2,                                       // 2 * 50 = 100
            returnsVoid = true, kind = InitPathKind.PUBLIC_TRANSITIF
        )
        assertEquals(180, s)
    }

    @Test
    fun `non-void ajoute 25`() {
        val s = EntryPointScorer.score(
            depth = 0, parametersRequired = 0, externalCalls = 0,
            sideEffects = 0, returnsVoid = false, kind = InitPathKind.PUBLIC_TRANSITIF
        )
        assertEquals(25, s)
    }

    @Test
    fun `bonus PUBLIC_POST_CONSTRUCT vaut -200, bonus ctor vaut -100`() {
        val pc = EntryPointScorer.score(
            depth = 0, parametersRequired = 0, externalCalls = 0,
            sideEffects = 0, returnsVoid = true, kind = InitPathKind.PUBLIC_POST_CONSTRUCT
        )
        val ctor = EntryPointScorer.score(
            depth = 0, parametersRequired = 0, externalCalls = 0,
            sideEffects = 0, returnsVoid = true, kind = InitPathKind.IMPLICIT_VIA_CONSTRUCTOR
        )
        assertEquals(-200, pc)
        assertEquals(-100, ctor)
        // Sanity : le @PostConstruct gagne sur un transit équivalent.
        val transit = EntryPointScorer.score(
            depth = 0, parametersRequired = 0, externalCalls = 0,
            sideEffects = 0, returnsVoid = true, kind = InitPathKind.PUBLIC_TRANSITIF
        )
        assertTrue(pc < transit, "@PostConstruct doit gagner sur un transit équivalent")
    }
}

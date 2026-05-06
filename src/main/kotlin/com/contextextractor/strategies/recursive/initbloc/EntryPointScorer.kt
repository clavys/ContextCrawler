package com.contextextractor.strategies.recursive.initbloc

import com.contextextractor.core.model.init.InitPathKind

// Scorer du BFS — STRATEGIE.md §4.4 (fonction `scorer`).
//
// Plus le score est BAS, meilleur est le candidat. Les poids sont littéraux et
// figés par la spec — toute modification doit passer par STRATEGIE.md d'abord.
//
// Formule (verrouillée par EntryPointScorerTest) :
//   score  = depth * 100
//          + parametersRequired * 30
//          + externalCalls * 20
//          + sideEffects * 50
//          + (returnsVoid ? 0 : 25)
//          + (kind == PUBLIC_POST_CONSTRUCT  ? -200 : 0)
//          + (kind == IMPLICIT_VIA_CONSTRUCTOR ? -100 : 0)
//
// Note : le ctor est par ailleurs court-circuité dans EntryPointFinder à
// score = 0 (§4.4 : `score = 0` pour la branche IMPLICIT_VIA_CONSTRUCTOR).
// Ce scorer n'est appelé que pour les chemins PUBLIC_*. Le bonus
// IMPLICIT_VIA_CONSTRUCTOR est exposé pour symétrie / tests, pas appelé en prod.
object EntryPointScorer {

    fun score(
        depth: Int,
        parametersRequired: Int,
        externalCalls: Int,
        sideEffects: Int,
        returnsVoid: Boolean,
        kind: InitPathKind
    ): Int {
        var s = 0
        s += depth * 100
        s += parametersRequired * 30
        s += externalCalls * 20
        s += sideEffects * 50
        if (!returnsVoid) s += 25
        when (kind) {
            InitPathKind.PUBLIC_POST_CONSTRUCT -> s -= 200
            InitPathKind.IMPLICIT_VIA_CONSTRUCTOR -> s -= 100
            InitPathKind.PUBLIC_TRANSITIF -> Unit
        }
        return s
    }
}

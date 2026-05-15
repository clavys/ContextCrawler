package com.contextextractor.strategies.recursive.initbloc

import com.contextextractor.core.model.init.InitStrategy

// `calculerOrdreTopologique` (§4.7) — n'est PAS un vrai tri topologique sur un
// DAG. C'est un tri par priorité fixe à 5 niveaux. Implémenté ici comme une
// extension `Int`-valuée pour permettre `entries.sortedBy { it.value.strategy
// .ordinalPriority() }` côté RecursiveDeepStrategy. Pas de dépendance graphe.
//
// Ordre §4.7 (du plus prioritaire au moins prioritaire — sortedBy croissant) :
//   0 — implicite, fait au moment du `new SUT(...)` :
//          CONSTRUCTOR, MOCKITO_INJECT_MOCKS, IMPLICIT, IMPLICIT_VIA_CONSTRUCTOR
//   1 — SETTER : appel direct après `new SUT(...)`, avant tout init métier
//   2 — CALL_POST_CONSTRUCT : après les setters (init du framework simulé)
//   3 — CALL_PUBLIC* / CALL_SAME_PACKAGE : APRÈS les `when(...)` Mockito,
//          donc en fin de @BeforeEach
//   4 — UNTESTABLE_AS_IS : groupé en queue ; le rendu décide quoi en faire
//
// Note : la spec §4.7 liste 5 niveaux numérotés 1-5 ; ici l'ordinal commence à
// 0 pour aligner avec le bucket « instanciation », plus pratique pour sortedBy.
fun InitStrategy.ordinalPriority(): Int = when (this) {
    InitStrategy.CONSTRUCTOR,
    InitStrategy.MOCKITO_INJECT_MOCKS,
    InitStrategy.IMPLICIT,
    InitStrategy.IMPLICIT_VIA_CONSTRUCTOR -> 0

    is InitStrategy.SETTER -> 1

    is InitStrategy.CALL_POST_CONSTRUCT -> 2

    is InitStrategy.CALL_PUBLIC,
    is InitStrategy.CALL_PUBLIC_WITH_STUBS,
    is InitStrategy.CALL_PUBLIC_WITH_ARGS,
    is InitStrategy.CALL_PUBLIC_TRANSITIVE,
    is InitStrategy.CALL_SAME_PACKAGE -> 3

    is InitStrategy.UNTESTABLE_AS_IS -> 4
}

package com.contextextractor.core.classifier

import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.model.refs.ClassReference

// PORT principal de la PASSE 2 — V1.2.
//
// Diffère de `ClassClassifier` (V1.1) par sa signature : il consomme une
// `ClassReference` complète (avec tous ses `UsageSite` agrégés) plutôt
// qu'un triplet `(type, descriptor, callerContext)`. Cette différence est
// fondamentale :
//
//   V1.1 : décision PAR site d'appel — eager, single-pass — patches post-hoc
//          nécessaires pour réconcilier (Bug U promotion, Bug B filter...)
//   V1.2 : décision PAR classe — informée par TOUS ses sites — pas de patch
//
// **Garantie de pureté** : aucune dépendance au port `CodeIntrospector`.
// Le classifier ne fait JAMAIS d'I/O ; il décide à partir des données
// déjà collectées dans la `ClassReference`. Le caller (PASSE 3 materializer
// ou pipeline) est responsable de fournir `descriptorMethods` quand la
// règle « toutes méthodes triviales → DTO » doit être évaluée.
//
// **Coexistence V1.1** : l'ancien `ClassClassifier` reste vivant et utilisé
// par `RecursiveDeepStrategy.extractCore` V1.1. La bascule vers ce nouveau
// classifier se fait en Phase 3 (réécriture extractCore).
interface ContextAwareClassifier {

    fun classify(
        ref: ClassReference,
        hierarchyFqns: Set<String>,
        frameworkPrefixes: List<String> = emptyList(),
        descriptorMethods: List<MethodSignature> = emptyList()
    ): ExtractionMode
}

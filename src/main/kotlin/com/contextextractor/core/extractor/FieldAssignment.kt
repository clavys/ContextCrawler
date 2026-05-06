package com.contextextractor.core.extractor

// Assignation de champ détectée dans un corps de méthode — distincte de
// FieldAccess (qui agrège lectures et écritures via un booléen `write` mais
// sans porter le contexte conditionnel/null-check).
//
// Source unique : STRATEGIE.md §4.2 « Détection des méthodes assignatrices ».
// Consommé par BLOC 7 (collecterSourcesInit § 4.1 → MethodInitializer) et par
// estAutoInitialisé (§4.5 stratégie 10) qui compare positions de la première
// lecture vs première assignation dans la méthode cible.
//
// `rhsExpression` porte le texte source de la RHS — utilisé en debug et au
// rendu du prompt, mais pas dans la décision algorithmique.
//
// `rhsType` est le type résolu de la RHS quand calculable. Null si la RHS
// est trop complexe pour PSI (cf §8bis.1).
//
// `isConditional` = vrai ssi l'assignation est imbriquée dans un nœud
// conditionnel (if/while/switch/ternaire). `conditionIsNullCheck` = vrai ssi
// la condition contient `this.<fieldName> == null` ou équivalent — c'est le
// pattern de lazy-init légitime (CALL_PUBLIC peut quand même être candidat).
data class FieldAssignment(
    val ownerType: String,
    val fieldName: String,
    val rhsExpression: String,
    val rhsType: ResolvedType?,
    val isConditional: Boolean = false,
    val conditionIsNullCheck: Boolean = false
)

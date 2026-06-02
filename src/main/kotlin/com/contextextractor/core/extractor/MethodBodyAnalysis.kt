package com.contextextractor.core.extractor

// Résultat de l'analyse structurelle du corps d'une méthode — STRATEGIE.md
// §3.1 BLOC 2. Capturé en UN SEUL parcours AST par l'introspector.
//
// Distinct de `listMethodCalls` / `listFieldAccesses` (accès ciblés) : ici on
// collecte les éléments structurels que §3.1 BLOC 2 énumère et que le LLM
// utilise pour cadrer le chemin nominal — instanciations, lambdas attendues,
// exceptions lancées / catchées, branches conditionnelles, sources
// non-déterministes.
//
// Tous les champs sont vides par défaut : un introspector qui ne sait pas
// analyser le corps (cas dégradé §8bis, port stub) retourne `MethodBodyAnalysis()`
// neutre — le pipeline aval ne rend simplement aucune sous-section.
data class MethodBodyAnalysis(
    val instantiations: List<ResolvedType> = emptyList(),
    val expectedLambdas: List<String> = emptyList(),
    val thrownExceptions: List<ThrownExceptionRef> = emptyList(),
    val caughtExceptions: List<CaughtExceptionRef> = emptyList(),
    val conditionalBranches: List<ConditionalBranchRef> = emptyList(),
    val nonDeterministicSources: List<String> = emptyList()
)

// `new TypeException("message")` détecté dans le corps — `message` non-null
// uniquement si l'argument est un littéral constant (§3.1 « message constSiPossible »).
data class ThrownExceptionRef(val typeFqn: String, val message: String? = null)

// Bloc catch — `types` supporte le multi-catch (`catch (A | B e)`). `callsInCatch`
// résume les appels du corps du catch (`type.methode`).
data class CaughtExceptionRef(
    val types: List<String>,
    val callsInCatch: List<String> = emptyList()
)

// Branche conditionnelle — `kind` ∈ {IF, SWITCH, TERNARY}. `condition` est le
// texte source de la condition ; `constants` les littéraux qui y apparaissent
// (utiles au LLM pour choisir les valeurs du chemin nominal).
data class ConditionalBranchRef(
    val kind: String,
    val condition: String,
    val constants: List<String> = emptyList()
)

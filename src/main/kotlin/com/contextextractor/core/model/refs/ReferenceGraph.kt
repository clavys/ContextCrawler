package com.contextextractor.core.model.refs

import com.contextextractor.core.extractor.MethodSignature

// Graphe de références — résultat de la PASSE 1 du pipeline V1.2.
//
// **Forme** : Map immuable FQN → ClassReference. L'ordre d'itération suit
// l'ordre de découverte BFS (LinkedHashMap côté builder) — utile pour les
// renderers déterministes mais pas garanti par le contrat de cette classe.
//
// **Rôle** : représentation intermédiaire entre l'extraction PSI et la
// classification. Le builder peuple ce graphe SANS prendre de décision
// Mode ; le classifier le lit et produit `Map<String, ExtractionMode>` en
// PASSE 2 ; le materializer transforme tout ça en `ContextResult` final
// en PASSE 3.
//
// **Pourquoi cette indirection** : éliminer la cascade de patches V1.1
// (Bug U promotion, Bug B filter, Bug I/S/T essential pre-amorçage,
// Bug A/D budget eviction). En V1.1, chaque classe était classifiée et
// commitée immédiatement lors du BFS → décisions prises avec info partielle
// → patches post-hoc pour corriger. En V1.2, la décision est différée
// jusqu'à avoir TOUT le contexte d'usage agrégé.
//
// **Immutabilité** : aucune mutation après construction. Le builder produit
// l'instance finale via son constructeur ; classifier et materializer lisent
// only. Tests faciles, threadsafe by-construction.
data class ReferenceGraph(
    val byFqn: Map<String, ClassReference>,
    // Méthodes intra-SUT visitées par le BFS — alimentera InternalLogic en
    // PASSE 3. Distinct de byFqn car les internals sont par-MÉTHODE, pas par
    // classe.
    val visitedInternalMethods: List<VisitedMethod> = emptyList(),
    // Raisons d'arrêt du BFS (depth limit, classe non résolvable...) propagées
    // au ContextResult final via le materializer.
    val truncationReasons: List<String> = emptyList()
) {

    // Lookup direct par FQN. Retourne null si la classe n'a jamais été
    // rencontrée par le BFS.
    fun referenceFor(fqn: String): ClassReference? = byFqn[fqn]

    // Itérateur sur toutes les références — ordre BFS si le builder utilise
    // LinkedHashMap. Le classifier les parcourt en PASSE 2.
    fun allReferences(): Collection<ClassReference> = byFqn.values

    // Filtre par prédicat — sucre syntaxique pour les tests et le materializer.
    // Ex : `graph.filter { it.isCalledAsInstance }` → tous les futurs mocks.
    fun filter(predicate: (ClassReference) -> Boolean): List<ClassReference> =
        byFqn.values.filter(predicate)

    // Vrai si une classe est référencée dans le graphe. Pas le même que
    // `descriptor != null` : une classe peut être référencée sans être
    // résolvable (§8bis.1).
    operator fun contains(fqn: String): Boolean = fqn in byFqn

    val size: Int get() = byFqn.size

    companion object {
        val EMPTY = ReferenceGraph(emptyMap())
    }
}

// Méthode intra-SUT visitée par le BFS — alimente `InternalLogic` en PASSE 3.
//
// `isFrameworkBoundary` distingue les méthodes qui descendent dans un
// préfixe framework (typiquement javax.faces/* sur les controleurs JSF) —
// celles-ci seront matérialisées avec `stubViaSpy = true` et leur corps
// ne sera pas lu.
data class VisitedMethod(
    val ownerFqn: String,
    val signature: MethodSignature,
    val isFrameworkBoundary: Boolean = false,
    val frameworkPrefixesHit: List<String> = emptyList()
)

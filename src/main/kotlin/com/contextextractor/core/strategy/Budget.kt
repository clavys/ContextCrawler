package com.contextextractor.core.strategy

// Limites quantitatives imposées au crawling — voir STRATEGIE.md §2.3.
// Dépassement → marquer ContextTree.truncated = true.
//
// **Refactor V1.2 (Phase 4)** : les caps de résultat `maxMockCount`,
// `maxDtoCount`, `maxInternalLogicCount` ont été supprimés. La V1.1 les
// utilisait pour évincer rétroactivement des mocks/DTOs/internals collectés
// par BLOC 6, ce qui ouvrait des trous de cohérence : un mock parasite
// pouvait évincer un mock essentiel (Bug I), un DTO non-instanciable était
// supprimé du prompt mais référencé par un setter (Bug DD), un service
// directement appelé par target était droppé (Bug M).
//
// La V1.2 ne *cap pas* le résultat : `ReferenceGraphBuilder` (PASSE 1)
// crawle exhaustivement à partir de la racine, le classifier (PASSE 2)
// décide une fois pour chaque type rencontré, et `ResultMaterializer`
// (PASSE 3) émet tout ce qui est classifié MOCK/DTO/INTERNAL. La seule
// limite est `maxDepth` pour borner le BFS — purement structurelle, pas
// quantitative.
//
// **Valeurs par défaut V1.2** :
//   maxDepth=6                — profondeur BFS du graphe de références
//   maxInitDepth=2            — profondeur de récursion BLOC 7 sur init
//   maxGraphDepth=4           — BFS du callGraph intra-SUT (transitive usage)
//   maxEstimatedTokens=50000  — coupure prompt avant troncature
data class Budget(
    val maxDepth: Int = 6,
    val maxInitDepth: Int = 2,
    val maxGraphDepth: Int = 4,
    val maxEstimatedTokens: Int = 50_000
) {

    // Retourne une copie validée. Le min est strict (throw si non respecté) ;
    // le max est clampé silencieusement (valeur trop grosse ≠ erreur, juste
    // budget plus large que nécessaire).
    fun validated(): Budget = copy(
        maxDepth = ensureRange("budget.maxDepth", maxDepth, MIN_DEPTH, MAX_DEPTH),
        maxInitDepth = ensureRange("budget.maxInitDepth", maxInitDepth, MIN_INIT_DEPTH, MAX_INIT_DEPTH),
        maxGraphDepth = ensureRange("budget.maxGraphDepth", maxGraphDepth, MIN_GRAPH_DEPTH, MAX_GRAPH_DEPTH),
        maxEstimatedTokens = ensureRange(
            "budget.maxEstimatedTokens", maxEstimatedTokens, MIN_TOKENS, MAX_TOKENS
        )
    )

    private fun ensureRange(key: String, value: Int, min: Int, max: Int): Int {
        require(value >= min) {
            // Message explicite : clé YAML + valeur reçue + minimum requis.
            "$key must be ≥ $min (got $value)"
        }
        return value.coerceAtMost(max)
    }

    companion object {
        // Minimums : sous ces seuils, la récursion produit un résultat vide
        // ou crashe. Au-dessus, le budget est juste sur-alloué.
        private const val MIN_DEPTH = 1
        private const val MAX_DEPTH = 20
        private const val MIN_INIT_DEPTH = 0
        private const val MAX_INIT_DEPTH = 10
        private const val MIN_GRAPH_DEPTH = 1
        private const val MAX_GRAPH_DEPTH = 20
        private const val MIN_TOKENS = 100
        private const val MAX_TOKENS = 200_000
    }
}

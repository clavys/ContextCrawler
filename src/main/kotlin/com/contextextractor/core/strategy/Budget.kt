package com.contextextractor.core.strategy

// Limites quantitatives imposées à la récursion — voir STRATEGIE.md §2.3.
// Dépassement → marquer ContextTree.truncated = true.
//
// **Valeurs par défaut** (validées par les sub-tests V1) :
//   maxDepth=6                — profondeur PSI max d'un BFS de classes
//   maxInitDepth=2            — profondeur de récursion BLOC 7 sur init
//   maxGraphDepth=4           — BFS du callGraph intra-SUT
//   maxDtoCount=15            — nombre max de DTOs collectés
//   maxMockCount=10           — nombre max de mocks collectés
//   maxInternalLogicCount=12  — nombre max de méthodes internes visitées
//   maxEstimatedTokens=50000  — coupure prompt avant troncature
//
// **Pourquoi 50_000 et pas 8_000** : la cible LLM est un modèle local (pas
// d'API cloud → pas de coût par token). La qualité de l'extraction prime
// sur l'économie. 50_000 tokens représentent un contexte large mais réaliste
// pour la plupart des LLM modernes (Claude/GPT/Llama 3.x ont 100k+ de window).
// La troncature ne se déclenche désormais que sur des SUT vraiment volumineux.
//
// **Validation** (cf [validated]) — verrou demandé étape 6 :
//   • valeur < min sain → `IllegalArgumentException` avec clé YAML + valeur reçue
//   • valeur > max sain → clamp silencieux (les valeurs dégénérées positives
//     ne crashent pas, juste sur-allouent inutilement → tolérables)
//   • Les seuils min sont choisis pour éviter le crash silencieux : maxDepth=0
//     bloquerait la récursion à la racine, donc min=1 ; maxEstimatedTokens<100
//     produirait un prompt vide, donc min=100.
data class Budget(
    val maxDepth: Int = 6,
    val maxInitDepth: Int = 2,
    val maxGraphDepth: Int = 4,
    val maxDtoCount: Int = 15,
    val maxMockCount: Int = 10,
    val maxInternalLogicCount: Int = 12,
    val maxEstimatedTokens: Int = 50_000
) {

    // Retourne une copie validée. Le min est strict (throw si non respecté) ;
    // le max est clampé silencieusement (valeur trop grosse ≠ erreur, juste
    // budget plus large que nécessaire).
    fun validated(): Budget = copy(
        maxDepth = ensureRange("budget.maxDepth", maxDepth, MIN_DEPTH, MAX_DEPTH),
        maxInitDepth = ensureRange("budget.maxInitDepth", maxInitDepth, MIN_INIT_DEPTH, MAX_INIT_DEPTH),
        maxGraphDepth = ensureRange("budget.maxGraphDepth", maxGraphDepth, MIN_GRAPH_DEPTH, MAX_GRAPH_DEPTH),
        maxDtoCount = ensureRange("budget.maxDtoCount", maxDtoCount, MIN_COUNT, MAX_COUNT),
        maxMockCount = ensureRange("budget.maxMockCount", maxMockCount, MIN_COUNT, MAX_COUNT),
        maxInternalLogicCount = ensureRange(
            "budget.maxInternalLogicCount", maxInternalLogicCount, MIN_COUNT, MAX_COUNT
        ),
        maxEstimatedTokens = ensureRange(
            "budget.maxEstimatedTokens", maxEstimatedTokens, MIN_TOKENS, MAX_TOKENS
        )
    )

    private fun ensureRange(key: String, value: Int, min: Int, max: Int): Int {
        require(value >= min) {
            // Message explicite : clé YAML + valeur reçue + minimum requis.
            // Sinon, dégénérescence silencieuse au démarrage = diagnostic
            // inutilisable (verrou rappelé en 6-α par l'utilisateur).
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
        private const val MIN_COUNT = 0
        private const val MAX_COUNT = 200
        private const val MIN_TOKENS = 100
        private const val MAX_TOKENS = 200_000
    }
}

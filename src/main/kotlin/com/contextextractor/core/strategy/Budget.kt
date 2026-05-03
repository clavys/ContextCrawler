package com.contextextractor.core.strategy

// Limites quantitatives imposées à la récursion — voir STRATEGIE.md §2.3.
// Dépassement → marquer ContextTree.truncated = true.
data class Budget(
    val maxDepth: Int = 6,
    val maxInitDepth: Int = 2,
    val maxGraphDepth: Int = 4,
    val maxDtoCount: Int = 15,
    val maxMockCount: Int = 10,
    val maxInternalLogicCount: Int = 12,
    val maxEstimatedTokens: Int = 8000
)

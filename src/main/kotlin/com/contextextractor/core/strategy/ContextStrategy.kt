package com.contextextractor.core.strategy

import com.contextextractor.core.model.ContextTree

// Une stratégie d'extraction = une application du cœur (ARCHITECTURE.md §6).
// V1 : RecursiveDeepStrategy. Stratégies shallow et git-diff hors scope.
interface ContextStrategy {
    val id: String
    val displayName: String
    val description: String

    fun extract(input: StrategyInput): ContextTree
}

package com.contextextractor.core.strategy

// Registre de stratégies — alimenté par l'extension point IntelliJ
// "contextStrategy" (voir ARCHITECTURE.md §6).
class StrategyRegistry(private val strategies: List<ContextStrategy>) {

    fun all(): List<ContextStrategy> = strategies

    fun byId(id: String): ContextStrategy? = strategies.firstOrNull { it.id == id }

    fun require(id: String): ContextStrategy =
        byId(id) ?: throw IllegalStateException("Strategy not found: $id")
}

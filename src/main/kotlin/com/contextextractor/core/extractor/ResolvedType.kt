package com.contextextractor.core.extractor

// Type résolu — voir STRATEGIE.md §3.5 et table FR→EN d'ARCHITECTURE.md §3bis
// (TypeResolu → ResolvedType).
data class ResolvedType(
    val rawType: String,
    val fqName: String,
    val typeArgs: List<ResolvedType> = emptyList(),
    val isCollection: Boolean = false,
    val isContainer: Boolean = false,
    val isWildcard: Boolean = false,
    val isUnresolvedTypeParameter: Boolean = false,
    val nullable: Boolean = false
) {
    fun flatten(): List<String> =
        listOf(rawType) + typeArgs.flatMap { it.flatten() }
}

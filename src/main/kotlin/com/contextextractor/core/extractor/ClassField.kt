package com.contextextractor.core.extractor

// Champ déclaré sur une classe — remonté par CodeIntrospector.listFields().
// `declaredIn` peut différer de la classe interrogée si le champ provient
// d'une super-classe (cf STRATEGIE.md §3.1 BLOC 1, hiérarchie complète).
data class ClassField(
    val name: String,
    val type: ResolvedType,
    val visibility: String,
    val annotations: List<String> = emptyList(),
    val declaredIn: String
)

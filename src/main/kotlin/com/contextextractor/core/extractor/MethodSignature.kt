package com.contextextractor.core.extractor

// Paramètre formel d'une méthode (FR Parametre → EN Parameter).
data class Parameter(
    val name: String,
    val type: ResolvedType,
    val annotations: List<String> = emptyList()
)

// Signature complète d'une méthode (FR SignatureMethode → EN MethodSignature).
// `isStatic` distingue les méthodes statiques des méthodes d'instance — requis
// par detectPattern() (§3.4) pour repérer `builder()` / `of(...)` / factories,
// et par §6c-bis pour valider qu'un appel est bien statique côté call site.
data class MethodSignature(
    val name: String,
    val returnType: ResolvedType,
    val parameters: List<Parameter>,
    val annotations: List<String> = emptyList(),
    val declaredThrows: List<String> = emptyList(),
    val visibility: String,
    val isStatic: Boolean = false
) {
    // Clé canonique utilisée par le registre de visites (CleVisite → VisitKey).
    fun canonical(): String =
        "$name(${parameters.joinToString(",") { it.type.fqName }})"
}

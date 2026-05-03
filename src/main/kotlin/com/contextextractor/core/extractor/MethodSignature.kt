package com.contextextractor.core.extractor

// Paramètre formel d'une méthode (FR Parametre → EN Parameter).
data class Parameter(
    val name: String,
    val type: ResolvedType,
    val annotations: List<String> = emptyList()
)

// Signature complète d'une méthode (FR SignatureMethode → EN MethodSignature).
data class MethodSignature(
    val name: String,
    val returnType: ResolvedType,
    val parameters: List<Parameter>,
    val annotations: List<String> = emptyList(),
    val declaredThrows: List<String> = emptyList(),
    val visibility: String
) {
    // Clé canonique utilisée par le registre de visites (CleVisite → VisitKey).
    fun canonical(): String =
        "$name(${parameters.joinToString(",") { it.type.fqName }})"
}

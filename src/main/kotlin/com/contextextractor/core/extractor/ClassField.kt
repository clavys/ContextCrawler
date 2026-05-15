package com.contextextractor.core.extractor

// Champ déclaré sur une classe — remonté par CodeIntrospector.listFields().
// `declaredIn` peut différer de la classe interrogée si le champ provient
// d'une super-classe (cf STRATEGIE.md §3.1 BLOC 1, hiérarchie complète).
//
// `isFinal` est requis par BLOC 3 pour identifier les champs final + Lombok
// @RequiredArgsConstructor (§3.1 « Champ est final ET initialisé via
// constructeur »).
//
// `initializerExpression` porte le texte source de PsiField.getInitializer()
// — requis par BLOC 7 source FIELD_INITIALIZER (§4.1) pour décider IMPLICIT
// quand l'initialisation est une constante sûre. Null si le champ n'a pas
// d'initialiseur in-line.
data class ClassField(
    val name: String,
    val type: ResolvedType,
    val visibility: String,
    val annotations: List<String> = emptyList(),
    val declaredIn: String,
    val isFinal: Boolean = false,
    val initializerExpression: String? = null
)

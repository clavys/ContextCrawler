package com.contextextractor.core.extractor

// Représentation langage-agnostique d'un fichier source. Pas de PsiFile ici.
data class SourceFile(val path: String, val language: String)

// Position du curseur — utilisée comme entrée pour la stratégie d'extraction.
data class CursorLocation(val file: SourceFile, val offset: Int)

// Symbole résolu (méthode, classe, champ) — opaque côté core/.
// L'adapter PSI mappe ses propres handles vers ces ids stables.
data class Symbol(
    val id: String,
    val fqn: String,
    val kind: SymbolKind
)

enum class SymbolKind { CLASS, METHOD, FIELD, PARAMETER, OTHER }

// Référence d'annotation portée par une cible (classe, méthode, champ, param).
data class AnnotationRef(
    val fqn: String,
    val attributes: Map<String, String> = emptyMap()
)

// Cible annotable — abstraction pour CodeIntrospector.listAnnotations().
sealed interface AnnotatedTarget {
    val fqn: String

    data class OnClass(override val fqn: String) : AnnotatedTarget
    data class OnMethod(override val fqn: String, val signature: String) : AnnotatedTarget
    data class OnField(override val fqn: String, val name: String) : AnnotatedTarget
    data class OnParameter(override val fqn: String, val signature: String, val index: Int) : AnnotatedTarget
}

// Appel de méthode détecté dans un corps de méthode.
//
// V1.4.5 Bug QQ — `targetType` est la classe DÉCLARANTE de la méthode résolue ;
// sur une hiérarchie de modeles génériques (Astrea 4.4), les appels d'un même
// receveur se dispersent alors sur plusieurs classes (`this.modele.getX()` →
// AbstractSaisieMessageModele, `this.modele.getY()` → SaisieMessage01Modele)
// et le prompt prescrit DEUX mocks pour UN objet runtime. Les deux champs
// optionnels portent la vue CALL-SITE :
//   - `receiverTypeFqn`     : type statique du receveur, substitution générique
//                             incluse (`this.modele` → SaisieMessage01Modele) —
//                             c'est lui qui doit porter le mock.
//   - `resolvedReturnType`  : type de retour substitué au call-site
//                             (`getSectionPersonne()` → List<Section01Modele>
//                             au lieu du `List<T>` déclaré) — c'est lui que
//                             javac attend dans `thenReturn(...)`.
data class MethodCall(
    val targetType: String,
    val methodName: String,
    val argTypes: List<String>,
    val isStatic: Boolean = false,
    val receiverTypeFqn: String? = null,
    val resolvedReturnType: ResolvedType? = null
)

// Accès à un champ détecté dans un corps de méthode.
data class FieldAccess(
    val ownerType: String,
    val fieldName: String,
    val write: Boolean
)

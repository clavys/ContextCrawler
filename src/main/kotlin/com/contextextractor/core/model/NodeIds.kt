package com.contextextractor.core.model

import com.contextextractor.core.extractor.MethodSignature

// Convention de nommage des `ContextNode.id` — voir ARCHITECTURE.md §4
// (« clé unique stable, ex: method:com.X#foo »).
//
// **Pourquoi un objet centralisé ?** Les renderers, les tests
// (`tree.byId(...)`), et les actions IDE (highlighting) reposent sur ces
// chaînes. Toute divergence locale = lookup silencieux qui retourne null,
// donc bug invisible. Concentrer la convention ici garantit qu'un changement
// de format casse à la compilation, pas en silence.
//
// **Format général** : `{kind-prefix}:{stable-key}`. Le préfixe rend l'id
// auto-descriptif quand on lit un dump, et permet de filtrer par kind sans
// consulter la map `byKind`.
object NodeIds {

    // Racine de l'arbre — un nœud TARGET_METHOD wrap l'ensemble du contexte
    // pour la méthode analysée. La cible identifie de manière unique le résultat
    // d'une extraction (un test = une cible).
    fun target(classFqn: String, method: MethodSignature): String =
        "target:$classFqn#${method.canonical()}"

    // Hiérarchie — un seul nœud HIERARCHY par arbre, indexé par le SUT racine.
    fun hierarchy(sutFqn: String): String = "hierarchy:$sutFqn"

    // Champ utile — `field:{declaringClass}#{fieldName}`. La classe déclarante
    // distingue les overrides hérités vs les champs propres au SUT.
    fun field(declaringClass: String, fieldName: String): String =
        "field:$declaringClass#$fieldName"

    // Constructeur sélectionné — un seul par SUT (BLOC 4).
    fun ctor(sutFqn: String): String = "ctor:$sutFqn"

    // Setter trouvé en BLOC 5 — `setter:{methodName}`. Le name suffit car il
    // n'y a qu'un setter par champ (matching `setX`).
    fun setter(methodName: String): String = "setter:$methodName"

    // Méthode interne (mode INTERNAL_LOGIC) — `internal:{classFqn}#{canonical}`.
    // Format strictement identique à `VisitKey.methodCanonical` en INTERNAL_LOGIC.
    fun internalMethod(classFqn: String, canonical: String): String =
        "internal:$classFqn#$canonical"

    // Mock externe — un seul nœud par classe mockée (les multiples signatures
    // appelées sont aggrégées dans `MockInfo.requiredSignatures`).
    fun mock(classFqn: String): String = "mock:$classFqn"

    // DTO / DataStructure — un seul nœud par fqName.
    fun dto(classFqn: String): String = "dto:$classFqn"

    // Appel statique utilisateur — `static:{classFqn}#{methodName}`. Pas de
    // canonical car le sourced du dump §3.5 n'enregistre que classe+méthode.
    fun staticCall(classFqn: String, methodName: String): String =
        "static:$classFqn#$methodName"
}

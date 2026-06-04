package com.contextextractor.core.model.refs

import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter

// Manière dont une classe est référencée dans le graphe d'extraction — V1.2.
//
// Chaque variant décrit un SITE d'usage distinct : un endroit précis du code
// où la classe est mentionnée. Une même classe peut accumuler plusieurs
// `UsageSite` au fil du BFS (typiquement : `AsFieldOfSut` + `AsCallTarget`
// pour un service appelé via son champ injecté).
//
// **Invariant fondamental V1.2** : aucun `UsageSite` ne décide du Mode
// (MOCK/DATA_STRUCTURE/etc.). C'est le rôle exclusif du `ContextAwareClassifier`
// qui consomme la LISTE complète des `UsageSite` une fois le BFS terminé.
//
// Cette séparation élimine la cascade de patches V1.1 (Bug U, B, I, S, T)
// qui forçaient des promotions/évictions a posteriori parce que la décision
// était prise en single-pass eager.
sealed class UsageSite {

    // ── Catégorie 1 : usage statique côté SUT ────────────────────────────────

    // Le type est déclaré comme champ d'une classe de la hiérarchie SUT
    // (directement ou via héritage). Source d'information primaire :
    // `ClassField` contient annotations, visibility, type complet, etc.
    //
    // Plusieurs `AsFieldOfSut` possibles pour la même classe si elle apparaît
    // sur plusieurs niveaux de hiérarchie (rare mais valide).
    data class AsFieldOfSut(val field: ClassField) : UsageSite()

    // ── Catégorie 2 : usage côté signature de target ─────────────────────────

    // Paramètre de la méthode cible. Implique que le test devra construire
    // ou mocker une instance pour invoquer target.
    data class AsParamOfTarget(val parameter: Parameter) : UsageSite()

    // Type de retour de la méthode cible (ou type-arg du retour si
    // `asGenericArg = true`). Le test assertera sur cette valeur.
    data class AsReturnTypeOfTarget(val asGenericArg: Boolean = false) : UsageSite()

    // ── Catégorie 3 : usage dans le code (corps d'une méthode) ───────────────

    // Une méthode (target ou interne intra-SUT) appelle une méthode d'INSTANCE
    // sur ce type. C'est la condition prioritaire pour MOCK_EXTERNAL :
    // dès qu'on appelle une méthode dessus, il faut pouvoir la stubber.
    //
    // `resolvedMethod` peut être null si le port n'a pas pu résoudre la
    // signature (cas dégradé §8bis) — le classifier doit alors retomber sur
    // les infos de `call` (nom + argTypes bruts).
    //
    // `callerOwnerFqn` et `callerMethod` permettent au materializer de
    // reconstruire qui appelle qui — utile pour collecter les `requiredSignatures`
    // côté mock matérialisé.
    data class AsCallTarget(
        val call: MethodCall,
        val resolvedMethod: MethodSignature?,
        val callerOwnerFqn: String,
        val callerMethod: MethodSignature
    ) : UsageSite()

    // Une méthode (target ou interne) appelle une méthode STATIQUE sur ce type.
    // Distinct de `AsCallTarget` car les statics ne deviennent pas MOCK_EXTERNAL
    // mais STATIC_UTILITY (mockStatic Mockito).
    data class AsStaticCallTarget(
        val call: MethodCall,
        val callerMethod: MethodSignature
    ) : UsageSite()

    // `new Type(...)` détecté dans le corps de target ou d'une méthode interne.
    // Implique que ce type doit être instanciable (DATA_STRUCTURE) — on ne peut
    // pas le remplacer par un mock puisque le code de prod construit l'instance.
    data class AsInstantiationInBody(val callerMethod: MethodSignature) : UsageSite()

    // ── Catégorie 4 : usage indirect via une autre référence ─────────────────

    // Ce type est le type de retour d'une méthode qu'on prévoit de stubber sur
    // un mock conservé. Le LLM devra construire la valeur retournée
    // (`when(mock.method()).thenReturn(...)`).
    //
    // Bug DD V1.1 rendait ce cas problématique : un type évincé du budget
    // mais référencé ici causait des `new Type()` qui ne compilaient pas.
    // V1.2 : ce site garantit que le type sera surfacé dans le résultat.
    data class AsStubReturn(
        val mockOwnerFqn: String,
        val mockMethod: MethodSignature
    ) : UsageSite()

    // Ce type est un champ d'un autre type DATA_STRUCTURE déjà référencé.
    // Récursion DTO §3.4 Phase 4 : un Order contient un Customer →
    // Customer ajoute `AsFieldOfReferencedClass("Order", "customer")`.
    data class AsFieldOfReferencedClass(
        val parentClassFqn: String,
        val fieldName: String
    ) : UsageSite()

    // Ce type apparaît comme type-arg dans un autre type référencé
    // (`List<Foo>` → Foo a `AsTypeArgOfReference("java.util.List")`).
    // Utile au classifier pour distinguer "présent par dépendance générique"
    // vs "présent par usage direct".
    data class AsTypeArgOfReference(val parentFqn: String) : UsageSite()
}

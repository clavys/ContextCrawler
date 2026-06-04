package com.contextextractor.core.model.refs

import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.MethodSignature

// Référence agrégée à une classe — V1.2 modèle pivot du 2-pass.
//
// **Sémantique** : « cette classe a été RENCONTRÉE par le crawl sous une ou
// plusieurs formes (champ, paramètre, appel, instanciation...) ». Pas encore
// de décision Mode (MOCK/DTO/etc.) — c'est le `ContextAwareClassifier` qui
// décide en lisant `usages`.
//
// **Identité** : une `ClassReference` par FQN. Si la même classe est rencontrée
// dans 5 contextes, les 5 `UsageSite` sont accumulés dans la même instance.
// Le builder est responsable d'agréger (pas le classifier).
//
// **Helpers booléens** : tous dérivés de `usages` — pas de cache, pas d'état
// mutable. Le classifier les consulte au lieu d'introspecter `usages` à la
// main, ce qui rend les règles plus lisibles et plus testables.
//
// **Pourquoi `descriptor` est nullable** : §8bis.1 cas dégradé — un type
// référencé peut ne pas être résolvable (import cassé, type généré non
// présent dans le classpath). On laisse passer dans le graphe avec
// `descriptor = null` ; le classifier décidera (typiquement DATA_STRUCTURE
// par défaut + entrée stub côté materializer).
data class ClassReference(
    val fqn: String,
    val descriptor: ClassDescriptor?,
    val usages: List<UsageSite>
) {

    // ── Helpers de présence d'usage ──────────────────────────────────────────

    // Au moins un appel d'instance sur ce type (target ou méthode interne).
    // C'est le déclencheur prioritaire de MOCK_EXTERNAL : si on appelle dessus,
    // on doit pouvoir le stubber.
    val isCalledAsInstance: Boolean
        get() = usages.any { it is UsageSite.AsCallTarget }

    // Au moins un appel STATIQUE sur ce type. Implique STATIC_UTILITY plutôt
    // que MOCK_EXTERNAL — le test utilisera `mockStatic(Type.class)`.
    val isCalledAsStatic: Boolean
        get() = usages.any { it is UsageSite.AsStaticCallTarget }

    // Apparaît comme champ déclaré sur la SUT (un ou plusieurs niveaux).
    val isSutField: Boolean
        get() = usages.any { it is UsageSite.AsFieldOfSut }

    // Apparaît dans la signature de target (paramètre OU retour OU type-arg
    // de retour). Implique que le type est sur la surface du test.
    val isOnTargetSurface: Boolean
        get() = usages.any {
            it is UsageSite.AsParamOfTarget ||
                it is UsageSite.AsReturnTypeOfTarget
        }

    // `new Type(...)` détecté quelque part dans le code visité. Le type doit
    // donc rester instanciable côté prod — pas de mock substituable.
    val isInstantiatedInBody: Boolean
        get() = usages.any { it is UsageSite.AsInstantiationInBody }

    // Apparaît comme returnType d'une méthode qu'on stubbera sur un mock.
    // Critère structurel pour qu'un type soit présent dans le prompt même
    // s'il n'est pas mocké lui-même (ex: contenu d'une List retournée).
    val isStubReturnType: Boolean
        get() = usages.any { it is UsageSite.AsStubReturn }

    // ── Helpers d'extraction ──────────────────────────────────────────────────

    // Tous les `AsFieldOfSut` (peut y en avoir plusieurs si le type apparaît
    // sur plusieurs niveaux de hiérarchie — rare mais valide).
    val asSutFields: List<ClassField>
        get() = usages.filterIsInstance<UsageSite.AsFieldOfSut>().map { it.field }

    // Toutes les méthodes d'instance appelées sur ce type, résolues si possible.
    // Utilisé par le materializer pour peupler `MockInfo.requiredSignatures`.
    val instanceCallSites: List<UsageSite.AsCallTarget>
        get() = usages.filterIsInstance<UsageSite.AsCallTarget>()

    // Signatures uniques effectivement résolues (drop les nulls). Utile au
    // materializer pour assembler la liste de méthodes à stubber sur le mock.
    val resolvedCalledSignatures: List<MethodSignature>
        get() = instanceCallSites
            .mapNotNull { it.resolvedMethod }
            .distinctBy { it.canonical() }

    // Position dans la signature de target — utile au classifier pour
    // distinguer "essentiel" (vrai input/output) vs "dépendance dérivée".
    val targetSurfaceUsages: List<UsageSite>
        get() = usages.filter {
            it is UsageSite.AsParamOfTarget || it is UsageSite.AsReturnTypeOfTarget
        }
}

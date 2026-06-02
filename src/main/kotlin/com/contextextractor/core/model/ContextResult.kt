package com.contextextractor.core.model

import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.extractor.ResolvedType
import com.contextextractor.core.model.init.InitSource
import com.contextextractor.core.model.init.InitStrategy

// Modèle de résultat brut produit par RecursiveDeepStrategy avant mapping vers
// ContextTree (sous-étape 4f). Traduction anglaise de `ContexteResultat` —
// STRATEGIE.md §5 + table FR→EN d'ARCHITECTURE.md §3bis.
//
// Volontairement immuable : la stratégie travaille via ContextResultBuilder
// pendant la récursion, puis appelle build() à la fin.
data class ContextResult(
    val sutFqName: String,
    val hierarchy: List<HierarchyLevel>,
    val fields: List<ClassField>,
    val instantiationPlan: InstantiationPlan,
    val targetMethod: TargetMethodAnalysis,
    val internalLogics: Map<String, InternalLogic> = emptyMap(),
    val mocks: Map<String, MockInfo> = emptyMap(),
    val dataStructures: Map<String, DataStructureInfo> = emptyMap(),
    val staticCalls: List<StaticCallInfo> = emptyList(),
    val initProtocol: Map<String, FieldInitProtocol> = emptyMap(),
    val initOrder: List<String> = emptyList(),
    val testabilityDiagnostic: TestabilityDiagnostic = TestabilityDiagnostic.UNKNOWN,
    val intraSutCallGraph: Map<String, List<String>> = emptyMap(),
    val truncated: Boolean = false,
    val truncationReasons: List<String> = emptyList()
)

// ── BLOC 1 : hiérarchie ──────────────────────────────────────────────────────

// Un niveau de la hiérarchie du SUT — le SUT lui-même apparaît en première
// position, suivi de ses super-classes utilisateur (Object exclu).
// `superConstructorParams` est non-null si la classe au-dessus a un constructeur
// non-vide compatible avec un appel super(...) ; null sinon.
data class HierarchyLevel(
    val classFqn: String,
    val annotations: List<String>,
    val superConstructorParams: List<Parameter>? = null
)

// ── BLOC 2 : analyse de la méthode cible ─────────────────────────────────────

data class InstanceCall(
    val targetType: String,
    val targetName: String,
    val methodName: String,
    val argTypes: List<String>
)

data class StaticCall(
    val classFqn: String,
    val methodName: String,
    val argTypes: List<String>
)

data class ThrownException(val typeFqn: String, val message: String? = null)

data class CaughtException(
    val types: List<String>,
    val callsInCatch: List<String> = emptyList()
)

data class ConditionalBranch(
    val kind: String,
    val condition: String,
    val constants: List<String> = emptyList()
)

data class TargetMethodAnalysis(
    val signature: MethodSignature,
    val instanceCalls: List<InstanceCall> = emptyList(),
    val staticCalls: List<StaticCall> = emptyList(),
    val instantiations: List<ResolvedType> = emptyList(),
    val expectedLambdas: List<String> = emptyList(),
    val thrownExceptions: List<ThrownException> = emptyList(),
    val caughtExceptions: List<CaughtException> = emptyList(),
    val conditionalBranches: List<ConditionalBranch> = emptyList(),
    val nonDeterministicSources: List<String> = emptyList(),
    // Corps source de la méthode (incluant accolades extérieures) — STRATEGIE.md
    // §3.1 ligne 192 « Corps = AST(methodeCible) ». Le LLM en a besoin pour
    // reproduire fidèlement la logique métier ; les éléments structurés ci-dessus
    // restent capturés en parallèle (BLOC 2). Vide si l'introspector n'a pas pu
    // lire le source (méthode abstraite, cas dégradé §8bis).
    val body: String = ""
)

// ── BLOCs 4-5 : protocole d'instanciation du SUT ─────────────────────────────

data class SelectedConstructor(
    val parameters: List<Parameter>,
    val triggerAnnotation: String? = null,
    val superArgs: List<String> = emptyList()
)

data class Setter(val methodName: String, val fieldName: String, val paramType: ResolvedType)

data class InstantiationPlan(
    val selectedConstructor: SelectedConstructor,
    val setters: List<Setter> = emptyList(),
    val postConstruct: List<String> = emptyList()
)

// ── Sous-types des modes secondaires (sous-étape 4c) ─────────────────────────

// LogiqueInterne — STRATEGIE.md §3.2 / §5. callSummaries résume les appels
// observés dans le corps (cible.methode + clue de classification) sans recopier
// l'AST entier — c'est ce qui distingue InternalLogic d'un dump de méthode.
data class InternalLogic(
    val signature: MethodSignature,
    val callSummaries: List<String> = emptyList(),
    val thrownExceptions: List<ThrownException> = emptyList(),
    val caughtExceptions: List<CaughtException> = emptyList(),
    // Corps source de la sous-méthode (incluant accolades) — STRATEGIE.md §3.2
    // ligne 362 « Corps = AST(Methode) ». Frontière intra-SUT identique à la
    // target : on lit le corps pour le rendre au LLM. Vide pour les internes
    // synthétisés post-BLOC 7 dont le port n'aurait pas exposé le source.
    val body: String = "",
    // Défaut #1 — §3.2bis. Marqueur d'une méthode héritée framework qui descend
    // dans javax.faces / javax.servlet / java.io etc. À stubber par
    // `spy(sut) + doAnswer(...)` plutôt qu'à exécuter. Quand vrai, le renderer
    // bascule sur la section « # Méthodes à stubber par spy » et le body /
    // callSummaries ne sont pas rendus (frontière de test fermée).
    val stubViaSpy: Boolean = false,
    val frameworkPrefixesHit: List<String> = emptyList()
)

// MockInfo — STRATEGIE.md §3.3 / §5. `requiredSignatures` accumule les
// signatures appelées sur ce mock (multi-appels ⇒ multi-signatures). Chaque
// nouvelle visite MOCK_EXTERNAL ajoute la signature courante si absente.
data class MockInfo(
    val concreteClass: String,
    val declaredType: String,
    val classAnnotations: List<String> = emptyList(),
    val requiredSignatures: List<MethodSignature> = emptyList(),
    val returnIsNestedMock: Boolean = false
)

// PatternConstruction — STRATEGIE.md §3.4 / §5. L'ordre énuméré n'a aucune
// signification ; la priorité de détection est codée dans detectPattern().
enum class ConstructionPattern { RECORD, BUILDER, CONSTRUCTOR, SETTER_BASED, ENUM, SEALED, STATIC_FACTORY }

// Champ d'un DataStructure — noms français de §5 (ChampData) traduits par
// ARCHITECTURE.md §3bis. validationAnnotations restreint à la liste JSR-380
// utile au générateur de prompt (§3.4 Phase 3).
data class DataField(
    val name: String,
    val type: ResolvedType,
    val validationAnnotations: List<String> = emptyList(),
    val defaultValue: String? = null,
    val required: Boolean = false
)

// Méthode du builder — capturée en Phase 2 pour les patterns BUILDER.
// `field` reproduit le nom du champ ciblé (convention Lombok : nom de méthode).
data class BuilderMethodInfo(
    val name: String,
    val field: String,
    val type: ResolvedType,
    val required: Boolean = false
)

data class BuilderInfo(
    val builderClass: String,
    val methods: List<BuilderMethodInfo> = emptyList()
)

// Méthode statique de fabrique — pour STATIC_FACTORY (of, from, valueOf, …).
data class FactoryMethodInfo(
    val name: String,
    val parameters: List<Parameter>,
    val returnType: ResolvedType
)

// DataStructureInfo — version complète (sous-étape 4d). Conserve fqName comme
// clé d'identité ; pattern et listes spécifiques sont peuplés selon la phase
// de §3.4. Pour ENUM/SEALED, fields reste vide ; les listes spécifiques
// (enumValues, sealedSubs) prennent le relais.
data class DataStructureInfo(
    val fqName: String,
    val pattern: ConstructionPattern,
    val fields: List<DataField> = emptyList(),
    val builderInfo: BuilderInfo? = null,
    val factoryMethods: List<FactoryMethodInfo> = emptyList(),
    val enumValues: List<String> = emptyList(),
    val sealedSubs: List<String> = emptyList()
)

data class StaticCallInfo(
    val classFqn: String,
    val methodName: String,
    val signature: MethodSignature
)

// `ProtocoleInitChamp` (§5) — pour CHAQUE champ utile de la SUT, on stocke
// l'ensemble des sources d'initialisation détectées et la stratégie élue par
// l'arbre de décision §4.5. `recommendedStrategy` est la sortie principale de
// BLOC 7 — c'est elle qui sera lue par le rendu CONTEXT layer (§6).
//
// `InitStrategy` et `InitSource` vivent dans `core/model/init/` (cf 4f-α) —
// ils appartiennent au modèle de données, pas à l'algorithme. Toute stratégie
// future (Shallow, GitDiff, …) qui produit un protocole d'init les réutilisera.
data class FieldInitProtocol(
    val field: ClassField,
    val sources: List<InitSource> = emptyList(),
    val recommendedStrategy: InitStrategy
)

data class TestabilityDiagnostic(
    val testable: Boolean,
    val blockingFields: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
    val refactorHints: List<String> = emptyList()
) {
    companion object {
        // Verdict par défaut tant que BLOC 7 n'a pas tourné — RecursiveDeepStrategy
        // remplace ce sentinelle par un vrai diagnostic une fois BLOC 7 exécuté.
        val UNKNOWN = TestabilityDiagnostic(testable = true)
    }
}

package com.contextextractor.strategies.recursive.refs

import com.contextextractor.core.classifier.ExtractionMode
import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.model.BuilderInfo
import com.contextextractor.core.model.BuilderMethodInfo
import com.contextextractor.core.model.ConstructionPattern
import com.contextextractor.core.model.DataField
import com.contextextractor.core.model.DataStructureInfo
import com.contextextractor.core.model.FactoryMethodInfo
import com.contextextractor.core.model.InternalLogic
import com.contextextractor.core.model.MockInfo
import com.contextextractor.core.model.StaticCallInfo
import com.contextextractor.core.model.ThrownException
import com.contextextractor.core.model.CaughtException
import com.contextextractor.core.model.refs.ClassReference
import com.contextextractor.core.model.refs.ReferenceGraph
import com.contextextractor.core.model.refs.UsageSite

// PASSE 3 du pipeline V1.2 — matérialise `ContextResult` partiel depuis
// `ReferenceGraph` + classifications.
//
// **Contrat** : pure transformation. Aucune décision d'algorithme — toutes
// les décisions ont été prises en PASSE 1 (builder) et PASSE 2 (classifier).
// Cette classe se contente de :
//   1. Enrichir chaque référence via le port (lecture des champs, méthodes,
//      corps de méthode) selon son Mode final.
//   2. Matérialiser les structures `MockInfo`, `DataStructureInfo`,
//      `InternalLogic`, `StaticCallInfo` consommées par le rendu downstream.
//
// **Pourquoi pas dans le builder** : séparation des responsabilités. Le
// builder ne sait pas encore quoi faire d'une référence (MOCK ? DTO ?). Le
// materializer le sait grâce aux classifications, et exécute la collecte
// de données ciblée. Cette séparation rend chaque phase testable isolément.
class ResultMaterializer(
    private val introspector: CodeIntrospector,
    private val graph: ReferenceGraph,
    private val classifications: Map<String, ExtractionMode>,
    private val hierarchyFqns: Set<String>
) {

    // ── Matérialisation des mocks ────────────────────────────────────────────

    // Pour chaque classe classifiée MOCK_EXTERNAL, construit un `MockInfo`
    // avec les signatures appelées agrégées depuis les `AsCallTarget`
    // UsageSites. `requiredSignatures` est la dédup par canonical des
    // signatures résolues.
    fun materializeMocks(): Map<String, MockInfo> {
        val out = LinkedHashMap<String, MockInfo>()
        graph.allReferences().forEach { ref ->
            if (classifications[ref.fqn] != ExtractionMode.MOCK_EXTERNAL) return@forEach
            // V1.4.5 Bug QQ — le retour résolu au call-site (substitution
            // générique) remplace le retour déclaré dans les signatures à
            // stubber : c'est lui que javac attend dans `thenReturn(...)`
            // (Astrea 4.4 : `getSectionPersonne()` déclaré `List<T>` mais vu
            // `List<Section01Modele>` à travers SaisieMessage01Modele).
            val signatures = ref.instanceCallSites
                .mapNotNull { site ->
                    val sig = site.resolvedMethod ?: return@mapNotNull null
                    site.call.resolvedReturnType?.let { sig.copy(returnType = it) } ?: sig
                }
                .distinctBy { it.canonical() }
            out[ref.fqn] = MockInfo(
                concreteClass = ref.fqn,
                declaredType = ref.fqn,
                classAnnotations = ref.descriptor?.annotations.orEmpty(),
                requiredSignatures = signatures,
                returnIsNestedMock = signatures.any { sig ->
                    classifications[sig.returnType.fqName] == ExtractionMode.MOCK_EXTERNAL
                }
            )
        }
        return out
    }

    // ── Matérialisation des DataStructure ────────────────────────────────────

    // Pour chaque classe classifiée DATA_STRUCTURE, exécute la détection de
    // pattern + capture Phase 2 + Phase 3 fields de §3.4 STRATEGIE.
    //
    // **Note pour Phase 3.2** : cette implémentation duplique temporairement
    // les helpers `detectPattern` / `capturePhase2*` de V1.1 `RecursiveDeepStrategy`.
    // La duplication sera supprimée lors de la bascule extractCore (les
    // helpers V1.1 deviendront inaccessibles puis seront retirés).
    fun materializeDataStructures(): Map<String, DataStructureInfo> {
        val out = LinkedHashMap<String, DataStructureInfo>()
        graph.allReferences().forEach { ref ->
            if (classifications[ref.fqn] != ExtractionMode.DATA_STRUCTURE) return@forEach
            // V1.4.1 Bug #E — une variable de type générique (E, T, K, V…) fuit
            // des signatures JDK (`List.remove(int):E`) comme nom nu sans package.
            // Ce n'est pas un type constructible : la rendre `## E [SETTER_BASED]`
            // est du bruit pur pour le LLM (vu en prod Astrea case 4.2). Un FQN
            // sans '.' ne peut pas être une vraie classe d'entreprise — on drop.
            if (!ref.fqn.contains('.')) return@forEach
            val descriptor = ref.descriptor
            if (descriptor == null) {
                // §8bis.1 — type non résolvable : entrée stub SETTER_BASED
                // pour signaler la rencontre.
                out[ref.fqn] = DataStructureInfo(
                    fqName = ref.fqn,
                    pattern = ConstructionPattern.SETTER_BASED
                )
                return@forEach
            }
            val methods = introspector.listMethods(descriptor)
            val pattern = detectPattern(descriptor, methods)
            val phase2 = capturePhase2(descriptor, methods, pattern)
            val phase3 = if (pattern in PHASE3_SKIP) emptyList()
            else capturePhase3Fields(descriptor)
            out[ref.fqn] = DataStructureInfo(
                fqName = ref.fqn,
                pattern = pattern,
                fields = phase2.fields + phase3,
                builderInfo = phase2.builderInfo,
                factoryMethods = phase2.factoryMethods,
                enumValues = phase2.enumValues,
                sealedSubs = phase2.sealedSubs,
                constructors = phase2.constructors
            )
        }
        return out
    }

    // ── Matérialisation des InternalLogic ────────────────────────────────────

    // Pour chaque méthode intra-SUT visitée, capture body + analyse +
    // callSummaries. Les frontières framework sont matérialisées avec
    // `stubViaSpy = true` et leur body n'est pas lu (§3.2bis).
    fun materializeInternalLogics(): Map<String, InternalLogic> {
        val out = LinkedHashMap<String, InternalLogic>()
        graph.visitedInternalMethods.forEach { visited ->
            val key = "${visited.ownerFqn}#${visited.signature.canonical()}"
            // Dédup — si la même méthode apparaît plusieurs fois (cas rare
            // d'overload résolu différemment), première occurrence gagne.
            if (key in out) return@forEach
            if (visited.isFrameworkBoundary) {
                out[key] = InternalLogic(
                    signature = visited.signature,
                    stubViaSpy = true,
                    frameworkPrefixesHit = visited.frameworkPrefixesHit
                )
                return@forEach
            }
            val body = introspector.readMethodBody(visited.signature)
            val bodyAnalysis = introspector.analyzeMethodBody(visited.signature)
            val callSummaries = introspector.listMethodCalls(visited.signature)
                .filter { !it.isStatic }
                .map { c -> "${c.targetType}.${c.methodName}" }
                .distinct()
            out[key] = InternalLogic(
                signature = visited.signature,
                callSummaries = callSummaries,
                thrownExceptions = bodyAnalysis.thrownExceptions.map {
                    ThrownException(it.typeFqn, it.message)
                },
                caughtExceptions = bodyAnalysis.caughtExceptions.map {
                    CaughtException(it.types, it.callsInCatch)
                },
                body = body
            )
        }
        return out
    }

    // ── Matérialisation des StaticCall ───────────────────────────────────────

    fun materializeStaticCalls(): List<StaticCallInfo> {
        val out = mutableListOf<StaticCallInfo>()
        graph.allReferences().forEach { ref ->
            if (classifications[ref.fqn] != ExtractionMode.STATIC_UTILITY) return@forEach
            ref.usages.filterIsInstance<UsageSite.AsStaticCallTarget>().forEach { site ->
                val resolved = findStaticMethod(ref, site.call.methodName, site.call.argTypes)
                    ?: return@forEach
                out += StaticCallInfo(
                    classFqn = ref.fqn,
                    methodName = site.call.methodName,
                    signature = resolved
                )
            }
        }
        return out.distinctBy { "${it.classFqn}#${it.signature.canonical()}" }
    }

    // ── Helpers : detection de pattern DTO (dupliqué de V1.1 §3.4) ───────────

    private fun detectPattern(
        descriptor: ClassDescriptor,
        methods: List<MethodSignature>
    ): ConstructionPattern {
        if (descriptor.isRecord) return ConstructionPattern.RECORD
        if (LOMBOK_BUILDER_FQN in descriptor.annotations) return ConstructionPattern.BUILDER
        if (LOMBOK_VALUE_FQN in descriptor.annotations) return ConstructionPattern.CONSTRUCTOR
        if (descriptor.isEnum) return ConstructionPattern.ENUM
        if (descriptor.isSealed) return ConstructionPattern.SEALED
        if (methods.any { it.isStatic && it.name == "builder" && it.parameters.isEmpty() }) {
            return ConstructionPattern.BUILDER
        }
        if (methods.any { it.isStatic && it.name in FACTORY_METHOD_NAMES }) {
            return ConstructionPattern.STATIC_FACTORY
        }
        if (methods.any { it.name == "<init>" && JSON_CREATOR_FQN in it.annotations }) {
            return ConstructionPattern.CONSTRUCTOR
        }
        if (LOMBOK_DATA_FQN in descriptor.annotations ||
            LOMBOK_ALL_ARGS_FQN in descriptor.annotations
        ) {
            return ConstructionPattern.CONSTRUCTOR
        }
        if (methods.any { it.name == "<init>" && it.visibility == "public" && it.parameters.isNotEmpty() }) {
            return ConstructionPattern.CONSTRUCTOR
        }
        return ConstructionPattern.SETTER_BASED
    }

    private data class Phase2Result(
        val fields: List<DataField> = emptyList(),
        val builderInfo: BuilderInfo? = null,
        val factoryMethods: List<FactoryMethodInfo> = emptyList(),
        val enumValues: List<String> = emptyList(),
        val sealedSubs: List<String> = emptyList(),
        val constructors: List<MethodSignature> = emptyList()
    )

    private fun capturePhase2(
        descriptor: ClassDescriptor,
        methods: List<MethodSignature>,
        pattern: ConstructionPattern
    ): Phase2Result = when (pattern) {
        ConstructionPattern.RECORD -> {
            val canonical = methods.filter { it.name == "<init>" }.maxByOrNull { it.parameters.size }
            val fields = canonical?.parameters?.map { p ->
                DataField(name = p.name, type = p.type, required = !p.type.nullable)
            }.orEmpty()
            Phase2Result(fields = fields)
        }
        ConstructionPattern.BUILDER -> {
            Phase2Result(builderInfo = collectBuilderInfo(descriptor, methods))
        }
        ConstructionPattern.CONSTRUCTOR -> {
            // V1.4.4 Bug LL — §3.4 Phase 2 « capturer paramètres » enfin
            // réalisé : sans la signature exacte, le LLM construit le DTO en
            // inventant un ctor « tous-les-champs » depuis la liste Fields
            // (Astrea 4.4 : `new MemoireSaisieSegment(...)` à 15 args
            // inexistant). On capture TOUS les ctors publics, du plus complet
            // au plus court — le LLM choisit.
            val ctors = methods
                .filter { it.name == "<init>" && it.visibility == "public" }
                .sortedByDescending { it.parameters.size }
            Phase2Result(constructors = ctors)
        }
        ConstructionPattern.SETTER_BASED -> Phase2Result()
        ConstructionPattern.ENUM -> Phase2Result(enumValues = descriptor.enumValues)
        ConstructionPattern.SEALED -> Phase2Result(sealedSubs = descriptor.permittedSubclasses)
        ConstructionPattern.STATIC_FACTORY -> {
            val factories = methods
                .filter { it.isStatic && it.name in FACTORY_METHOD_NAMES }
                .map { FactoryMethodInfo(name = it.name, parameters = it.parameters, returnType = it.returnType) }
            Phase2Result(factoryMethods = factories)
        }
    }

    private fun collectBuilderInfo(
        descriptor: ClassDescriptor,
        methods: List<MethodSignature>
    ): BuilderInfo {
        val builderStatic = methods.firstOrNull {
            it.isStatic && it.name == "builder" && it.parameters.isEmpty()
        }
        if (builderStatic != null) {
            val builderClassFqn = builderStatic.returnType.fqName
            val builderClass = introspector.resolveClass(builderClassFqn)
            if (builderClass != null) {
                val builderMethods = introspector.listMethods(builderClass)
                val setters = builderMethods
                    .filter { it.parameters.size == 1 && it.name != "build" && it.visibility == "public" }
                    .map { m ->
                        BuilderMethodInfo(name = m.name, field = m.name,
                            type = m.parameters.single().type, required = false)
                    }
                return BuilderInfo(builderClass = builderClassFqn, methods = setters)
            }
        }
        val classFields = introspector.listFields(descriptor)
        val synth = classFields.map { f ->
            BuilderMethodInfo(name = f.name, field = f.name, type = f.type, required = false)
        }
        return BuilderInfo(builderClass = "${descriptor.fqn}.${descriptor.simpleName}Builder", methods = synth)
    }

    private fun capturePhase3Fields(descriptor: ClassDescriptor): List<DataField> {
        val sources = mutableListOf<DataField>()
        introspector.listFields(descriptor).forEach { cf ->
            sources += DataField(
                name = cf.name,
                type = cf.type,
                validationAnnotations = cf.annotations.filter { it in VALIDATION_ANNOTATIONS },
                required = cf.annotations.any { it in REQUIRED_VALIDATION }
            )
        }
        introspector.listSuperClasses(descriptor).forEach { sup ->
            introspector.listFields(sup).forEach { cf ->
                sources += DataField(
                    name = cf.name,
                    type = cf.type,
                    validationAnnotations = cf.annotations.filter { it in VALIDATION_ANNOTATIONS },
                    required = cf.annotations.any { it in REQUIRED_VALIDATION }
                )
            }
        }
        return sources
    }

    // ── Helper : résolution d'une méthode statique sur le descripteur ────────

    private fun findStaticMethod(
        ref: ClassReference, name: String, argTypes: List<String>
    ): MethodSignature? {
        val cls = ref.descriptor ?: return null
        val candidates = introspector.listMethods(cls)
            .filter { it.isStatic && it.name == name }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { sig ->
            sig.parameters.map { it.type.fqName } == argTypes
        } ?: candidates.first()
    }

    companion object {
        // Patterns sans Phase 3 — leurs « champs » sont déjà capturés en Phase 2.
        private val PHASE3_SKIP = setOf(
            ConstructionPattern.RECORD,
            ConstructionPattern.ENUM,
            ConstructionPattern.SEALED
        )

        // Annotations / constantes — identiques à V1.1 (cohérence comportementale).
        private const val LOMBOK_BUILDER_FQN = "lombok.Builder"
        private const val LOMBOK_VALUE_FQN = "lombok.Value"
        private const val LOMBOK_DATA_FQN = "lombok.Data"
        private const val LOMBOK_ALL_ARGS_FQN = "lombok.AllArgsConstructor"
        private const val JSON_CREATOR_FQN = "com.fasterxml.jackson.annotation.JsonCreator"

        private val FACTORY_METHOD_NAMES = setOf("of", "from", "valueOf")

        private val VALIDATION_ANNOTATIONS = setOf(
            "jakarta.validation.constraints.NotNull",
            "jakarta.validation.constraints.NotBlank",
            "jakarta.validation.constraints.Size",
            "jakarta.validation.constraints.Min",
            "jakarta.validation.constraints.Max",
            "jakarta.validation.constraints.Pattern",
            "jakarta.validation.constraints.Email",
            "javax.validation.constraints.NotNull",
            "javax.validation.constraints.NotBlank",
            "javax.validation.constraints.Size",
            "javax.validation.constraints.Min",
            "javax.validation.constraints.Max",
            "javax.validation.constraints.Pattern",
            "javax.validation.constraints.Email"
        )

        private val REQUIRED_VALIDATION = setOf(
            "jakarta.validation.constraints.NotNull",
            "jakarta.validation.constraints.NotBlank",
            "javax.validation.constraints.NotNull",
            "javax.validation.constraints.NotBlank"
        )
    }
}

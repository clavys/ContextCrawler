package com.contextextractor.strategies.recursive.refs

import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.extractor.ResolvedType
import com.contextextractor.core.model.HierarchyLevel
import com.contextextractor.core.model.SelectedConstructor
import com.contextextractor.core.model.refs.ClassReference
import com.contextextractor.core.model.refs.ReferenceGraph
import com.contextextractor.core.model.refs.UsageSite

// Builder de la PASSE 1 — V1.2.
//
// Énumère TOUTES les références à des classes rencontrées depuis (SUT, target)
// sans rien classifier ni décider. Retourne un `ReferenceGraph` immuable
// consommé ensuite par le `ContextAwareClassifier` (PASSE 2).
//
// **Périmètre** :
//   - Champs de la SUT et héritage (hierarchy)        → AsFieldOfSut
//   - Paramètres du constructeur sélectionné          → AsParamOfTarget? (non — c'est ctor SUT)
//   - Paramètres de la méthode target                 → AsParamOfTarget
//   - Return type de target + type-args               → AsReturnTypeOfTarget
//   - Appels d'instance dans body target/internes     → AsCallTarget
//   - Appels statiques utilisateur                    → AsStaticCallTarget
//   - `new X()` dans body target/internes             → AsInstantiationInBody
//   - Méthode interne intra-SUT visitée               → enqueue body pour exploration récursive
//   - Type retour d'une signature appelée sur un mock → AsStubReturn
//   - Champ d'un DTO référencé                        → AsFieldOfReferencedClass
//   - Type-arg d'une référence                        → AsTypeArgOfReference
//
// **Hors périmètre** (différé) :
//   - Décision Mode (MOCK/DTO/etc.) — PASSE 2
//   - Construction MockInfo/DataStructureInfo/InternalLogic — PASSE 3
//   - BLOC 7 init protocol — inchangé V1.1
//   - BLOC 1/4/5 (hierarchy, ctor, setters) — inchangé V1.1
//
// **Invariant** : un seul `ClassReference` par FQN. Si la même classe apparaît
// sous 3 angles différents, 3 `UsageSite` sont accumulés.
//
// **Garde-fous PSI préservés** :
//   - Frontière framework respectée (detectFrameworkBoundary)
//   - Trivial getter court-circuité (isTrivialGetter)
//   - maxCrawlDepth limite la profondeur d'exploration
class ReferenceGraphBuilder(
    private val introspector: CodeIntrospector,
    private val frameworkPrefixes: List<String> = emptyList(),
    private val maxCrawlDepth: Int = 8
) {

    fun build(
        sut: ClassDescriptor,
        targetMethod: MethodSignature,
        hierarchy: List<HierarchyLevel>,
        selectedConstructor: SelectedConstructor
    ): ReferenceGraph {
        val ctx = BuildContext(introspector, hierarchy.map { it.classFqn }.toSet())

        // SEED 1 — champs de la SUT et héritage. On enregistre AsFieldOfSut pour
        // chaque type de champ. Les champs sans accès depuis target/init seront
        // filtrés en PASSE 2 (essential vs non), pas ici.
        seedSutFields(ctx, hierarchy)

        // SEED 2 — paramètres + return type de target. Surface du test.
        seedTargetSignature(ctx, targetMethod)

        // SEED 3 — paramètres du constructeur SUT (peuvent être des types non
        // présents en champs : injection par constructeur sans @Autowired).
        seedConstructorParams(ctx, selectedConstructor)

        // BFS 1 — explorer le corps de target d'abord. Chaque méthode visitée
        // peut enqueue d'autres méthodes intra-SUT à visiter récursivement
        // (cf §3.2 INTERNAL_LOGIC).
        ctx.methodQueue.add(MethodToVisit(targetMethod, sut.fqn, depth = 0))
        while (ctx.methodQueue.isNotEmpty()) {
            val item = ctx.methodQueue.removeFirst()
            if (!ctx.visitedMethods.add("${item.ownerFqn}#${item.method.canonical()}")) continue
            if (item.depth > maxCrawlDepth) {
                ctx.truncationReasons += "maxCrawlDepth at ${item.ownerFqn}#${item.method.name}"
                continue
            }
            visitMethodBody(ctx, item.method, item.ownerFqn, item.depth)
        }

        // BFS 2 — pour chaque classe référencée comme appelée d'instance,
        // enregistrer ses signatures appelées comme `AsStubReturn` cibles
        // (le returnType de chaque méthode stubée doit être présent).
        propagateStubReturns(ctx)

        // BFS 3 — pour chaque classe référencée data-shaped (sera vraisemblablement
        // classifiée DATA_STRUCTURE), descendre dans ses champs. Cette propagation
        // est PURE référence — on ne décide pas du pattern (RECORD/BUILDER/etc.)
        // ici ; le materializer le fera en PASSE 3.
        propagateDataStructureFields(ctx)

        return ReferenceGraph(
            byFqn = ctx.toMap(),
            truncationReasons = ctx.truncationReasons.toList()
        )
    }

    // ── Phases de seed ───────────────────────────────────────────────────────

    private fun seedSutFields(ctx: BuildContext, hierarchy: List<HierarchyLevel>) {
        hierarchy.forEach { level ->
            val cls = introspector.resolveClass(level.classFqn) ?: return@forEach
            introspector.listFields(cls).forEach { field ->
                ctx.register(field.type.fqName, UsageSite.AsFieldOfSut(field))
                // Type-args du type du champ → ajoutés comme `AsTypeArgOfReference`
                field.type.typeArgs.forEach { arg ->
                    ctx.register(arg.fqName, UsageSite.AsTypeArgOfReference(field.type.fqName))
                }
            }
        }
    }

    private fun seedTargetSignature(ctx: BuildContext, targetMethod: MethodSignature) {
        targetMethod.parameters.forEach { p ->
            ctx.register(p.type.fqName, UsageSite.AsParamOfTarget(p))
            p.type.typeArgs.forEach { arg ->
                ctx.register(arg.fqName, UsageSite.AsTypeArgOfReference(p.type.fqName))
            }
        }
        ctx.register(targetMethod.returnType.fqName, UsageSite.AsReturnTypeOfTarget(asGenericArg = false))
        targetMethod.returnType.typeArgs.forEach { arg ->
            ctx.register(arg.fqName, UsageSite.AsReturnTypeOfTarget(asGenericArg = true))
        }
    }

    private fun seedConstructorParams(ctx: BuildContext, ctor: SelectedConstructor) {
        ctor.parameters.forEach { p ->
            // Un paramètre de ctor SUT qui ne correspond pas à un champ direct
            // est quand même une référence — on l'enregistre via `AsFieldOfSut`
            // synthétique. C'est sémantiquement correct : le ctor sert à câbler
            // une dépendance, qui sera un mock @InjectMocks. Si le champ
            // correspondant existe déjà, l'agrégation par FQN n'introduit pas
            // de doublon — juste un usage de plus.
            // Pour V1.2 minimal, on enregistre via un synthetic ClassField :
            ctx.register(p.type.fqName, UsageSite.AsFieldOfSut(
                ClassField(
                    name = p.name,
                    type = p.type,
                    visibility = "private",
                    declaredIn = "<ctor-param>"
                )
            ))
        }
    }

    // ── Phase BFS — exploration du corps d'une méthode ───────────────────────

    private fun visitMethodBody(
        ctx: BuildContext,
        method: MethodSignature,
        ownerFqn: String,
        depth: Int
    ) {
        // Si la méthode descend dans une frontière framework, on STOP — son
        // corps ne sera pas testé (STUB_VIA_SPY).
        if (frameworkPrefixes.isNotEmpty() &&
            detectFrameworkBoundary(method).isNotEmpty()
        ) return

        val calls = introspector.listMethodCalls(method)
        val bodyAnalysis = introspector.analyzeMethodBody(method)

        calls.forEach { call ->
            if (call.isStatic) {
                if (isUserStatic(call.targetType)) {
                    ctx.register(call.targetType, UsageSite.AsStaticCallTarget(call, method))
                    call.argTypes.forEach { argType ->
                        ctx.register(argType, UsageSite.AsTypeArgOfReference(call.targetType))
                    }
                }
                return@forEach
            }

            val calledMethod = findMethodIn(call.targetType, call.methodName, call.argTypes)

            if (call.targetType in ctx.hierarchyFqns) {
                // Appel intra-SUT — descendre dans la méthode visée si elle
                // n'est ni un getter trivial ni un framework boundary.
                if (calledMethod == null) return@forEach
                if (isTrivialGetter(calledMethod)) {
                    // Bug P préservé — le champ derrière le getter est ajouté
                    // comme AsFieldOfSut via le seed initial ; pas d'action ici.
                    return@forEach
                }
                ctx.methodQueue.add(MethodToVisit(calledMethod, call.targetType, depth + 1))
            } else {
                // Appel externe — registre la classe comme appelée d'instance.
                ctx.register(
                    call.targetType,
                    UsageSite.AsCallTarget(
                        call = call,
                        resolvedMethod = calledMethod,
                        callerOwnerFqn = ownerFqn,
                        callerMethod = method
                    )
                )
                // Arguments de l'appel — chaque type d'arg devient une
                // référence (le test devra construire/mocker l'arg).
                call.argTypes.forEach { argType ->
                    ctx.register(argType, UsageSite.AsTypeArgOfReference(call.targetType))
                }
            }
        }

        // Instanciations `new X(...)` dans le body — toujours DATA_STRUCTURE
        // candidate (impossible de mocker une instanciation en dur).
        bodyAnalysis.instantiations.forEach { type ->
            ctx.register(type.fqName, UsageSite.AsInstantiationInBody(method))
            type.typeArgs.forEach { arg ->
                ctx.register(arg.fqName, UsageSite.AsTypeArgOfReference(type.fqName))
            }
        }
    }

    // ── Propagation : returnType des signatures stubées sur les mocks ────────

    private fun propagateStubReturns(ctx: BuildContext) {
        // Snapshot immutable — on itère sur les ClassReference actuels mais on
        // peut ajouter de nouvelles entrées (returnType inédit). Pas de mutation
        // concurrente : on itère sur une copie convertie en `ClassReference`.
        val snapshot = ctx.snapshot()
        snapshot.values.forEach { ref ->
            ref.instanceCallSites.forEach { site ->
                val sig = site.resolvedMethod ?: return@forEach
                ctx.register(
                    sig.returnType.fqName,
                    UsageSite.AsStubReturn(mockOwnerFqn = ref.fqn, mockMethod = sig)
                )
                sig.returnType.typeArgs.forEach { arg ->
                    ctx.register(
                        arg.fqName,
                        UsageSite.AsStubReturn(mockOwnerFqn = ref.fqn, mockMethod = sig)
                    )
                }
            }
        }
    }

    // ── Propagation : fields des classes data-shaped (heuristique légère) ────

    private fun propagateDataStructureFields(ctx: BuildContext) {
        // On itère jusqu'à point fixe pour gérer les DTOs imbriqués —
        // ex: Order → Customer → Address. Garde-fou maxIterations contre
        // les références circulaires (ne devrait pas arriver avec
        // ctx.visitedDtoForFields mais paranoia).
        var iterations = 0
        val maxIterations = 10
        while (iterations < maxIterations) {
            val before = ctx.size()
            val snapshot = ctx.snapshot()
            snapshot.values.forEach { ref ->
                if (ref.fqn in ctx.visitedDtoForFields) return@forEach
                val descriptor = ref.descriptor ?: return@forEach
                // Heuristique : "data-shaped" = pas d'appel d'instance dessus
                // ET pas dans la hiérarchie SUT ET résolu.
                if (ref.isCalledAsInstance) return@forEach
                if (ref.fqn in ctx.hierarchyFqns) return@forEach
                if (ref.fqn.startsWith("java.") || ref.fqn.startsWith("javax.") ||
                    ref.fqn.startsWith("jakarta.") || ref.fqn.startsWith("kotlin.")) return@forEach
                ctx.visitedDtoForFields.add(ref.fqn)
                introspector.listFields(descriptor).forEach { f ->
                    ctx.register(
                        f.type.fqName,
                        UsageSite.AsFieldOfReferencedClass(ref.fqn, f.name)
                    )
                    f.type.typeArgs.forEach { arg ->
                        ctx.register(arg.fqName, UsageSite.AsTypeArgOfReference(f.type.fqName))
                    }
                }
            }
            if (ctx.size() == before) break
            iterations++
        }
        if (iterations >= maxIterations) {
            ctx.truncationReasons += "DTO field propagation hit max iterations ($maxIterations)"
        }
    }

    // ── Helpers locaux ────────────────────────────────────────────────────────

    private fun findMethodIn(
        classFqn: String, methodName: String, argTypeFqns: List<String>
    ): MethodSignature? {
        val cls = introspector.resolveClass(classFqn) ?: return null
        val candidates = introspector.listMethods(cls).filter { it.name == methodName }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { sig ->
            sig.parameters.map { it.type.fqName } == argTypeFqns
        } ?: candidates.first()
    }

    // Copie locale d'isTrivialGetter — Phase 3 refactorera vers un helper
    // partagé. Pour Phase 1, duplication minimale assumée.
    private fun isTrivialGetter(method: MethodSignature): Boolean {
        if (method.name == "<init>") return false
        if (method.parameters.isNotEmpty()) return false
        if (introspector.listMethodCalls(method).isNotEmpty()) return false
        val analysis = introspector.analyzeMethodBody(method)
        if (analysis.thrownExceptions.isNotEmpty()) return false
        if (analysis.caughtExceptions.isNotEmpty()) return false
        if (analysis.conditionalBranches.isNotEmpty()) return false
        if (analysis.instantiations.isNotEmpty()) return false
        if (analysis.expectedLambdas.isNotEmpty()) return false
        if (introspector.listFieldAssignments(method).isNotEmpty()) return false
        val accesses = introspector.listFieldAccesses(method)
        if (accesses.size != 1) return false
        val access = accesses.single()
        if (access.write) return false
        val body = introspector.readMethodBody(method).trim()
            .removeSurrounding("{", "}").trim()
        if (body.isNotEmpty()) {
            val name = access.fieldName
            val accepted = setOf("return $name;", "return this.$name;")
            if (body !in accepted) return false
        }
        return true
    }

    private fun detectFrameworkBoundary(method: MethodSignature): List<String> {
        if (frameworkPrefixes.isEmpty()) return emptyList()
        val calls = introspector.listMethodCalls(method)
        return calls.mapNotNull { c ->
            frameworkPrefixes.firstOrNull { p -> c.targetType.startsWith(p) }
        }.distinct()
    }

    private fun isUserStatic(classFqn: String): Boolean =
        SYSTEM_STATIC_PREFIXES.none { classFqn.startsWith(it) }

    // ── État interne du builder ──────────────────────────────────────────────

    private data class MethodToVisit(
        val method: MethodSignature,
        val ownerFqn: String,
        val depth: Int
    )

    private class BuildContext(
        private val introspector: CodeIntrospector,
        val hierarchyFqns: Set<String>
    ) {
        // Ordre d'insertion préservé via LinkedHashMap — assure des résultats
        // déterministes pour les tests et le rendu downstream.
        val refs: LinkedHashMap<String, MutableClassReference> = LinkedHashMap()
        val methodQueue: ArrayDeque<MethodToVisit> = ArrayDeque()
        val visitedMethods: MutableSet<String> = mutableSetOf()
        val visitedDtoForFields: MutableSet<String> = mutableSetOf()
        val truncationReasons: MutableList<String> = mutableListOf()

        fun register(fqn: String, usage: UsageSite) {
            // Filtre des types primitifs / void — pas de classe à référencer.
            if (fqn in PRIMITIVE_FQNS || fqn == "void") return
            val mut = refs.getOrPut(fqn) {
                MutableClassReference(fqn, introspector.resolveClass(fqn))
            }
            mut.usages += usage
        }

        fun toMap(): Map<String, ClassReference> =
            refs.mapValues { it.value.toImmutable() }

        // Snapshot immutable utilisé par les phases de propagation pour
        // itérer sans courir derrière les helpers ClassReference (qui
        // n'existent que sur l'immutable).
        fun snapshot(): Map<String, ClassReference> = toMap()

        fun size(): Int = refs.size
    }

    // Builder mutable interne — converti en `ClassReference` immutable au build.
    // Évite de reconstruire la liste à chaque ajout (perf O(1) vs O(n)).
    private class MutableClassReference(
        val fqn: String,
        val descriptor: ClassDescriptor?
    ) {
        val usages: MutableList<UsageSite> = mutableListOf()
        fun toImmutable(): ClassReference =
            ClassReference(fqn, descriptor, usages.toList())
    }

    companion object {
        private val SYSTEM_STATIC_PREFIXES = listOf(
            "java.", "javax.", "jakarta.", "kotlin.", "scala.", "sun.", "com.sun."
        )

        // Types primitifs Java — ne sont pas des classes à référencer.
        private val PRIMITIVE_FQNS = setOf(
            "boolean", "byte", "char", "short", "int", "long", "float", "double"
        )
    }
}

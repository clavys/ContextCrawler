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
import com.contextextractor.core.model.refs.VisitedMethod

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
    private val maxCrawlDepth: Int = 8,
    // V1.4.3 Bug KK — profondeur max de la descente dans les champs des DTOs
    // (BFS 3). depth 0 = types du flux de données de la cible ; chaque niveau
    // de champ imbriqué ajoute 1. Au-delà, les types restent référencés (et
    // matérialisés avec leurs champs) mais leurs propres champs ne génèrent
    // plus de nouvelles entrées — Bug DD prescrit déjà `mock(T.class)` ad hoc.
    private val maxDtoFieldDepth: Int = 2
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
        // filtrés en aval (relevance filter V1.4.3 dans RecursiveDeepStrategy).
        seedSutFields(ctx, hierarchy, sut)

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

        // BFS 2bis — Bug #C : détecter les méthodes intra-SUT dont le returnType
        // est appelé d'instance ailleurs dans le body (= chaînage type
        // `super.getStructurePage().getNombreMax(...)`). Sans ce détecteur,
        // le LLM ne sait pas qu'il faut spy+stub la méthode parent pour
        // injecter la valeur du chaînage → NPE ou ne compile pas.
        propagateChainedReturnSpy(ctx)

        // BFS 3 — pour chaque classe référencée data-shaped (sera vraisemblablement
        // classifiée DATA_STRUCTURE), descendre dans ses champs. Cette propagation
        // est PURE référence — on ne décide pas du pattern (RECORD/BUILDER/etc.)
        // ici ; le materializer le fera en PASSE 3.
        propagateDataStructureFields(ctx)

        return ReferenceGraph(
            byFqn = ctx.toMap(),
            visitedInternalMethods = ctx.visitedInternalMethods.toList(),
            truncationReasons = ctx.truncationReasons.toList()
        )
    }

    // ── Phases de seed ───────────────────────────────────────────────────────

    private fun seedSutFields(
        ctx: BuildContext,
        hierarchy: List<HierarchyLevel>,
        sut: ClassDescriptor
    ) {
        hierarchy.forEach { level ->
            val cls = introspector.resolveClass(level.classFqn) ?: return@forEach
            // V1.4.3 Bug JJ — vue contextuelle : `modele : M` est enregistré
            // sous sa liaison concrète, cohérente avec BLOC 3 côté stratégie.
            introspector.listFieldsInContext(cls, sut).forEach { field ->
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
                // V1.4.4 Bug OO — un type-arg d'un paramètre de target est SUR
                // LA SURFACE du test : le test doit le CONSTRUIRE pour appeler
                // target (`List<TriDTO>` → TriDTO). L'enregistrer en simple
                // AsTypeArgOfReference (usage faible) le privait de la descente
                // DTO bornée V1.4.3 → régression Astrea 4.1 : TriDTO non crawlé
                // → `OrdreTriEnum [ENUM]` absent du prompt → le LLM hallucine
                // la constante `ASC` (le bug R3-A exact que DTO_ENUM_VALUES
                // avait été créé pour empêcher).
                ctx.register(arg.fqName, UsageSite.AsParamOfTarget(p))
            }
        }
        ctx.register(targetMethod.returnType.fqName, UsageSite.AsReturnTypeOfTarget(asGenericArg = false))
        targetMethod.returnType.typeArgs.forEach { arg ->
            ctx.register(arg.fqName, UsageSite.AsReturnTypeOfTarget(asGenericArg = true))
        }
        // V1.4.4 Bug OO — exceptions déclarées par target : matérialisées comme
        // dataStructures (avec signature de ctor — Bug LL) pour que le test
        // puisse écrire `thenThrow(new X(...))` sans inventer le constructeur.
        targetMethod.declaredThrows.forEach { thrown ->
            ctx.register(thrown, UsageSite.AsDeclaredThrowOfTarget)
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
        // Enregistrer la méthode visitée — sauf si c'est la target elle-même
        // (depth == 0) : target n'apparaît pas dans `# Sous-méthodes internes`.
        // La détection de frontière framework se fait AU CALL SITE (sur la
        // méthode enfant), pas ici sur la méthode courante. Sinon target
        // serait skip dès qu'elle descend transitivement dans le framework
        // (cas typique controleur JSF).
        if (depth > 0) {
            ctx.visitedInternalMethods.add(VisitedMethod(
                ownerFqn = ownerFqn,
                signature = method
            ))
        }

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
                // Bug N préservé — détection transitive : si la méthode appelée
                // descend dans un préfixe framework, on l'enregistre comme
                // stubViaSpy SANS visiter son corps. Vérification au call site.
                // V1.4.4 Bug NN — exception : une méthode PRIVÉE n'est pas
                // stubable via spy ; on la visite normalement (ses appels
                // framework seront classés SYSTEM_IGNORE, sans danger).
                val frameworkHits =
                    if (calledMethod.visibility == "private") emptyList()
                    else detectFrameworkBoundary(calledMethod, ctx.hierarchyFqns)
                if (frameworkHits.isNotEmpty()) {
                    ctx.visitedInternalMethods.add(VisitedMethod(
                        ownerFqn = call.targetType,
                        signature = calledMethod,
                        isFrameworkBoundary = true,
                        frameworkPrefixesHit = frameworkHits
                    ))
                    return@forEach
                }
                ctx.methodQueue.add(MethodToVisit(calledMethod, call.targetType, depth + 1))
            } else {
                // Appel externe — registre la classe comme appelée d'instance.
                //
                // V1.4.5 Bug QQ — attribution au RECEVEUR concret plutôt qu'à la
                // classe déclarante. Sur une hiérarchie de modeles génériques
                // (Astrea 4.4), `this.modele.getX()` se résout sur
                // AbstractSaisieMessageModele et `this.modele.getY()` sur
                // SaisieMessage01Modele → le prompt prescrivait DEUX mocks pour
                // UN objet runtime, avec les stubs dispersés. En attribuant au
                // type statique du receveur (substitué : SaisieMessage01Modele),
                // tous les stubs convergent sur le mock réellement injecté.
                val mockOwnerFqn = call.receiverTypeFqn
                    ?.takeIf { it.contains('.') && it !in ctx.hierarchyFqns }
                    ?: call.targetType
                ctx.register(
                    mockOwnerFqn,
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
                    ctx.register(argType, UsageSite.AsTypeArgOfReference(mockOwnerFqn))
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

    // ── Bug #C : intra-SUT method whose returnType is chained-called ─────────
    //
    // Cas Astrea 4.1 : `super.getStructurePage().getNombreMax...()`.
    // Sans signal, le LLM construit IHMDTO en local mais ne sait pas l'injecter
    // comme retour de `super.getStructurePage()` → coverage incomplète OU NPE.
    //
    // Détection : si une méthode intra-SUT visitée retourne un type non-trivial
    // (non-void, non-primitive, non-system) ET ce type a au moins un
    // `AsCallTarget` dans le graphe (= chaîné quelque part) → promote la méthode
    // en `STUB_VIA_SPY` (réutilise le flag isFrameworkBoundary).
    //
    // Garde-fou : skip si la méthode assigne des fields SUT (init protocol).
    // Sinon on remplacerait le body d'un init method par un spy stub, cassant
    // BLOC 7.

    private fun propagateChainedReturnSpy(ctx: BuildContext) {
        val snapshot = ctx.snapshot()
        val updated = ctx.visitedInternalMethods.map { vm ->
            // Déjà marqué framework boundary → ne pas re-traiter.
            if (vm.isFrameworkBoundary) return@map vm

            // V1.4.4 Bug NN — Mockito ne peut PAS stubber une méthode PRIVÉE
            // via spy (`doReturn(...).when(sut).methodePrivee(...)` ne compile
            // pas). Prescrire le pattern spy sur une privée = erreur de compile
            // garantie (Astrea 4.4 : `gererPreRequisChampHeureAudience` private
            // → « has private access »). On la laisse en internal logic
            // informationnelle.
            if (vm.signature.visibility == "private") return@map vm

            val rtFqn = vm.signature.returnType.fqName
            // Void / primitives / system → skip.
            if (rtFqn == "void" || rtFqn in PRIMITIVE_FQNS) return@map vm
            if (SYSTEM_STATIC_PREFIXES.any { rtFqn.startsWith(it) }) return@map vm
            // Skip si returnType est dans la hiérarchie SUT (mocker un self
            // serait surprenant ; cas marginal).
            if (rtFqn in ctx.hierarchyFqns) return@map vm

            // Safety BLOC 7 : skip si la méthode assigne des fields SUT.
            // Un init method qui retourne quelque chose n'est pas attendu,
            // mais on protège quand même.
            val assignments = runCatching {
                introspector.listFieldAssignments(vm.signature)
            }.getOrDefault(emptyList())
            if (assignments.any { it.ownerType in ctx.hierarchyFqns }) return@map vm

            // Détection : returnType appelé d'instance ailleurs dans le graphe ?
            val refOnReturn = snapshot[rtFqn] ?: return@map vm
            if (refOnReturn.isCalledAsInstance) {
                vm.copy(
                    isFrameworkBoundary = true,
                    frameworkPrefixesHit = listOf("return-chained-on-${rtFqn.substringAfterLast('.')}")
                )
            } else vm
        }
        // Re-emit dans le même ordre.
        ctx.visitedInternalMethods.clear()
        ctx.visitedInternalMethods.addAll(updated)
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
                // V1.4.5 Bug QQ — le retour résolu AU CALL-SITE (substitution
                // générique faite par PSI) prime sur le retour déclaré : il
                // porte les types concrets que le test devra construire
                // (`List<Section01Modele>` au lieu de `List<T>` ou raw List).
                val returnType = site.call.resolvedReturnType ?: sig.returnType
                ctx.register(
                    returnType.fqName,
                    UsageSite.AsStubReturn(mockOwnerFqn = ref.fqn, mockMethod = sig)
                )
                returnType.typeArgs.forEach { arg ->
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
        // V1.4.3 Bug KK — réécriture de l'ancien point fixe (quasi illimité :
        // 10 itérations ≈ 10 niveaux d'imbrication). Vu en prod Astrea
        // case 4.4 : 125 champs hérités non utilisés + graphe d'entités JPA
        // interconnecté → 260 dataStructures, prompt ingérable pour le LLM.
        //
        // Deux gardes :
        //   (1) PERTINENCE — la descente part UNIQUEMENT des types présents
        //       dans le flux de données de la cible (usage fort : param/retour
        //       de target, appel, static, instanciation, stub return). Un type
        //       dont le seul lien est d'être déclaré comme champ de la
        //       hiérarchie (AsFieldOfSut pur) ne tire plus son graphe de
        //       champs transitifs dans le prompt.
        //   (2) PROFONDEUR — bornée par maxDtoFieldDepth. Les types atteints
        //       à la limite restent référencés et matérialisés avec leurs
        //       champs (capturePhase3), mais ne génèrent plus d'entrées pour
        //       leurs propres champs.
        val depths = mutableMapOf<String, Int>()
        val queue = ArrayDeque<String>()
        ctx.snapshot().values.forEach { ref ->
            if (ref.usages.any { it.isStrongUsage() }) {
                depths[ref.fqn] = 0
                queue.add(ref.fqn)
            }
        }
        var depthTruncated = false
        while (queue.isNotEmpty()) {
            val fqn = queue.removeFirst()
            if (fqn in ctx.visitedDtoForFields) continue
            val depth = depths.getValue(fqn)
            val ref = ctx.snapshot()[fqn] ?: continue
            val descriptor = ref.descriptor ?: continue
            // Heuristique : "data-shaped" = pas d'appel d'instance dessus
            // ET pas dans la hiérarchie SUT ET résolu.
            if (ref.isCalledAsInstance) continue
            if (fqn in ctx.hierarchyFqns) continue
            if (fqn.startsWith("java.") || fqn.startsWith("javax.") ||
                fqn.startsWith("jakarta.") || fqn.startsWith("kotlin.")) continue
            // §3.4 V1.1 préservé : ENUM ne déclenche PAS de Phase 4 — les
            // constantes sont des valeurs, pas des types à crawler.
            if (descriptor.isEnum) {
                ctx.visitedDtoForFields.add(fqn)
                continue
            }
            if (depth >= maxDtoFieldDepth) {
                depthTruncated = true
                continue
            }
            // SEALED : Phase 4 sur les sous-classes permises (pas sur
            // listFields qui pourrait contenir des champs hérités sans
            // intérêt). Reste fidèle au §3.4 V1.1.
            if (descriptor.isSealed) {
                ctx.visitedDtoForFields.add(fqn)
                descriptor.permittedSubclasses.forEach { subFqn ->
                    ctx.register(subFqn,
                        UsageSite.AsFieldOfReferencedClass(fqn, "<sealed-sub>"))
                    enqueueChild(subFqn, depth + 1, depths, queue)
                }
                continue
            }
            // RECORD traité comme classe ordinaire : ses composants sont
            // accessibles via listFields et doivent être explorés normalement.
            ctx.visitedDtoForFields.add(fqn)
            introspector.listFields(descriptor).forEach { f ->
                ctx.register(
                    f.type.fqName,
                    UsageSite.AsFieldOfReferencedClass(fqn, f.name)
                )
                enqueueChild(f.type.fqName, depth + 1, depths, queue)
                f.type.typeArgs.forEach { arg ->
                    ctx.register(arg.fqName, UsageSite.AsTypeArgOfReference(f.type.fqName))
                    enqueueChild(arg.fqName, depth + 1, depths, queue)
                }
            }
        }
        if (depthTruncated) {
            ctx.truncationReasons += "DTO field propagation depth-capped at $maxDtoFieldDepth"
        }
    }

    private fun enqueueChild(
        fqn: String,
        depth: Int,
        depths: MutableMap<String, Int>,
        queue: ArrayDeque<String>
    ) {
        val known = depths[fqn]
        if (known != null && known <= depth) return
        depths[fqn] = depth
        queue.add(fqn)
    }

    // V1.4.3 Bug KK — un usage est « fort » quand il place le type dans le
    // flux de données du test : la cible le reçoit, le retourne, l'appelle,
    // l'instancie, ou il revient d'un stub. AsFieldOfSut (champ déclaré mais
    // pas forcément touché) et les liens dérivés (type-arg, champ d'un autre
    // DTO) ne suffisent pas à justifier une descente.
    private fun UsageSite.isStrongUsage(): Boolean = when (this) {
        is UsageSite.AsParamOfTarget,
        is UsageSite.AsReturnTypeOfTarget,
        is UsageSite.AsDeclaredThrowOfTarget,
        is UsageSite.AsCallTarget,
        is UsageSite.AsStaticCallTarget,
        is UsageSite.AsInstantiationInBody,
        is UsageSite.AsStubReturn -> true
        is UsageSite.AsFieldOfSut,
        is UsageSite.AsFieldOfReferencedClass,
        is UsageSite.AsTypeArgOfReference -> false
    }

    // ── Helpers locaux ────────────────────────────────────────────────────────

    private fun findMethodIn(
        classFqn: String, methodName: String, argTypeFqns: List<String>
    ): MethodSignature? {
        val cls = introspector.resolveClass(classFqn) ?: return null
        // 1. Recherche directe dans la classe cible (cas nominal).
        findMatchingMethod(cls, methodName, argTypeFqns)?.let { return it }
        // 2. Bug #C bis V1.3 — remontée hiérarchie quand la méthode n'est pas
        //    visible depuis `classFqn` (cas Astrea typique : `super.getStructurePage()`
        //    avec `call.targetType = BaseAstreaControleur` mais la méthode définie
        //    plus haut dans `BaseControleur`). Sans cette remontée, le call est
        //    silencieusement perdu → LLM hallucine le receiver (cf §10.4 P0).
        for (superCls in introspector.listSuperClasses(cls)) {
            findMatchingMethod(superCls, methodName, argTypeFqns)?.let { return it }
        }
        // 3. Toujours non-résolue après remontée → null (comportement legacy).
        //    Le caller `visitMethodBody` saute alors silencieusement le call.
        //    Si on observe encore Bug #C bis après cette remontée → étape 2bis
        //    avec fallback synthetic.
        return null
    }

    // Helper extrait pour ne pas dupliquer la logique de matching dans
    // findMethodIn (cas direct) et la remontée hiérarchie (cas Bug #C bis).
    private fun findMatchingMethod(
        cls: ClassDescriptor, methodName: String, argTypeFqns: List<String>
    ): MethodSignature? {
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

    private fun detectFrameworkBoundary(
        method: MethodSignature,
        hierarchyFqns: Set<String> = emptySet(),
        maxBfsDepth: Int = 8
    ): List<String> {
        if (frameworkPrefixes.isEmpty()) return emptyList()
        // Bug N préservé : détection TRANSITIVE intra-SUT. Si `redirige(1)`
        // ne touche pas directement javax.faces mais y descend via
        // `redirige(2) → redirige(3) → javax.faces`, on signale tout de
        // même `redirige(1)` comme frontière framework. Le test stub alors
        // l'ENTRÉE de la cascade, pas la feuille — pattern attendu Mockito 4.x
        // pour éviter d'exécuter la cascade côté SUT.
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<MethodSignature, Int>>()
        queue.add(method to 0)
        val hits = LinkedHashSet<String>()
        while (queue.isNotEmpty()) {
            val (m, d) = queue.removeFirst()
            if (!visited.add(m.canonical())) continue
            if (d > maxBfsDepth) continue
            val calls = introspector.listMethodCalls(m)
            calls.forEach { c ->
                frameworkPrefixes.firstOrNull { p -> c.targetType.startsWith(p) }
                    ?.let { hits += it }
            }
            // Descente transitive uniquement si on dispose de la hiérarchie SUT
            // (mode rétro-compatible : si vide, détection directe seulement).
            if (hierarchyFqns.isNotEmpty()) {
                calls.forEach { c ->
                    if (c.targetType !in hierarchyFqns) return@forEach
                    val cls = introspector.resolveClass(c.targetType) ?: return@forEach
                    val candidates = introspector.listMethods(cls).filter { it.name == c.methodName }
                    val next = candidates.firstOrNull { sig ->
                        sig.parameters.map { it.type.fqName } == c.argTypes
                    } ?: candidates.firstOrNull() ?: return@forEach
                    queue.add(next to d + 1)
                }
            }
        }
        return hits.toList()
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
        // Méthodes intra-SUT visitées — alimente InternalLogic en PASSE 3.
        // Distinct de visitedMethods (qui sert juste à dédoublonner le BFS).
        val visitedInternalMethods: MutableList<VisitedMethod> = mutableListOf()
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

package com.contextextractor.strategies.recursive

import com.contextextractor.core.classifier.CallerContext
import com.contextextractor.core.classifier.ClassClassifier
import com.contextextractor.core.classifier.ExtractionMode
import com.contextextractor.core.classifier.VisitKey
import com.contextextractor.core.classifier.VisitRegistry
import com.contextextractor.core.strategy.Budget
import com.contextextractor.core.strategy.ContextStrategy
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.core.strategy.StrategyInput
import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.SymbolKind
import com.contextextractor.core.model.BuilderInfo
import com.contextextractor.core.model.BuilderMethodInfo
import com.contextextractor.core.model.ConstructionPattern
import com.contextextractor.core.model.ContextResult
import com.contextextractor.core.model.ContextTree
import com.contextextractor.core.model.DataField
import com.contextextractor.core.model.DataStructureInfo
import com.contextextractor.core.model.FactoryMethodInfo
import com.contextextractor.core.model.FieldInitProtocol
import com.contextextractor.core.model.HierarchyLevel
import com.contextextractor.core.model.InstanceCall
import com.contextextractor.core.model.InstantiationPlan
import com.contextextractor.core.model.InternalLogic
import com.contextextractor.core.model.MockInfo
import com.contextextractor.core.model.SelectedConstructor
import com.contextextractor.core.model.Setter
import com.contextextractor.core.model.StaticCall
import com.contextextractor.core.model.StaticCallInfo
import com.contextextractor.core.model.TargetMethodAnalysis
import com.contextextractor.core.model.TestabilityDiagnostic
import com.contextextractor.core.model.init.InitStrategy
import com.contextextractor.core.model.init.MethodKey
import com.contextextractor.core.model.init.POST_CONSTRUCT_FQNS
import com.contextextractor.strategies.recursive.initbloc.CallGraphBuilder
import com.contextextractor.strategies.recursive.initbloc.EntryPointFinder
import com.contextextractor.strategies.recursive.initbloc.SourceCollector
import com.contextextractor.strategies.recursive.initbloc.StrategySelector
import com.contextextractor.strategies.recursive.initbloc.ordinalPriority

// Stratégie récursive principale — STRATEGIE.md §3 et §4.
//
// Avancement par sous-étape :
//  4a — VisitKey, VisitRegistry, DefaultClassifier (terminé)
//  4b — BLOCs 1-5 du mode SUT_BOOTSTRAP (terminé)
//  4c — BLOC 6 + modes INTERNAL_LOGIC / MOCK_EXTERNAL + DATA_STRUCTURE minimal (ce fichier)
//  4d — Mode DATA_STRUCTURE complet (7 patterns)
//  4e — BLOC 7 (protocole d'init des champs)
//  4f — Câblage StrategyRegistry + mapping ContextResult → ContextTree
//
// `extract()` lève NotImplementedError sur le mapping ContextTree jusqu'à 4f.
// Les tests appellent directement `extractCore()`.
class RecursiveDeepStrategy : ContextStrategy {

    override val id: String = "recursive-deep"
    override val displayName: String = "Récursif profond"
    override val description: String =
        "Stratégie complète STRATEGIE.md §3 — V1 du plugin."

    private val springInjectAnnotations = setOf(
        "org.springframework.beans.factory.annotation.Autowired",
        "jakarta.inject.Inject",
        "javax.inject.Inject",
        "jakarta.annotation.Resource",
        "javax.annotation.Resource",
        "org.springframework.beans.factory.annotation.Value",
        "jakarta.persistence.PersistenceContext"
    )

    override fun extract(input: StrategyInput): ContextTree =
        ContextResultTreeMapper().map(extractResult(input))

    fun extractResult(input: StrategyInput): ContextResult {
        val (sut, targetMethod) = resolveCursor(input)
        return extractCore(input.introspector, input.classifier, input.config, sut, targetMethod)
    }

    fun extractCore(
        introspector: CodeIntrospector,
        classifier: ClassClassifier,
        config: StrategyConfig,
        sut: ClassDescriptor,
        targetMethod: MethodSignature
    ): ContextResult {
        // ── BLOC 1 : HIÉRARCHIE COMPLÈTE ──────────────────────────────────────
        val hierarchy = buildHierarchy(introspector, sut)
        val hierarchyFqns = hierarchy.map { it.classFqn }.toSet()

        // ── BLOC 2 : ANALYSE DE LA MÉTHODE CIBLE ──────────────────────────────
        val targetCalls = introspector.listMethodCalls(targetMethod)
        val targetBody = introspector.readMethodBody(targetMethod)
        val targetAnalysis = analyzeTargetMethod(targetMethod, targetCalls, targetBody)

        // ── BLOC 3 : INVENTAIRE DES CHAMPS UTILES ─────────────────────────────
        val activeFields = introspector.listFieldAccesses(targetMethod)
            .map { it.fieldName }.toSet()
        val usefulFields = collectUsefulFields(introspector, hierarchy, activeFields)

        // ── BLOC 4 : PROTOCOLE DE CONSTRUCTION DU SUT ─────────────────────────
        val selectedConstructor = chooseConstructor(introspector, sut)

        // ── BLOC 5 : LEVIERS DE MUTATION ──────────────────────────────────────
        val (setters, postConstruct) = collectSettersAndPostConstruct(
            introspector, hierarchy, usefulFields
        )

        // ── BLOC 6 : POINTS D'ENTRÉE RÉCURSIFS ────────────────────────────────
        val ctx = RecursionContext(
            introspector = introspector,
            classifier = classifier,
            budget = config.budget,
            sutHierarchyFqns = hierarchyFqns,
            targetMethod = targetMethod
        )
        // Marquer la racine SUT_BOOTSTRAP visitée — évite une re-entrée si BLOC 6
        // déclenche un appel au SUT lui-même (cas pathologique mais possible).
        ctx.visits.add(VisitKey(ExtractionMode.SUT_BOOTSTRAP, sut.fqn, targetMethod.name))

        recurseBlock6(ctx, sut, targetMethod, selectedConstructor, usefulFields,
            targetAnalysis, depth = 0)

        // ── BLOC 7 : PROTOCOLE D'INITIALISATION DES CHAMPS (§4) ───────────────
        val block7 = runBlock7(
            introspector, config.budget, hierarchyFqns,
            usefulFields, selectedConstructor, targetMethod
        )

        // ── Réconciliation post-BLOC 7 — verrou critique étape 7 ──────────────
        // Si BLOC 7 a décidé qu'un champ s'auto-construit (CALL_PUBLIC_WITH_ARGS,
        // CALL_PUBLIC_TRANSITIVE, CALL_POST_CONSTRUCT, SETTER, etc.), alors le
        // type du champ ne doit PLUS apparaître dans `mocks` — sinon le prompt
        // serait contradictoire (« mocke Config » + « sut.configure() construit
        // config » dans le même prompt). BLOC 7 a la priorité sémantique :
        // c'est l'avis le plus tardif (et le plus informé).
        val rawMocks = ctx.mocks.mapValues { it.value.build() }
        val reconciledMocks = reconcileMocksWithInitProtocol(
            rawMocks, block7.initProtocol, usefulFields
        )

        // ── Enrichissement internalLogics — étape 7 #3 ─────────────────────────
        // Les méthodes downstream intra-SUT découvertes par BLOC 7 (ex : buildCache
        // appelée depuis warmup) ne sont PAS visitées en BLOC 6 si la méthode
        // cible ne les appelle pas. Pour qu'elles apparaissent dans la section
        // « # Sous-méthodes internes » du prompt et que le LLM voie qu'elles
        // existent (cf §3.2 — INTERNAL_LOGIC est légitime ici), on les ajoute
        // synthétiquement à `internalLogics` avant la sortie.
        val enrichedInternalLogics = enrichInternalLogicsWithDownstream(
            introspector = introspector,
            hierarchyFqns = hierarchyFqns,
            initProtocol = block7.initProtocol,
            existing = ctx.internalLogics.toMap()
        )

        return ContextResult(
            sutFqName = sut.fqn,
            hierarchy = hierarchy,
            fields = usefulFields,
            instantiationPlan = InstantiationPlan(
                selectedConstructor = selectedConstructor,
                setters = setters,
                postConstruct = postConstruct
            ),
            targetMethod = targetAnalysis,
            internalLogics = enrichedInternalLogics,
            mocks = reconciledMocks,
            dataStructures = ctx.dataStructures.toMap(),
            staticCalls = ctx.staticCalls.toList(),
            initProtocol = block7.initProtocol,
            initOrder = block7.initOrder,
            testabilityDiagnostic = block7.diagnostic,
            intraSutCallGraph = block7.callGraphSerialized,
            truncated = ctx.truncationReasons.isNotEmpty(),
            truncationReasons = ctx.truncationReasons.toList()
        )
    }

    // Réconciliation BLOC 6 ↔ BLOC 7 sur les mocks. Voir doc dans extractCore.
    //
    // **Règle** : pour chaque type T présent dans `mocks` qui correspond au
    // type d'au moins un champ de la SUT, T est conservé UNIQUEMENT s'il
    // existe au moins un champ de type T dont la stratégie d'init est
    // MOCKITO_INJECT_MOCKS. Sinon T est supprimé.
    //
    // **Cas multi-champs même type** : `Config configA, Config configB` avec
    // A=CALL_PUBLIC_WITH_ARGS et B=MOCKITO. Config reste dans mocks (B en a
    // besoin). Si les DEUX étaient CALL_PUBLIC_*, Config serait retiré.
    //
    // **Cas type non-champ** : un type peut être dans mocks sans être un
    // champ de la SUT (ex : argument passé à un appel de méthode externe).
    // Dans ce cas il n'y a pas de stratégie d'init le concernant — on le
    // conserve (verrou : `?: return@filterKeys true`).
    private fun reconcileMocksWithInitProtocol(
        rawMocks: Map<String, MockInfo>,
        initProtocol: Map<String, FieldInitProtocol>,
        fields: List<ClassField>
    ): Map<String, MockInfo> {
        val fieldsByType: Map<String, List<ClassField>> = fields.groupBy { it.type.fqName }
        return rawMocks.filterKeys { typeFqn ->
            val fieldsOfThisType = fieldsByType[typeFqn] ?: return@filterKeys true
            fieldsOfThisType.any { field ->
                initProtocol[field.name]?.recommendedStrategy is InitStrategy.MOCKITO_INJECT_MOCKS
            }
        }
    }

    // Étape 7 #3 — synthèse de méthodes downstream BLOC 7 dans internalLogics.
    //
    // Les `CALL_PUBLIC_TRANSITIVE.downstreamChain` contiennent des canonicals
    // de méthodes intra-SUT atteintes en aval depuis l'assignment site
    // (ex : `buildCache()` derrière `warmup`). Ces méthodes sont rarement
    // visitées en BLOC 6 (la méthode cible ne les appelle pas) mais sont
    // pertinentes pour le LLM : elles produisent les valeurs assignées et
    // contiennent les appels externes à stubber.
    //
    // **Lookup classFqn** : on parcourt la hiérarchie pour matcher le canonical.
    // Si plusieurs classes ont une méthode au même canonical (override), on
    // prend la PREMIÈRE rencontrée — l'ordre `hierarchyFqns` est SUT → super,
    // donc la version la plus dérivée gagne (cohérent avec dispatch dynamique).
    //
    // **Idempotent** : si l'entrée existe déjà (BLOC 6 l'a visitée), on ne
    // l'écrase pas. Le résumé d'appels existant a déjà la profondeur du BLOC 6.
    private fun enrichInternalLogicsWithDownstream(
        introspector: CodeIntrospector,
        hierarchyFqns: Set<String>,
        initProtocol: Map<String, FieldInitProtocol>,
        existing: Map<String, InternalLogic>
    ): Map<String, InternalLogic> {
        val downstreamCanonicals = initProtocol.values
            .map { it.recommendedStrategy }
            .filterIsInstance<InitStrategy.CALL_PUBLIC_TRANSITIVE>()
            .flatMap { it.downstreamChain }
            .toSet()
        if (downstreamCanonicals.isEmpty()) return existing

        // Index hiérarchie : `(classFqn, MethodSignature)` énuméré une fois.
        val hierarchyMethods: List<Pair<String, MethodSignature>> = hierarchyFqns.flatMap { fqn ->
            val cls = introspector.resolveClass(fqn) ?: return@flatMap emptyList()
            introspector.listMethods(cls).map { fqn to it }
        }

        val out = LinkedHashMap(existing)
        for (canonical in downstreamCanonicals) {
            val match = hierarchyMethods.firstOrNull { (_, m) -> m.canonical() == canonical }
                ?: continue
            val (ownerFqn, sig) = match
            val key = "$ownerFqn#${sig.canonical()}"
            if (key in out) continue
            // Synthèse minimale : résumé d'appels (= une ligne par appel non-static).
            // Le format `targetType.methodName` est cohérent avec le rendu §6 actuel.
            val callSummaries = introspector.listMethodCalls(sig)
                .filter { !it.isStatic }
                .map { c -> "${c.targetType}.${c.methodName}" }
                .distinct()
            // §3.2 — la méthode est intra-SUT, frontière ouverte : on lit le corps.
            val body = introspector.readMethodBody(sig)
            out[key] = InternalLogic(
                signature = sig,
                callSummaries = callSummaries,
                body = body
            )
        }
        return out
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLOC 7 — protocole d'initialisation (§4)
    // ──────────────────────────────────────────────────────────────────────────
    //
    // Construit pour chaque champ utile son `FieldInitProtocol` (sources +
    // strategie). Trois invariants :
    //   • CallGraphBuilder.build() est invoqué UNE seule fois par SUT — coût
    //     O(hierarchie × méthodes), à amortir sur tous les champs. Construire
    //     par champ ferait du O(champs × hierarchie × méthodes) inutilement.
    //   • EntryPointFinder et StrategySelector sont aussi construits une fois
    //     car ils maintiennent des index lazy (signatureLookup, ownerByMethod)
    //     qu'on ne veut PAS recalculer à chaque champ.
    //   • L'ordre dans `initOrder` est dicté par §4.7 (5 niveaux fixes via
    //     `InitStrategy.ordinalPriority()`) — pas un vrai tri topologique.
    //
    // Retour groupé pour éviter de propager 4 paramètres dans extractCore().
    private fun runBlock7(
        introspector: CodeIntrospector,
        budget: Budget,
        hierarchyFqns: Set<String>,
        usefulFields: List<ClassField>,
        selectedConstructor: SelectedConstructor,
        targetMethod: MethodSignature
    ): Block7Result {
        // 1) Graphe d'appels INVERSE intra-SUT — partagé par tous les champs.
        val callGraph = CallGraphBuilder(introspector).build(hierarchyFqns)

        // 2) Localisation de targetMethod dans la hiérarchie pour lui assigner
        //    un MethodKey (le sélecteur l'utilise pour exclure les chemins qui
        //    passent par target — voir EntryPointFinder.find()).
        val targetOwner = findOwnerOfMethod(introspector, hierarchyFqns, targetMethod)
            ?: hierarchyFqns.first() // fallback : premier niveau de la hiérarchie
        val targetMethodKey = MethodKey.of(targetOwner, targetMethod)

        // 3) Composants partagés — instances réutilisées sur tous les champs.
        val finder = EntryPointFinder(
            introspector = introspector,
            hierarchyFqns = hierarchyFqns,
            maxGraphDepth = budget.maxGraphDepth
        )
        val collector = SourceCollector(introspector, hierarchyFqns)
        val selector = StrategySelector(
            introspector = introspector,
            hierarchyFqns = hierarchyFqns,
            callGraph = callGraph,
            finder = finder,
            targetMethod = targetMethod,
            targetMethodKey = targetMethodKey
        )

        // 4) Pour chaque champ utile, collecte sources + élit stratégie.
        //    LinkedHashMap pour conserver l'ordre d'insertion des champs (utile
        //    pour les renderers qui veulent énumérer dans l'ordre déclaratif).
        val initProtocol = LinkedHashMap<String, FieldInitProtocol>()
        for (field in usefulFields) {
            val sources = collector.collect(field, selectedConstructor)
            val strategy = selector.choose(field, sources)
            initProtocol[field.name] = FieldInitProtocol(
                field = field,
                sources = sources,
                recommendedStrategy = strategy
            )
        }

        // 5) Tri par priorité §4.7 (sortedBy ASCENDING d'ordinalPriority — 0 en
        //    premier). En cas d'égalité, l'ordre d'insertion (LinkedHashMap)
        //    prévaut, ce qui rend le résultat déterministe.
        val initOrder = initProtocol.entries
            .sortedBy { it.value.recommendedStrategy.ordinalPriority() }
            .map { it.key }

        // 6) Diagnostic de testabilité — agrégat des UNTESTABLE_AS_IS.
        val diagnostic = buildTestabilityDiagnostic(initProtocol)

        // 7) Sérialisation du callGraph pour ContextResult — `Map<String,List<String>>`.
        //    Format clé : `${classFqn}#${canonical}` — strictement identique au
        //    format `VisitKey.methodCanonical` pour cohérence avec les autres maps.
        val callGraphSerialized = callGraph.entries.associate { (callee, callers) ->
            "${callee.classFqn}#${callee.canonical}" to
                callers.map { "${it.classFqn}#${it.canonical}" }
        }

        return Block7Result(initProtocol, initOrder, diagnostic, callGraphSerialized)
    }

    // §4.6 construireDiagnostic — testable si AUCUN champ n'est UNTESTABLE_AS_IS.
    private fun buildTestabilityDiagnostic(
        initProtocol: Map<String, FieldInitProtocol>
    ): TestabilityDiagnostic {
        val blockers = initProtocol.entries
            .filter { it.value.recommendedStrategy is InitStrategy.UNTESTABLE_AS_IS }
        if (blockers.isEmpty()) {
            return TestabilityDiagnostic(testable = true)
        }
        val blockingFields = blockers.map { it.key }
        val reasons = blockers.map {
            (it.value.recommendedStrategy as InitStrategy.UNTESTABLE_AS_IS).reason
        }
        val refactorHints = blockers
            .flatMap { (it.value.recommendedStrategy as InitStrategy.UNTESTABLE_AS_IS).refactorHints }
            .distinct()
        return TestabilityDiagnostic(
            testable = false,
            blockingFields = blockingFields,
            reasons = reasons,
            refactorHints = refactorHints
        )
    }

    // Recherche linéaire — la hiérarchie est généralement de 1-3 classes,
    // donc inutile d'indexer. Retourne le premier niveau qui déclare la méthode.
    private fun findOwnerOfMethod(
        introspector: CodeIntrospector,
        hierarchyFqns: Set<String>,
        method: MethodSignature
    ): String? {
        for (classFqn in hierarchyFqns) {
            val cls = introspector.resolveClass(classFqn) ?: continue
            if (introspector.listMethods(cls).any { it.canonical() == method.canonical() }) {
                return classFqn
            }
        }
        return null
    }

    private data class Block7Result(
        val initProtocol: Map<String, FieldInitProtocol>,
        val initOrder: List<String>,
        val diagnostic: TestabilityDiagnostic,
        val callGraphSerialized: Map<String, List<String>>
    )

    // ──────────────────────────────────────────────────────────────────────────
    // BLOC 1
    // ──────────────────────────────────────────────────────────────────────────

    private fun buildHierarchy(
        introspector: CodeIntrospector,
        sut: ClassDescriptor
    ): List<HierarchyLevel> {
        val levels = mutableListOf(HierarchyLevel(sut.fqn, sut.annotations))
        introspector.listSuperClasses(sut).forEach { superCls ->
            levels += HierarchyLevel(superCls.fqn, superCls.annotations)
        }
        return levels
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLOC 2
    // ──────────────────────────────────────────────────────────────────────────

    private fun analyzeTargetMethod(
        targetMethod: MethodSignature,
        calls: List<MethodCall>,
        body: String
    ): TargetMethodAnalysis {
        val instanceCalls = calls.filterNot { it.isStatic }.map {
            InstanceCall(it.targetType, "", it.methodName, it.argTypes)
        }
        val staticCalls = calls.filter { it.isStatic }.map {
            StaticCall(it.targetType, it.methodName, it.argTypes)
        }
        // Note 4c : instantiations / lambdas / thrownExceptions / caughtExceptions
        // / conditionalBranches / nonDeterministicSources nécessitent des accès PSI
        // dédiés (NewExpression, ThrowStatement…) que le port ne fournit pas
        // encore. Restent vides ; à ajouter dès qu'un test concret les exige
        // (cf. consigne « vigilance BLOC 2 » de l'étape 4c).
        return TargetMethodAnalysis(
            signature = targetMethod,
            instanceCalls = instanceCalls,
            staticCalls = staticCalls,
            body = body
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLOC 3
    // ──────────────────────────────────────────────────────────────────────────

    private fun collectUsefulFields(
        introspector: CodeIntrospector,
        hierarchy: List<HierarchyLevel>,
        activeFields: Set<String>
    ): List<ClassField> {
        val collected = mutableListOf<ClassField>()
        hierarchy.forEach { level ->
            val cls = introspector.resolveClass(level.classFqn) ?: return@forEach
            introspector.listFields(cls).forEach { f ->
                val accessed = f.name in activeFields
                val injected = f.annotations.any { it in springInjectAnnotations }
                if (accessed || injected) collected += f
            }
        }
        return collected
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLOC 4 (§3.6)
    // ──────────────────────────────────────────────────────────────────────────

    private fun chooseConstructor(
        introspector: CodeIntrospector,
        sut: ClassDescriptor
    ): SelectedConstructor {
        val constructors = introspector.listMethods(sut).filter { it.name == "<init>" }
        if (constructors.isEmpty()) return SelectedConstructor(parameters = emptyList())

        constructors.firstOrNull { hasAnnotation(it, "org.springframework.beans.factory.annotation.Autowired") }
            ?.let { return it.toSelected("org.springframework.beans.factory.annotation.Autowired") }
        constructors.firstOrNull { hasAnnotation(it, "com.fasterxml.jackson.annotation.JsonCreator") }
            ?.let { return it.toSelected("com.fasterxml.jackson.annotation.JsonCreator") }
        constructors.firstOrNull { hasAnnotation(it, "java.beans.ConstructorProperties") }
            ?.let { return it.toSelected("java.beans.ConstructorProperties") }
        // Lombok @RequiredArgsConstructor / @AllArgsConstructor — sous-étape 4c+.

        if (constructors.size == 1) return constructors.single().toSelected(null)
        return constructors.maxBy { it.parameters.size }.toSelected(null)
    }

    private fun hasAnnotation(method: MethodSignature, fqn: String): Boolean =
        fqn in method.annotations

    private fun MethodSignature.toSelected(triggerAnnotation: String?): SelectedConstructor =
        SelectedConstructor(parameters = parameters, triggerAnnotation = triggerAnnotation)

    // ──────────────────────────────────────────────────────────────────────────
    // BLOC 5
    // ──────────────────────────────────────────────────────────────────────────

    private fun collectSettersAndPostConstruct(
        introspector: CodeIntrospector,
        hierarchy: List<HierarchyLevel>,
        usefulFields: List<ClassField>
    ): Pair<List<Setter>, List<String>> {
        val setters = mutableListOf<Setter>()
        val postConstruct = mutableListOf<String>()
        val usefulFieldNames = usefulFields.map { it.name }.toSet()

        hierarchy.forEach { level ->
            val cls = introspector.resolveClass(level.classFqn) ?: return@forEach
            introspector.listMethods(cls).forEach { m ->
                if (m.name == "<init>") return@forEach
                if (looksLikeSetter(m)) {
                    val targetFieldName = decapitalize(m.name.removePrefix("set"))
                    if (targetFieldName in usefulFieldNames) {
                        setters += Setter(m.name, targetFieldName, m.parameters.single().type)
                    }
                }
                if (m.annotations.any { it in POST_CONSTRUCT_FQNS }) {
                    postConstruct += m.name
                }
            }
        }
        return setters to postConstruct
    }

    private fun looksLikeSetter(method: MethodSignature): Boolean =
        method.name.startsWith("set") && method.name.length > 3 &&
            method.parameters.size == 1 && method.visibility == "public"

    private fun decapitalize(s: String): String =
        if (s.isEmpty()) s else s[0].lowercaseChar() + s.substring(1)

    // ──────────────────────────────────────────────────────────────────────────
    // BLOC 6 — orchestration des points d'entrée récursifs (§3.1 BLOC 6)
    // ──────────────────────────────────────────────────────────────────────────

    private fun recurseBlock6(
        ctx: RecursionContext,
        sut: ClassDescriptor,
        targetMethod: MethodSignature,
        ctor: SelectedConstructor,
        usefulFields: List<ClassField>,
        targetAnalysis: TargetMethodAnalysis,
        depth: Int
    ) {
        // 6a — dépendances de construction (constructeur ∪ champs utiles)
        ctor.parameters.forEach { p ->
            recurseDispatch(ctx, p.type.fqName, CallerContext.FIELD_OF_SUT,
                methodToCall = null, depth = depth + 1)
        }
        usefulFields.forEach { f ->
            recurseDispatch(ctx, f.type.fqName, CallerContext.FIELD_OF_SUT,
                methodToCall = null, depth = depth + 1)
        }

        // 6b — paramètres de methodeCible
        targetMethod.parameters.forEach { p ->
            recurseDispatch(ctx, p.type.fqName, CallerContext.PARAM_OF_METHOD,
                methodToCall = null, depth = depth + 1)
        }

        // 6c — appels d'instance (split intra-SUT vs externe)
        targetAnalysis.instanceCalls.forEach { call ->
            val calledMethod = findMethodIn(ctx, call.targetType, call.methodName, call.argTypes)
            if (call.targetType in ctx.sutHierarchyFqns) {
                if (calledMethod != null) {
                    recurseInternalLogic(ctx, call.targetType, calledMethod, depth + 1)
                }
            } else {
                recurseDispatch(ctx, call.targetType, CallerContext.CALL_TARGET,
                    methodToCall = calledMethod, depth = depth + 1)
            }
        }

        // 6c-bis — appels statiques utilisateur
        targetAnalysis.staticCalls.forEach { sc ->
            val calledMethod = findMethodIn(ctx, sc.classFqn, sc.methodName, sc.argTypes) ?: return@forEach
            // Filtre system : on ne loggue pas java.* / kotlin.* / etc.
            if (isUserStatic(sc.classFqn)) {
                ctx.staticCalls += StaticCallInfo(sc.classFqn, sc.methodName, calledMethod)
            }
        }

        // 6d — instanciations : différé (extension PSI dédiée requise — voir BLOC 2 note).
        // Aucun test 4c ne l'exige aujourd'hui.

        // 6e — type de retour de methodeCible
        recurseDispatch(ctx, targetMethod.returnType.fqName, CallerContext.RETURN_OF_METHOD,
            methodToCall = null, depth = depth + 1)
        targetMethod.returnType.typeArgs.forEach { arg ->
            recurseDispatch(ctx, arg.fqName, CallerContext.GENERIC_ARG,
                methodToCall = null, depth = depth + 1)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Dispatcher : classify puis aiguille vers le bon recurse()
    // ──────────────────────────────────────────────────────────────────────────

    private fun recurseDispatch(
        ctx: RecursionContext,
        classFqn: String,
        callerContext: CallerContext,
        methodToCall: MethodSignature?,
        depth: Int
    ) {
        if (depth > ctx.budget.maxDepth) {
            ctx.truncationReasons += "maxDepth dépassée à profondeur=$depth (cls=$classFqn)"
            return
        }
        val descriptor = ctx.introspector.resolveClass(classFqn)
        val type = descriptor?.let {
            com.contextextractor.core.extractor.ResolvedType(
                rawType = it.simpleName, fqName = it.fqn
            )
        } ?: com.contextextractor.core.extractor.ResolvedType(
            rawType = classFqn.substringAfterLast('.'), fqName = classFqn
        )
        val descriptorMethods = if (descriptor != null) ctx.introspector.listMethods(descriptor)
        else emptyList()
        val mode = ctx.classifier.classify(
            type, descriptor, callerContext, ctx.sutHierarchyFqns, descriptorMethods
        )
        when (mode) {
            ExtractionMode.MOCK_EXTERNAL -> recurseMockExternal(ctx, classFqn, descriptor, methodToCall, depth)
            ExtractionMode.DATA_STRUCTURE -> recurseDataStructure(ctx, classFqn, depth)
            ExtractionMode.INTERNAL_LOGIC -> {
                // Survenu en 6e ou 6a si une méthode SUT-hiérarchie est passée comme
                // type retour ou champ — récurer si on a un methodToCall valide.
                if (methodToCall != null) recurseInternalLogic(ctx, classFqn, methodToCall, depth)
            }
            ExtractionMode.SYSTEM_IGNORE,
            ExtractionMode.SUT_BOOTSTRAP,
            ExtractionMode.FUNCTIONAL_LAMBDA,
            ExtractionMode.CONTAINER,
            ExtractionMode.COLLECTION,
            ExtractionMode.STATIC_UTILITY -> {
                // SYSTEM/SUT/SAM/CONTAINER/COLLECTION/STATIC : pas de récursion
                // côté 4c. CONTAINER/COLLECTION : la récursion sur typeArgs est
                // déjà faite par BLOC 6e. SUT_BOOTSTRAP en plein milieu = cycle
                // (la racine est marquée visitée avant BLOC 6).
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // §3.2 — INTERNAL_LOGIC
    // ──────────────────────────────────────────────────────────────────────────

    private fun recurseInternalLogic(
        ctx: RecursionContext,
        classFqn: String,
        method: MethodSignature,
        depth: Int
    ) {
        if (depth > ctx.budget.maxDepth) {
            ctx.truncationReasons += "maxDepth dépassée — INTERNAL_LOGIC $classFqn#${method.canonical()}"
            return
        }
        val key = VisitKey(ExtractionMode.INTERNAL_LOGIC, classFqn, method.canonical())
        if (!ctx.visits.isNew(key)) return
        if (ctx.internalLogics.size >= ctx.budget.maxInternalLogicCount) {
            ctx.truncationReasons += "maxInternalLogicCount atteint — drop $classFqn#${method.name}"
            return
        }

        val calls = ctx.introspector.listMethodCalls(method)
        val callSummaries = calls.map { "${it.targetType}#${it.methodName}(${it.argTypes.joinToString(",")})" }
        // §3.2 ligne 362 : « Corps = AST(Methode) ». On capture le texte source
        // pour rendu dans la layer CONTEXT. Frontière intra-SUT (cette méthode
        // n'est appelée que pour des classes de la hiérarchie du SUT).
        val body = ctx.introspector.readMethodBody(method)

        val logicKey = "$classFqn#${method.canonical()}"
        ctx.internalLogics[logicKey] = InternalLogic(
            signature = method,
            callSummaries = callSummaries,
            // thrownExceptions / caughtExceptions : extension port nécessaire.
            body = body
        )

        // Récursion : ré-appliquer BLOC 6c sur les appels de ce corps.
        calls.forEach { call ->
            val calledMethod = findMethodIn(ctx, call.targetType, call.methodName, call.argTypes)
            if (call.isStatic) {
                if (calledMethod != null && isUserStatic(call.targetType)) {
                    ctx.staticCalls += StaticCallInfo(call.targetType, call.methodName, calledMethod)
                }
                return@forEach
            }
            if (call.targetType in ctx.sutHierarchyFqns) {
                if (calledMethod != null) recurseInternalLogic(ctx, call.targetType, calledMethod, depth + 1)
            } else {
                recurseDispatch(ctx, call.targetType, CallerContext.CALL_TARGET, calledMethod, depth + 1)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // §3.3 — MOCK_EXTERNAL
    // ──────────────────────────────────────────────────────────────────────────

    private fun recurseMockExternal(
        ctx: RecursionContext,
        classFqn: String,
        descriptor: ClassDescriptor?,
        methodToCall: MethodSignature?,
        depth: Int
    ) {
        val key = VisitKey(ExtractionMode.MOCK_EXTERNAL, classFqn, methodToCall?.canonical())
        if (!ctx.visits.isNew(key)) return
        if (ctx.mocks.size >= ctx.budget.maxMockCount && classFqn !in ctx.mocks) {
            ctx.truncationReasons += "maxMockCount atteint — drop mock $classFqn"
            return
        }

        // Phase 1 : type pour le mock = type déclaré (à 4c on prend la classe
        // elle-même ; un retrouverTypeDeclareDansSUT() plus fin viendra avec
        // l'extension d'introspection des champs/params en 4e).
        val builder = ctx.mocks.getOrPut(classFqn) {
            MockBuilder(
                concreteClass = classFqn,
                declaredType = classFqn,
                classAnnotations = descriptor?.annotations.orEmpty()
            )
        }

        // Phase 2 : signature appelée
        if (methodToCall != null && builder.signatures.none { it.canonical() == methodToCall.canonical() }) {
            builder.signatures += methodToCall
        }

        // Phase 3 : récursion sur le type de retour pour stubbing
        if (methodToCall != null) {
            val rt = methodToCall.returnType
            recurseDispatch(ctx, rt.fqName, CallerContext.RETURN_OF_METHOD,
                methodToCall = null, depth = depth + 1)
            rt.typeArgs.forEach { arg ->
                recurseDispatch(ctx, arg.fqName, CallerContext.GENERIC_ARG,
                    methodToCall = null, depth = depth + 1)
            }
            // Marqueur retour-imbriqué : si le type de retour finit en MOCK_EXTERNAL.
            val retDescriptor = ctx.introspector.resolveClass(rt.fqName)
            val retMethods = retDescriptor?.let { ctx.introspector.listMethods(it) }.orEmpty()
            val retType = com.contextextractor.core.extractor.ResolvedType(
                rawType = rt.rawType, fqName = rt.fqName
            )
            val retMode = ctx.classifier.classify(
                retType, retDescriptor, CallerContext.RETURN_OF_METHOD,
                ctx.sutHierarchyFqns, retMethods
            )
            if (retMode == ExtractionMode.MOCK_EXTERNAL) builder.returnIsNestedMock = true
        }

        // STOP : §3.3 interdit la lecture du corps des méthodes externes.
    }

    // ──────────────────────────────────────────────────────────────────────────
    // §3.4 — DATA_STRUCTURE complet (sous-étape 4d)
    // ──────────────────────────────────────────────────────────────────────────
    //
    // 7 patterns détectés en ordre strict (§3.4 Phase 1) :
    //   RECORD → BUILDER → CONSTRUCTOR (Lombok @Value) → ENUM → SEALED →
    //   BUILDER (méthode statique builder()) → STATIC_FACTORY → CONSTRUCTOR
    //   (@JsonCreator) → BUILDER ou CONSTRUCTOR (Lombok @Data/@AllArgsConstructor) →
    //   CONSTRUCTOR (constructeur public avec params) → SETTER_BASED.
    //
    // Phase 2 capture par pattern. Phase 3 collecte les champs (sauf RECORD/
    // ENUM/SEALED — leurs « champs » sont les composants/constantes/sous-classes
    // capturés en Phase 2). Phase 4 récure sur les champs complexes et les
    // type-args génériques.

    private fun recurseDataStructure(
        ctx: RecursionContext,
        classFqn: String,
        depth: Int
    ) {
        val key = VisitKey(ExtractionMode.DATA_STRUCTURE, classFqn, null)
        if (!ctx.visits.isNew(key)) return
        if (ctx.dataStructures.size >= ctx.budget.maxDtoCount) {
            ctx.truncationReasons += "maxDtoCount atteint — drop DTO $classFqn"
            return
        }
        if (depth > ctx.budget.maxDepth) {
            ctx.truncationReasons += "maxDepth dépassée — DATA_STRUCTURE $classFqn"
            return
        }

        val descriptor = ctx.introspector.resolveClass(classFqn)
        if (descriptor == null) {
            // §8bis.1 — type non résolvable. On enregistre une entrée stub
            // (pattern SETTER_BASED par défaut, vide) pour signaler la rencontre
            // sans tenter d'introspection.
            ctx.dataStructures[classFqn] = DataStructureInfo(
                fqName = classFqn,
                pattern = ConstructionPattern.SETTER_BASED
            )
            ctx.truncationReasons += "DTO non résolvable: $classFqn"
            return
        }

        val methods = ctx.introspector.listMethods(descriptor)
        val pattern = detectPattern(descriptor, methods)

        // Phase 2 — capture par pattern.
        val phase2 = capturePhase2(ctx, descriptor, methods, pattern)

        // Phase 3 — champs (sauf RECORD/ENUM/SEALED).
        val phase3Fields = if (pattern in PHASE3_SKIP) emptyList()
        else capturePhase3Fields(ctx, descriptor)

        // Pour RECORD : les composants viennent du constructeur canonique
        // capturé en Phase 2 (phase2.fields).
        val allFields = phase2.fields + phase3Fields

        ctx.dataStructures[classFqn] = DataStructureInfo(
            fqName = classFqn,
            pattern = pattern,
            fields = allFields,
            builderInfo = phase2.builderInfo,
            factoryMethods = phase2.factoryMethods,
            enumValues = phase2.enumValues,
            sealedSubs = phase2.sealedSubs
        )

        // Phase 4 — récursion.
        when (pattern) {
            ConstructionPattern.ENUM -> {
                // Pas de Phase 4 sur les constantes : ENUM est un point d'arrêt.
            }
            ConstructionPattern.SEALED -> {
                phase2.sealedSubs.forEach { subFqn ->
                    recurseDataStructure(ctx, subFqn, depth + 1)
                }
            }
            else -> {
                allFields.forEach { f -> recurseFieldType(ctx, f.type, depth + 1) }
            }
        }
    }

    // -- Phase 1 : détection du pattern (§3.4) --------------------------------
    //
    // ATTENTION : l'ordre est SIGNIFICATIF. Toute modification d'ordre change la
    // sémantique. En particulier @Builder est testé AVANT toute règle de
    // constructeur (y compris @JsonCreator) — c'est la règle de §3.4 et c'est
    // le test « ambiguïté @Builder + @JsonCreator » qui le verrouille.

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
            // @Builder déjà capté plus haut ; reste @Data / @AllArgsConstructor
            // sans @Builder → CONSTRUCTOR.
            return ConstructionPattern.CONSTRUCTOR
        }
        if (methods.any { it.name == "<init>" && it.visibility == "public" && it.parameters.isNotEmpty() }) {
            return ConstructionPattern.CONSTRUCTOR
        }
        return ConstructionPattern.SETTER_BASED
    }

    // -- Phase 2 : capture par pattern ----------------------------------------

    private data class Phase2Result(
        val fields: List<DataField> = emptyList(),
        val builderInfo: BuilderInfo? = null,
        val factoryMethods: List<FactoryMethodInfo> = emptyList(),
        val enumValues: List<String> = emptyList(),
        val sealedSubs: List<String> = emptyList()
    )

    private fun capturePhase2(
        ctx: RecursionContext,
        descriptor: ClassDescriptor,
        methods: List<MethodSignature>,
        pattern: ConstructionPattern
    ): Phase2Result = when (pattern) {
        ConstructionPattern.RECORD -> {
            // Composants = paramètres du constructeur canonique. PSI Java les
            // expose aussi via methods normaux ; ici on prend le constructeur
            // avec le plus de paramètres (typiquement le canonique).
            val canonical = methods.filter { it.name == "<init>" }.maxByOrNull { it.parameters.size }
            val fields = canonical?.parameters?.map { p ->
                DataField(name = p.name, type = p.type, required = !p.type.nullable)
            }.orEmpty()
            Phase2Result(fields = fields)
        }
        ConstructionPattern.BUILDER -> {
            // Builder Lombok : pas de classe builder explicite côté caller —
            // on capture les setters fluents conventionnellement nommés `champ(value)`.
            // Builder explicite (méthode statique builder()) : on suit le type
            // retour de builder() pour résoudre la classe builder.
            val builderInfo = collectBuilderInfo(ctx, descriptor, methods)
            Phase2Result(builderInfo = builderInfo)
        }
        ConstructionPattern.CONSTRUCTOR -> {
            // Le constructeur sélectionné dictera l'ordre de construction côté
            // prompt (utilisé en 4f via selectCtor). Côté `fields`, c'est Phase 3
            // qui fait foi : les champs de classe sont la source de vérité, ne
            // PAS dupliquer les paramètres du constructeur dans `fields`.
            Phase2Result()
        }
        ConstructionPattern.SETTER_BASED -> {
            // Phase 2 : capturer les setters publics. Les champs viendront via Phase 3.
            // (Ici on ne stocke pas les setters comme DataField — ils sont
            // implicitement déduits des champs en Phase 3.)
            Phase2Result()
        }
        ConstructionPattern.ENUM -> {
            Phase2Result(enumValues = descriptor.enumValues)
        }
        ConstructionPattern.SEALED -> {
            Phase2Result(sealedSubs = descriptor.permittedSubclasses)
        }
        ConstructionPattern.STATIC_FACTORY -> {
            val factories = methods
                .filter { it.isStatic && it.name in FACTORY_METHOD_NAMES }
                .map {
                    FactoryMethodInfo(
                        name = it.name,
                        parameters = it.parameters,
                        returnType = it.returnType
                    )
                }
            Phase2Result(factoryMethods = factories)
        }
    }

    private fun collectBuilderInfo(
        ctx: RecursionContext,
        descriptor: ClassDescriptor,
        methods: List<MethodSignature>
    ): BuilderInfo {
        // Cas 1 : méthode statique `builder()` qui retourne une classe builder
        // dédiée — on suit le type de retour pour énumérer les setters fluents.
        val builderStatic = methods.firstOrNull {
            it.isStatic && it.name == "builder" && it.parameters.isEmpty()
        }
        if (builderStatic != null) {
            val builderClassFqn = builderStatic.returnType.fqName
            val builderClass = ctx.introspector.resolveClass(builderClassFqn)
            if (builderClass != null) {
                val builderMethods = ctx.introspector.listMethods(builderClass)
                val setters = builderMethods
                    .filter { it.parameters.size == 1 && it.name != "build" && it.visibility == "public" }
                    .map { m ->
                        BuilderMethodInfo(
                            name = m.name,
                            field = m.name,
                            type = m.parameters.single().type,
                            required = false
                        )
                    }
                return BuilderInfo(builderClass = builderClassFqn, methods = setters)
            }
        }
        // Cas 2 : @lombok.Builder sans builder() statique visible côté fixture —
        // on synthétise un BuilderInfo à partir des champs déclarés.
        val classFields = ctx.introspector.listFields(descriptor)
        val synth = classFields.map { f ->
            BuilderMethodInfo(name = f.name, field = f.name, type = f.type, required = false)
        }
        return BuilderInfo(builderClass = "${descriptor.fqn}.${descriptor.simpleName}Builder", methods = synth)
    }

    // -- Phase 3 : capture des champs (sauf RECORD/ENUM/SEALED) ---------------

    private fun capturePhase3Fields(
        ctx: RecursionContext,
        descriptor: ClassDescriptor
    ): List<DataField> {
        // Champs déclarés sur la classe + super-classes utilisateur (Object exclu
        // par listSuperClasses).
        val sources = mutableListOf<ClassField>()
        sources += ctx.introspector.listFields(descriptor)
        ctx.introspector.listSuperClasses(descriptor).forEach { sup ->
            sources += ctx.introspector.listFields(sup)
        }
        return sources.map { cf ->
            DataField(
                name = cf.name,
                type = cf.type,
                validationAnnotations = cf.annotations.filter { it in VALIDATION_ANNOTATIONS },
                required = cf.annotations.any { it in REQUIRED_VALIDATION }
            )
        }
    }

    // -- Phase 4 : récursion sur un type de champ -----------------------------

    private fun recurseFieldType(
        ctx: RecursionContext,
        type: com.contextextractor.core.extractor.ResolvedType,
        depth: Int
    ) {
        if (depth > ctx.budget.maxDepth) {
            ctx.truncationReasons += "maxDepth dépassée — DTO field type ${type.fqName}"
            return
        }
        // Mode courant via le classifier en contexte FIELD_OF_DTO.
        val descriptor = ctx.introspector.resolveClass(type.fqName)
        val descriptorMethods = if (descriptor != null) ctx.introspector.listMethods(descriptor)
        else emptyList()
        val mode = ctx.classifier.classify(
            type, descriptor, CallerContext.FIELD_OF_DTO,
            ctx.sutHierarchyFqns, descriptorMethods
        )
        when (mode) {
            ExtractionMode.DATA_STRUCTURE -> recurseDataStructure(ctx, type.fqName, depth + 1)
            ExtractionMode.COLLECTION,
            ExtractionMode.CONTAINER -> {
                // §3.4 Phase 4 : pour collections/containers on ne récure pas
                // sur le type brut (qui est java.*) mais sur chaque type-arg.
                type.typeArgs.forEach { arg ->
                    val argDescriptor = ctx.introspector.resolveClass(arg.fqName)
                    val argMethods = if (argDescriptor != null) ctx.introspector.listMethods(argDescriptor)
                    else emptyList()
                    val argMode = ctx.classifier.classify(
                        arg, argDescriptor, CallerContext.GENERIC_ARG,
                        ctx.sutHierarchyFqns, argMethods
                    )
                    if (argMode == ExtractionMode.DATA_STRUCTURE) {
                        recurseDataStructure(ctx, arg.fqName, depth + 1)
                    }
                }
            }
            else -> {
                // SYSTEM_IGNORE / FUNCTIONAL_LAMBDA / MOCK_EXTERNAL / INTERNAL_LOGIC :
                // Phase 4 ne creuse pas (§3.4 ne mentionne que DATA_STRUCTURE et
                // COLLECTION/CONTAINER). Un MOCK_EXTERNAL apparaissant en
                // FIELD_OF_DTO indique une violation d'immuabilité — on l'ignore
                // côté DTO, le rendu décidera quoi en faire à la sous-étape 4f.
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun findMethodIn(
        ctx: RecursionContext,
        classFqn: String,
        methodName: String,
        argTypeFqns: List<String>
    ): MethodSignature? {
        val cls = ctx.introspector.resolveClass(classFqn) ?: return null
        val candidates = ctx.introspector.listMethods(cls).filter { it.name == methodName }
        if (candidates.isEmpty()) return null
        // Match strict sur les FQN d'arguments si fournis ; sinon premier match.
        val exact = candidates.firstOrNull { sig ->
            sig.parameters.map { it.type.fqName } == argTypeFqns
        }
        return exact ?: candidates.first()
    }

    private fun isUserStatic(classFqn: String): Boolean =
        SYSTEM_STATIC_PREFIXES.none { classFqn.startsWith(it) }

    // ──────────────────────────────────────────────────────────────────────────
    // Résolution du curseur
    // ──────────────────────────────────────────────────────────────────────────

    private fun resolveCursor(input: StrategyInput): Pair<ClassDescriptor, MethodSignature> {
        val cursor = input.cursor
        val symbol = input.introspector.resolveSymbolAt(cursor.file, cursor.offset)
            ?: error("Aucun symbole résolu à la position du curseur (${cursor.file.path}:${cursor.offset})")
        if (symbol.kind != SymbolKind.METHOD) {
            error("Le curseur doit être positionné sur une méthode (kind=${symbol.kind})")
        }
        val targetMethod = input.introspector.findEnclosingMethod(symbol)
            ?: error("Aucune méthode englobante trouvée pour le symbole ${symbol.id}")
        val sutFqn = symbol.fqn.substringBefore('#')
        val sut = input.introspector.resolveClass(sutFqn)
            ?: error("Classe SUT introuvable : $sutFqn")
        return sut to targetMethod
    }

    // ──────────────────────────────────────────────────────────────────────────
    // État interne de la récursion
    // ──────────────────────────────────────────────────────────────────────────

    private class RecursionContext(
        val introspector: CodeIntrospector,
        val classifier: ClassClassifier,
        val budget: Budget,
        val sutHierarchyFqns: Set<String>,
        @Suppress("unused") val targetMethod: MethodSignature,
        val visits: VisitRegistry = VisitRegistry(),
        val mocks: LinkedHashMap<String, MockBuilder> = LinkedHashMap(),
        val internalLogics: LinkedHashMap<String, InternalLogic> = LinkedHashMap(),
        val dataStructures: LinkedHashMap<String, DataStructureInfo> = LinkedHashMap(),
        val staticCalls: MutableList<StaticCallInfo> = mutableListOf(),
        val truncationReasons: MutableList<String> = mutableListOf()
    )

    private class MockBuilder(
        val concreteClass: String,
        var declaredType: String,
        var classAnnotations: List<String>,
        val signatures: MutableList<MethodSignature> = mutableListOf(),
        var returnIsNestedMock: Boolean = false
    ) {
        fun build(): MockInfo = MockInfo(
            concreteClass = concreteClass,
            declaredType = declaredType,
            classAnnotations = classAnnotations,
            requiredSignatures = signatures.toList(),
            returnIsNestedMock = returnIsNestedMock
        )
    }

    companion object {
        private val SYSTEM_STATIC_PREFIXES = listOf(
            "java.", "javax.", "jakarta.", "kotlin.", "scala.", "sun.", "com.sun."
        )

        // Patterns sans Phase 3 — leurs « champs » sont les composants/constantes/
        // sous-classes capturés en Phase 2.
        private val PHASE3_SKIP = setOf(
            ConstructionPattern.RECORD,
            ConstructionPattern.ENUM,
            ConstructionPattern.SEALED
        )

        // Annotations Lombok détectées par detectPattern() — leur ordre dans
        // detectPattern() reflète la priorité de la spec, pas l'ordre de ce set.
        private const val LOMBOK_BUILDER_FQN = "lombok.Builder"
        private const val LOMBOK_VALUE_FQN = "lombok.Value"
        private const val LOMBOK_DATA_FQN = "lombok.Data"
        private const val LOMBOK_ALL_ARGS_FQN = "lombok.AllArgsConstructor"
        private const val JSON_CREATOR_FQN = "com.fasterxml.jackson.annotation.JsonCreator"

        // Noms conventionnels de méthodes de fabrique (§3.4 « Si ∃ méthode
        // statique of(...) / from(...) → STATIC_FACTORY »). On reste strict :
        // une factory doit s'appeler exactement comme un de ces noms — éviter
        // les faux positifs sur des helpers métier nommés `create*`.
        private val FACTORY_METHOD_NAMES = setOf("of", "from", "valueOf")

        // Annotations de validation JSR-380 capturées en Phase 3 — listing
        // restreint à ce qui sert au prompt (§3.4 ligne « ∩ {…} »).
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

        // Sous-ensemble de VALIDATION_ANNOTATIONS qui implique l'obligation
        // (required=true). @Size/@Min/@Max contraignent mais n'imposent pas
        // la présence — donc exclus.
        private val REQUIRED_VALIDATION = setOf(
            "jakarta.validation.constraints.NotNull",
            "jakarta.validation.constraints.NotBlank",
            "javax.validation.constraints.NotNull",
            "javax.validation.constraints.NotBlank"
        )
    }
}

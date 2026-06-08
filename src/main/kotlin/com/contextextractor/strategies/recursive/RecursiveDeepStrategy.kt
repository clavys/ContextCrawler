package com.contextextractor.strategies.recursive

import com.contextextractor.core.classifier.ClassClassifier
import com.contextextractor.core.classifier.ContextAwareClassifier
import com.contextextractor.core.classifier.DefaultContextAwareClassifier
import com.contextextractor.core.classifier.ExtractionMode
import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.MethodBodyAnalysis
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.extractor.SymbolKind
import com.contextextractor.core.model.CaughtException
import com.contextextractor.core.model.ConditionalBranch
import com.contextextractor.core.model.ContextResult
import com.contextextractor.core.model.ContextTree
import com.contextextractor.core.model.FieldInitProtocol
import com.contextextractor.core.model.HierarchyLevel
import com.contextextractor.core.model.InstanceCall
import com.contextextractor.core.model.InstantiationPlan
import com.contextextractor.core.model.InternalLogic
import com.contextextractor.core.model.MockInfo
import com.contextextractor.core.model.SelectedConstructor
import com.contextextractor.core.model.Setter
import com.contextextractor.core.model.StaticCall
import com.contextextractor.core.model.TargetMethodAnalysis
import com.contextextractor.core.model.TestabilityDiagnostic
import com.contextextractor.core.model.ThrownException
import com.contextextractor.core.model.init.InitStrategy
import com.contextextractor.core.model.init.MethodKey
import com.contextextractor.core.model.init.POST_CONSTRUCT_FQNS
import com.contextextractor.core.strategy.Budget
import com.contextextractor.core.strategy.ContextStrategy
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.core.strategy.StrategyInput
import com.contextextractor.strategies.recursive.initbloc.CallGraphBuilder
import com.contextextractor.strategies.recursive.initbloc.EntryPointFinder
import com.contextextractor.strategies.recursive.initbloc.SourceCollector
import com.contextextractor.strategies.recursive.initbloc.StrategySelector
import com.contextextractor.strategies.recursive.initbloc.ordinalPriority
import com.contextextractor.strategies.recursive.refs.ReferenceGraphBuilder
import com.contextextractor.strategies.recursive.refs.ResultMaterializer

// Stratégie récursive principale — STRATEGIE.md §3 et §4 (V1.2).
//
// **Refactor V1.2** — cf RAPPORT_CONTEXT §9. Le BLOC 6 V1.1 (single-pass
// eager avec promotions/évictions à l'intérieur de `recurseBlock6`) est
// remplacé par un pipeline 2-pass differred classification :
//
//   PASSE 1 — ReferenceGraphBuilder      (énumère sans classifier)
//   PASSE 2 — ContextAwareClassifier     (décide avec contexte complet)
//   PASSE 3 — ResultMaterializer         (transforme en MockInfo/DTO/etc.)
//
// Les BLOCs 1-5 et 7 sont inchangés.
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
        // Le paramètre `classifier` (port V1.1) reste exposé pour rétro-
        // compatibilité de la signature publique mais N'EST PLUS CONSULTÉ — la
        // V1.2 instancie son propre `DefaultContextAwareClassifier`.
        @Suppress("UNUSED_PARAMETER", "unused") val unused = classifier

        // ── BLOC 1 : HIÉRARCHIE COMPLÈTE ──────────────────────────────────────
        val hierarchy = buildHierarchy(introspector, sut)
        val hierarchyFqns = hierarchy.map { it.classFqn }.toSet()

        // ── BLOC 2 : ANALYSE DE LA MÉTHODE CIBLE ──────────────────────────────
        val targetCalls = introspector.listMethodCalls(targetMethod)
        val targetBody = introspector.readMethodBody(targetMethod)
        val targetBodyAnalysis = introspector.analyzeMethodBody(targetMethod)
        val targetAnalysis = analyzeTargetMethod(
            targetMethod, targetCalls, targetBody, targetBodyAnalysis
        )

        // ── BLOC 3 : INVENTAIRE DES CHAMPS UTILES ─────────────────────────────
        // Bug T body-scan + Bug P trivial getter resolution restent nécessaires
        // côté champ — ce sont des helpers PSI-coping, pas des patches de
        // classification, donc préservés en V1.2.
        val directFieldAccesses = introspector.listFieldAccesses(targetMethod)
            .map { it.fieldName }.toSet()
        val trivialGetterFieldNames = resolveTrivialGetterFieldNames(
            introspector, hierarchyFqns, targetCalls
        )
        val allHierarchyFieldNames: Set<String> = hierarchy.flatMap { level ->
            val cls = introspector.resolveClass(level.classFqn) ?: return@flatMap emptyList()
            introspector.listFields(cls).map { it.name }
        }.toSet()
        val bodyMentionedFieldNames: Set<String> = allHierarchyFieldNames.filter { name ->
            val esc = Regex.escape(name)
            Regex("(?<!\\w)this\\.$esc(?!\\w)").containsMatchIn(targetBody) ||
                Regex("(?<!\\w)$esc\\s*\\.").containsMatchIn(targetBody)
        }.toSet()
        val activeFields = directFieldAccesses + trivialGetterFieldNames + bodyMentionedFieldNames
        val usefulFields = collectUsefulFields(introspector, hierarchy, activeFields)

        // ── BLOC 4 : PROTOCOLE DE CONSTRUCTION DU SUT ─────────────────────────
        val selectedConstructor = chooseConstructor(introspector, sut)

        // ── BLOC 5 : LEVIERS DE MUTATION ──────────────────────────────────────
        val (setters, postConstruct) = collectSettersAndPostConstruct(
            introspector, hierarchy, usefulFields
        )

        // ── PASSE 1 V1.2 : Reference Graph (remplace BLOC 6) ──────────────────
        // Crawl exhaustif, borné par `budget.maxDepth` seul. Pas de cap de
        // résultat. cf RAPPORT_CONTEXT §9 défaut #3.
        val graph = ReferenceGraphBuilder(
            introspector = introspector,
            frameworkPrefixes = config.frameworkPackagePrefixes,
            maxCrawlDepth = config.budget.maxDepth
        ).build(sut, targetMethod, hierarchy, selectedConstructor)

        // ── PASSE 2 V1.2 : Classification context-aware ───────────────────────
        val contextClassifier: ContextAwareClassifier = DefaultContextAwareClassifier()
        val classifications: Map<String, ExtractionMode> =
            graph.allReferences().associate { ref ->
                val descriptor = ref.descriptor
                val methods = descriptor?.let { introspector.listMethods(it) } ?: emptyList()
                ref.fqn to contextClassifier.classify(
                    ref = ref,
                    hierarchyFqns = hierarchyFqns,
                    frameworkPrefixes = config.frameworkPackagePrefixes,
                    descriptorMethods = methods
                )
            }

        // ── PASSE 3 V1.2 : Matérialisation ────────────────────────────────────
        val materializer = ResultMaterializer(
            introspector = introspector,
            graph = graph,
            classifications = classifications,
            hierarchyFqns = hierarchyFqns
        )
        val rawMocks = materializer.materializeMocks()
        val rawDataStructures = materializer.materializeDataStructures()
        val rawInternalLogics = materializer.materializeInternalLogics()
        val staticCalls = materializer.materializeStaticCalls()

        // ── BLOC 7 : PROTOCOLE D'INITIALISATION DES CHAMPS (§4) ───────────────
        val rawBlock7 = runBlock7(
            introspector, config.budget, hierarchyFqns,
            usefulFields, selectedConstructor, targetMethod
        )
        // Bug P préservé — getter trivial intra-SUT → MOCKITO_INJECT_MOCKS pour
        // permettre la réconciliation correcte avec les mocks correspondants.
        val block7 = if (trivialGetterFieldNames.isEmpty()) rawBlock7
        else rawBlock7.copy(
            initProtocol = rawBlock7.initProtocol.mapValues { (name, proto) ->
                if (name in trivialGetterFieldNames &&
                    proto.recommendedStrategy !is InitStrategy.MOCKITO_INJECT_MOCKS) {
                    proto.copy(recommendedStrategy = InitStrategy.MOCKITO_INJECT_MOCKS)
                } else proto
            }
        )

        // ── Filtrage des fields par usage (préservé pour la clarté du prompt) ─
        // Un champ jamais utilisé avec strategy MOCKITO_INJECT_MOCKS n'a pas
        // d'intérêt dans le prompt — c'est un parasite @Autowired.
        val transitiveUsage = computeTransitiveFieldUsage(
            introspector, hierarchyFqns, targetMethod,
            block7.initProtocol, config.budget.maxGraphDepth,
            frameworkPrefixes = config.frameworkPackagePrefixes
        )
        val filteredFields = usefulFields.filter { f ->
            val strategy = block7.initProtocol[f.name]?.recommendedStrategy
            f.name in transitiveUsage ||
                f.name in bodyMentionedFieldNames ||  // Bug T fallback : body scan textuel
                strategy !is InitStrategy.MOCKITO_INJECT_MOCKS
        }
        val droppedFieldNames = (usefulFields - filteredFields).map { it.name }.toSet()
        val droppedFieldTypes = (usefulFields - filteredFields).map { it.type.fqName }.toSet()
        val filteredInitProtocol = block7.initProtocol.filterKeys { it !in droppedFieldNames }
        val filteredInitOrder = block7.initOrder.filter { it !in droppedFieldNames }
        val filteredDiagnostic = buildTestabilityDiagnostic(filteredInitProtocol)

        // Un mock dont le champ correspondant a été dropé par le filtre transitif
        // est lui aussi retiré du prompt — sinon le LLM voit des `@Mock` parasites
        // sans contexte d'usage.
        val mocksAfterFieldFilter = rawMocks.filterKeys { typeFqn ->
            if (typeFqn !in droppedFieldTypes) true
            else filteredFields.any { it.type.fqName == typeFqn }
        }

        // ── Réconciliation BLOC 7 ↔ mocks ─────────────────────────────────────
        // Si BLOC 7 dit qu'un champ s'auto-construit via CALL_PUBLIC_*, le type
        // du champ ne doit plus apparaître dans `mocks` — prompt non
        // contradictoire.
        val reconciledMocks = reconcileMocksWithInitProtocol(
            mocksAfterFieldFilter, filteredInitProtocol, filteredFields
        )

        // ── Enrichissement internalLogics avec downstream BLOC 7 ──────────────
        val enrichedInternalLogics = enrichInternalLogicsWithDownstream(
            introspector = introspector,
            hierarchyFqns = hierarchyFqns,
            initProtocol = filteredInitProtocol,
            existing = rawInternalLogics,
            frameworkPrefixes = config.frameworkPackagePrefixes
        )

        return ContextResult(
            sutFqName = sut.fqn,
            hierarchy = hierarchy,
            fields = filteredFields,
            instantiationPlan = InstantiationPlan(
                selectedConstructor = selectedConstructor,
                setters = setters,
                postConstruct = postConstruct
            ),
            targetMethod = targetAnalysis,
            internalLogics = enrichedInternalLogics,
            mocks = reconciledMocks,
            dataStructures = rawDataStructures,
            staticCalls = staticCalls,
            initProtocol = filteredInitProtocol,
            initOrder = filteredInitOrder,
            testabilityDiagnostic = filteredDiagnostic,
            intraSutCallGraph = block7.callGraphSerialized,
            truncated = graph.truncationReasons.isNotEmpty(),
            truncationReasons = graph.truncationReasons
        )
    }

    // Réconciliation BLOC 6 ↔ BLOC 7 sur les mocks. Pour chaque type T présent
    // dans `mocks` qui correspond au type d'au moins un champ de la SUT, T est
    // conservé UNIQUEMENT s'il existe au moins un champ de type T dont la
    // stratégie d'init est MOCKITO_INJECT_MOCKS. Sinon T est supprimé.
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

    // Synthèse de méthodes downstream BLOC 7 dans internalLogics. Les
    // `CALL_PUBLIC_TRANSITIVE.downstreamChain` contiennent des canonicals de
    // méthodes intra-SUT atteintes en aval depuis l'assignment site. Ces
    // méthodes sont rarement visitées en PASSE 1 (la méthode cible ne les
    // appelle pas) mais sont pertinentes pour le LLM.
    private fun enrichInternalLogicsWithDownstream(
        introspector: CodeIntrospector,
        hierarchyFqns: Set<String>,
        initProtocol: Map<String, FieldInitProtocol>,
        existing: Map<String, InternalLogic>,
        frameworkPrefixes: List<String> = emptyList()
    ): Map<String, InternalLogic> {
        val downstreamCanonicals = initProtocol.values
            .map { it.recommendedStrategy }
            .filterIsInstance<InitStrategy.CALL_PUBLIC_TRANSITIVE>()
            .flatMap { it.downstreamChain }
            .toSet()
        if (downstreamCanonicals.isEmpty()) return existing

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
            if (isTrivialGetter(introspector, sig)) continue
            val hits = detectFrameworkBoundary(introspector, sig, frameworkPrefixes, hierarchyFqns)
            if (hits.isNotEmpty()) {
                out[key] = InternalLogic(
                    signature = sig,
                    stubViaSpy = true,
                    frameworkPrefixesHit = hits
                )
                continue
            }
            val callSummaries = introspector.listMethodCalls(sig)
                .filter { !it.isStatic }
                .map { c -> "${c.targetType}.${c.methodName}" }
                .distinct()
            val body = introspector.readMethodBody(sig)
            val bodyAnalysis = introspector.analyzeMethodBody(sig)
            out[key] = InternalLogic(
                signature = sig,
                callSummaries = callSummaries,
                thrownExceptions = bodyAnalysis.thrownAsModel(),
                caughtExceptions = bodyAnalysis.caughtAsModel(),
                body = body
            )
        }
        return out
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLOC 7 — protocole d'initialisation (§4)
    // ──────────────────────────────────────────────────────────────────────────
    private fun runBlock7(
        introspector: CodeIntrospector,
        budget: Budget,
        hierarchyFqns: Set<String>,
        usefulFields: List<ClassField>,
        selectedConstructor: SelectedConstructor,
        targetMethod: MethodSignature
    ): Block7Result {
        val callGraph = CallGraphBuilder(introspector).build(hierarchyFqns)
        val targetOwner = findOwnerOfMethod(introspector, hierarchyFqns, targetMethod)
            ?: hierarchyFqns.first()
        val targetMethodKey = MethodKey.of(targetOwner, targetMethod)

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
            targetMethodKey = targetMethodKey,
            // V1.4 — branche 10bis : champs hérités accessibles → REFLECTION_INJECTION.
            // `sutFqn` est le owner du target en V1 (le SUT est la classe au curseur).
            sutFqn = targetOwner
        )

        // Bug CC — targetMethod transmis à SourceCollector pour qu'il l'exclue
        // des MethodInitializer candidates. Un champ écrit (mais jamais lu) par
        // target — typiquement un OUTPUT de target — ne doit pas voir target
        // sélectionné comme stratégie d'init (sinon le LLM appelle target dans
        // @BeforeEach et crashe en NotAMockException sur le @InjectMocks).
        val initProtocol = LinkedHashMap<String, FieldInitProtocol>()
        for (field in usefulFields) {
            val sources = collector.collect(field, selectedConstructor, targetMethod)
            val strategy = selector.choose(field, sources)
            initProtocol[field.name] = FieldInitProtocol(
                field = field,
                sources = sources,
                recommendedStrategy = strategy
            )
        }

        val initOrder = initProtocol.entries
            .sortedBy { it.value.recommendedStrategy.ordinalPriority() }
            .map { it.key }

        val diagnostic = buildTestabilityDiagnostic(initProtocol)

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
        body: String,
        bodyAnalysis: MethodBodyAnalysis
    ): TargetMethodAnalysis {
        val instanceCalls = calls.filterNot { it.isStatic }.map {
            InstanceCall(it.targetType, "", it.methodName, it.argTypes)
        }
        val staticCalls = calls.filter { it.isStatic }.map {
            StaticCall(it.targetType, it.methodName, it.argTypes)
        }
        return TargetMethodAnalysis(
            signature = targetMethod,
            instanceCalls = instanceCalls,
            staticCalls = staticCalls,
            instantiations = bodyAnalysis.instantiations,
            expectedLambdas = bodyAnalysis.expectedLambdas,
            thrownExceptions = bodyAnalysis.thrownAsModel(),
            caughtExceptions = bodyAnalysis.caughtAsModel(),
            conditionalBranches = bodyAnalysis.conditionalBranches.map {
                ConditionalBranch(it.kind, it.condition, it.constants)
            },
            nonDeterministicSources = bodyAnalysis.nonDeterministicSources,
            body = body
        )
    }

    private fun MethodBodyAnalysis.thrownAsModel(): List<ThrownException> =
        thrownExceptions.map { ThrownException(it.typeFqn, it.message) }

    private fun MethodBodyAnalysis.caughtAsModel(): List<CaughtException> =
        caughtExceptions.map { CaughtException(it.types, it.callsInCatch) }

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
        if (constructors.isEmpty()) {
            synthesizeLombokConstructor(introspector, sut)?.let { return it }
            return SelectedConstructor(parameters = emptyList())
        }

        constructors.firstOrNull { hasAnnotation(it, "org.springframework.beans.factory.annotation.Autowired") }
            ?.let { return it.toSelected("org.springframework.beans.factory.annotation.Autowired") }
        constructors.firstOrNull { hasAnnotation(it, "com.fasterxml.jackson.annotation.JsonCreator") }
            ?.let { return it.toSelected("com.fasterxml.jackson.annotation.JsonCreator") }
        constructors.firstOrNull { hasAnnotation(it, "java.beans.ConstructorProperties") }
            ?.let { return it.toSelected("java.beans.ConstructorProperties") }

        if (constructors.size == 1) return constructors.single().toSelected(null)
        return constructors.maxBy { it.parameters.size }.toSelected(null)
    }

    // §3.6 + §8bis.6 — Lombok @RequiredArgsConstructor / @AllArgsConstructor
    // synthétisés quand le plugin Lombok IntelliJ est inactif.
    private fun synthesizeLombokConstructor(
        introspector: CodeIntrospector,
        sut: ClassDescriptor
    ): SelectedConstructor? {
        val allArgs = LOMBOK_ALL_ARGS_FQN in sut.annotations
        val requiredArgs = LOMBOK_REQUIRED_ARGS_FQN in sut.annotations
        if (!allArgs && !requiredArgs) return null

        val fields = introspector.listFields(sut)
        val selected = if (allArgs) {
            fields
        } else {
            fields.filter { f ->
                (f.isFinal && f.initializerExpression == null) ||
                    f.annotations.any { it == LOMBOK_NON_NULL_FQN }
            }
        }
        return SelectedConstructor(
            parameters = selected.map { Parameter(name = it.name, type = it.type) },
            triggerAnnotation = if (allArgs) LOMBOK_ALL_ARGS_FQN else LOMBOK_REQUIRED_ARGS_FQN
        )
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
    // Défaut #1 — détection des frontières framework (§3.2bis STUB_VIA_SPY)
    // ──────────────────────────────────────────────────────────────────────────
    //
    // Une méthode intra-SUT est une frontière framework si elle appelle
    // directement (sans creuser) au moins une méthode d'une classe externe
    // dont le FQN matche un préfixe configuré dans `StrategyConfig.frameworkPackagePrefixes`.
    // Bug N — détection transitive : on remonte la chaîne intra-SUT pour
    // reporter les hits sur l'entrée de la chaîne (celle appelée par target).
    private fun detectFrameworkBoundary(
        introspector: CodeIntrospector,
        method: MethodSignature,
        frameworkPrefixes: List<String>,
        hierarchyFqns: Set<String> = emptySet(),
        maxDepth: Int = 8
    ): List<String> {
        if (frameworkPrefixes.isEmpty()) return emptyList()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<MethodSignature, Int>>()
        queue.add(method to 0)
        val hits = LinkedHashSet<String>()
        while (queue.isNotEmpty()) {
            val (m, d) = queue.removeFirst()
            if (!visited.add(m.canonical())) continue
            if (d > maxDepth) continue
            val calls = introspector.listMethodCalls(m)
            calls.forEach { c ->
                frameworkPrefixes.firstOrNull { p -> c.targetType.startsWith(p) }
                    ?.let { hits += it }
            }
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

    // ──────────────────────────────────────────────────────────────────────────
    // Défaut #4 — détection des getters triviaux
    // ──────────────────────────────────────────────────────────────────────────
    //
    // Un getter trivial = méthode dont le corps se résume à `return this.X;`
    // ou `return X;`. Heuristique : pas d'appel, pas de branche, pas
    // d'exception, pas d'instanciation, pas d'assignation, exactement 1 accès
    // en lecture. Pattern body strict : `return X;` ou `return this.X;`.
    private fun isTrivialGetter(
        introspector: CodeIntrospector,
        method: MethodSignature
    ): Boolean {
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
            val accepted = setOf(
                "return $name;",
                "return this.$name;"
            )
            if (body !in accepted) return false
        }
        return true
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Bug P — résolution des getters triviaux intra-SUT
    // ──────────────────────────────────────────────────────────────────────────
    //
    // Quand le corps de la méthode cible appelle `this.getX().y()`, et que
    // `getX()` est un getter trivial qui renvoie le champ `x`, le LLM doit
    // savoir que `x` est un champ utile du SUT (pour que `@InjectMocks` ou le
    // protocole d'init le câble).
    private fun resolveTrivialGetterFieldNames(
        introspector: CodeIntrospector,
        hierarchyFqns: Set<String>,
        calls: List<MethodCall>
    ): Set<String> {
        val out = mutableSetOf<String>()
        forEachTrivialGetterField(introspector, hierarchyFqns, calls) { fieldName, _ ->
            out += fieldName
        }
        return out
    }

    private fun forEachTrivialGetterField(
        introspector: CodeIntrospector,
        hierarchyFqns: Set<String>,
        calls: List<MethodCall>,
        accept: (String, ClassField) -> Unit
    ) {
        val fieldsByName: Map<String, ClassField> = hierarchyFqns
            .asSequence()
            .mapNotNull { introspector.resolveClass(it) }
            .flatMap { introspector.listFields(it).asSequence() }
            .associateBy { it.name }
        calls.forEach { call ->
            if (call.isStatic) return@forEach
            if (call.targetType !in hierarchyFqns) return@forEach
            val cls = introspector.resolveClass(call.targetType) ?: return@forEach
            val candidates = introspector.listMethods(cls).filter { it.name == call.methodName }
            val method = candidates.firstOrNull { sig ->
                sig.parameters.map { it.type.fqName } == call.argTypes
            } ?: candidates.firstOrNull() ?: return@forEach
            if (!isTrivialGetter(introspector, method)) return@forEach
            val access = introspector.listFieldAccesses(method).singleOrNull() ?: return@forEach
            val field = fieldsByName[access.fieldName] ?: return@forEach
            accept(access.fieldName, field)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Défaut #2 — usage transitif des champs
    // ──────────────────────────────────────────────────────────────────────────
    //
    // BFS limité par `maxGraphDepth` qui parcourt les méthodes intra-SUT
    // atteignables depuis la méthode cible OU depuis les entry points élus par
    // BLOC 7. À chaque méthode visitée, accumule les noms de champs accédés.
    // Frontière intra-SUT respectée : on ne descend que dans hierarchyFqns.
    // Bug E — détection DIRECTE (depth 0) intentionnelle : on n'utilise pas
    // hierarchyFqns dans detectFrameworkBoundary ici, sinon le BFS skip target
    // dès qu'un descendant intra-SUT descend dans le framework.
    private fun computeTransitiveFieldUsage(
        introspector: CodeIntrospector,
        hierarchyFqns: Set<String>,
        targetMethod: MethodSignature,
        initProtocol: Map<String, FieldInitProtocol>,
        maxGraphDepth: Int,
        frameworkPrefixes: List<String> = emptyList()
    ): Set<String> {
        val methodIndex: List<Pair<String, MethodSignature>> = hierarchyFqns.flatMap { fqn ->
            val cls = introspector.resolveClass(fqn) ?: return@flatMap emptyList()
            introspector.listMethods(cls).map { fqn to it }
        }

        val accessed = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<MethodSignature, Int>>()

        queue.add(targetMethod to 0)
        initProtocol.values.forEach { protocol ->
            collectInitEntryPoints(protocol.recommendedStrategy, methodIndex)
                .forEach { queue.add(it to 0) }
        }

        while (queue.isNotEmpty()) {
            val (method, depth) = queue.removeFirst()
            val canonical = method.canonical()
            if (!visited.add(canonical)) continue
            if (depth > maxGraphDepth) continue
            if (frameworkPrefixes.isNotEmpty() &&
                detectFrameworkBoundary(introspector, method, frameworkPrefixes).isNotEmpty()
            ) continue

            introspector.listFieldAccesses(method).forEach { accessed += it.fieldName }
            introspector.listFieldAssignments(method).forEach { accessed += it.fieldName }

            introspector.listMethodCalls(method).forEach { call ->
                if (call.targetType in hierarchyFqns) {
                    val next = methodIndex.firstOrNull { (owner, sig) ->
                        owner == call.targetType && sig.name == call.methodName &&
                            sig.parameters.map { it.type.fqName } == call.argTypes
                    } ?: methodIndex.firstOrNull { (owner, sig) ->
                        owner == call.targetType && sig.name == call.methodName
                    }
                    if (next != null) queue.add(next.second to depth + 1)
                }
            }
        }
        return accessed
    }

    private fun collectInitEntryPoints(
        strategy: InitStrategy,
        methodIndex: List<Pair<String, MethodSignature>>
    ): List<MethodSignature> = when (strategy) {
        is InitStrategy.CALL_POST_CONSTRUCT -> listOf(strategy.method)
        is InitStrategy.CALL_PUBLIC -> listOf(strategy.method)
        is InitStrategy.CALL_PUBLIC_WITH_STUBS -> listOf(strategy.method)
        is InitStrategy.CALL_PUBLIC_WITH_ARGS -> listOf(strategy.method)
        is InitStrategy.CALL_PUBLIC_TRANSITIVE -> {
            val downstream = strategy.downstreamChain.mapNotNull { canonical ->
                methodIndex.firstOrNull { (_, sig) -> sig.canonical() == canonical }?.second
            }
            listOf(strategy.entryPoint) + downstream
        }
        is InitStrategy.CALL_SAME_PACKAGE -> listOf(strategy.method)
        is InitStrategy.SETTER,
        InitStrategy.CONSTRUCTOR,
        InitStrategy.IMPLICIT,
        InitStrategy.IMPLICIT_VIA_CONSTRUCTOR,
        InitStrategy.MOCKITO_INJECT_MOCKS,
        is InitStrategy.UNTESTABLE_AS_IS -> emptyList()
    }

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

    companion object {
        // Annotations Lombok détectées par chooseConstructor() /
        // synthesizeLombokConstructor() — leur ordre dans le code reflète la
        // priorité de la spec, pas l'ordre de ce set.
        private const val LOMBOK_ALL_ARGS_FQN = "lombok.AllArgsConstructor"
        private const val LOMBOK_REQUIRED_ARGS_FQN = "lombok.RequiredArgsConstructor"
        private const val LOMBOK_NON_NULL_FQN = "lombok.NonNull"
    }
}

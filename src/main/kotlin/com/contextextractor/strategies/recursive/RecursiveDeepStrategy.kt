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
import com.contextextractor.core.extractor.MethodBodyAnalysis
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.extractor.SymbolKind
import com.contextextractor.core.model.BuilderInfo
import com.contextextractor.core.model.CaughtException
import com.contextextractor.core.model.ConditionalBranch
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
import com.contextextractor.core.model.ThrownException
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
        val targetBodyAnalysis = introspector.analyzeMethodBody(targetMethod)
        val targetAnalysis = analyzeTargetMethod(
            targetMethod, targetCalls, targetBody, targetBodyAnalysis
        )

        // ── BLOC 3 : INVENTAIRE DES CHAMPS UTILES ─────────────────────────────
        val directFieldAccesses = introspector.listFieldAccesses(targetMethod)
            .map { it.fieldName }.toSet()
        // Bug P — un appel à un getter trivial intra-SUT (ex : héritage
        // `BaseControleur#getUserSession()`) doit compter comme un accès au
        // champ retourné. Sinon le champ hérité non-@Autowired serait dropé
        // de `usefulFields`, et son type ne serait pas mocké correctement.
        val trivialGetterFieldNames = resolveTrivialGetterFieldNames(
            introspector, hierarchyFqns, targetCalls
        )
        // Bug T — filet textuel sur le corps de target. PSI rate parfois
        // l'accès `this.field` quand il sert de qualifieur dans une chaîne
        // imbriquée — cas concret production :
        //   this.supervisionDeltaVecModele.setAfficherResultats(true);
        //   this.supervisionDeltaVecModele.setLienExportCsvVisible(
        //       CollectionUtils.isNotEmpty(this.lignesResultatSupervisionDeltaVecDTO));
        // sont absents de `listFieldAccesses` selon le contexte. Sans ce filet,
        // `supervisionDeltaVecModele` n'entre pas dans `activeFields` →
        // disparaît de `usefulFields` → mock SupervisionDeltaVecModele jamais
        // émis → le LLM confond avec `tableauSupervisionDeltaVecModele` et
        // produit du code qui ne compile pas.
        // Le scan utilise la liste de TOUS les champs de la hiérarchie comme
        // dictionnaire, puis vérifie `this.<name>` ou `<name>.` dans le body.
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

        // ── BLOC 6 : POINTS D'ENTRÉE RÉCURSIFS ────────────────────────────────
        val ctx = RecursionContext(
            introspector = introspector,
            classifier = classifier,
            budget = config.budget,
            sutHierarchyFqns = hierarchyFqns,
            targetMethod = targetMethod,
            frameworkPrefixes = config.frameworkPackagePrefixes
        )
        // Bug I — types essentiels pré-amorcés depuis la signature de target.
        // Les paramètres doivent être instanciables ou mockables par le test
        // (le LLM les passe à `sut.method(...)`) — ils ne doivent JAMAIS être
        // dropés du budget des mocks.
        ctx.essentialMockTypes += targetMethod.parameters.map { it.type.fqName }
        ctx.essentialMockTypes += targetMethod.parameters.flatMap { it.type.typeArgs.map { ta -> ta.fqName } }
        // Bug M — chaque type sur lequel le corps de target appelle une méthode
        // d'instance est essentiel. Cas concret : `supervisionDeltaVecService.
        // preparerContexteDetailDeltaVec(...)` → SupervisionDeltaVecService est
        // essentiel et survit une éviction par 10 @Autowired moins importants.
        // Filtre `!in hierarchyFqns` : un appel `this.xxx()` ne compte pas (le
        // SUT n'a pas besoin d'être mocké, c'est l'objet sous test).
        ctx.essentialMockTypes += targetAnalysis.instanceCalls
            .map { it.targetType }
            .filterNot { it in hierarchyFqns }
        // Bug P — pour les chaînes `this.getXxx().doSomething()`, getXxx est
        // intra-SUT (donc filtré par Bug M) mais le type retourné par le
        // getter — le type du champ derrière — doit aussi être essentiel.
        ctx.essentialMockTypes += resolveTrivialGetterFieldTypes(
            introspector, hierarchyFqns, targetCalls
        )
        // Bug T — types des champs détectés par scan textuel du body de
        // target (cf. computation de `bodyMentionedFieldNames` plus haut).
        // Les marquer essentiels protège du filtre `filteredFields` (Bug S)
        // et de l'éviction par budget — même si le BFS PSI les rate.
        ctx.essentialMockTypes += usefulFields
            .filter { it.name in bodyMentionedFieldNames }
            .map { it.type.fqName }
        // Marquer la racine SUT_BOOTSTRAP visitée — évite une re-entrée si BLOC 6
        // déclenche un appel au SUT lui-même (cas pathologique mais possible).
        ctx.visits.add(VisitKey(ExtractionMode.SUT_BOOTSTRAP, sut.fqn, targetMethod.name))

        recurseBlock6(ctx, sut, targetMethod, selectedConstructor, usefulFields,
            targetAnalysis, depth = 0)

        // ── BLOC 7 : PROTOCOLE D'INITIALISATION DES CHAMPS (§4) ───────────────
        val rawBlock7 = runBlock7(
            introspector, config.budget, hierarchyFqns,
            usefulFields, selectedConstructor, targetMethod
        )
        // Bug P — un champ surfacé via getter trivial intra-SUT (cas
        // `BaseControleur#getUserSession()` retournant `userSession` sans
        // @Autowired) doit recevoir la stratégie MOCKITO_INJECT_MOCKS. C'est
        // ce que le LLM va utiliser : `@Mock SessionModel ...; @InjectMocks
        // SUT ...;`. Sans cette substitution, la réconciliation drop le mock
        // de type SessionModel car le champ a une stratégie SETTER/UNTESTABLE.
        val block7 = if (trivialGetterFieldNames.isEmpty()) rawBlock7
        else rawBlock7.copy(
            initProtocol = rawBlock7.initProtocol.mapValues { (name, proto) ->
                if (name in trivialGetterFieldNames &&
                    proto.recommendedStrategy !is InitStrategy.MOCKITO_INJECT_MOCKS) {
                    proto.copy(recommendedStrategy = InitStrategy.MOCKITO_INJECT_MOCKS)
                } else proto
            }
        )

        // ── Filtrage par usage transitif (défaut #2) ──────────────────────────
        // Avant la réconciliation, on calcule l'ensemble des champs effectivement
        // touchés par la méthode cible OU par les chemins d'init élus en BLOC 7.
        // Un @Autowired non utilisé (typique sur les controleurs Spring avec
        // 10+ injections) sera dropé : la stratégie aurait sinon listé un mock
        // parasite qui consomme inutilement le budget `maxMockCount`.
        //
        // Règle de conservation :
        //   • dans l'usage transitif → garder
        //   • OU stratégie d'init non-MOCKITO (CONSTRUCTOR/SETTER/CALL_*/UNTESTABLE)
        //     → garder (intentionnellement documentée pour le LLM)
        //   • OU (Bug S) type du champ marqué essentiel par target body — filet
        //     de sécurité quand le BFS transitif rate un accès (chaînes
        //     `this.field.method()` complexes ou getter trivial intermédiaire).
        //   • sinon (MOCKITO_INJECT_MOCKS + jamais touché) → drop
        val transitiveUsage = computeTransitiveFieldUsage(
            introspector, hierarchyFqns, targetMethod,
            block7.initProtocol, config.budget.maxGraphDepth,
            frameworkPrefixes = config.frameworkPackagePrefixes
        )
        val filteredFields = usefulFields.filter { f ->
            val strategy = block7.initProtocol[f.name]?.recommendedStrategy
            f.name in transitiveUsage ||
                strategy !is InitStrategy.MOCKITO_INJECT_MOCKS ||
                // Bug S — type essentiel (touché par target body via Bug M ou
                // returnType d'une signature stubée via Bug I). Cas concret
                // production : `this.supervisionDeltaVecService.rechercherDeltaVec(...)`
                // — le service est dans essentialMockTypes mais le BFS de
                // computeTransitiveFieldUsage ne propage pas toujours `this.field`
                // jusque dans `accessed` (chaîne d'appel imbriquée). Sans ce
                // filet, le champ MOCKITO_INJECT_MOCKS est dropé et le mock
                // correspondant est retiré par la réconciliation → le LLM
                // hallucine `when(sut.getX()).thenReturn(...)`.
                f.type.fqName in ctx.essentialMockTypes
        }
        val droppedFieldNames = (usefulFields - filteredFields).map { it.name }.toSet()
        val droppedFieldTypes = (usefulFields - filteredFields).map { it.type.fqName }.toSet()
        val filteredInitProtocol = block7.initProtocol.filterKeys { it !in droppedFieldNames }
        val filteredInitOrder = block7.initOrder.filter { it !in droppedFieldNames }
        val filteredDiagnostic = buildTestabilityDiagnostic(filteredInitProtocol)

        // ── Réconciliation post-BLOC 7 — verrou critique étape 7 ──────────────
        // Si BLOC 7 a décidé qu'un champ s'auto-construit (CALL_PUBLIC_WITH_ARGS,
        // CALL_PUBLIC_TRANSITIVE, CALL_POST_CONSTRUCT, SETTER, etc.), alors le
        // type du champ ne doit PLUS apparaître dans `mocks` — sinon le prompt
        // serait contradictoire (« mocke Config » + « sut.configure() construit
        // config » dans le même prompt). BLOC 7 a la priorité sémantique :
        // c'est l'avis le plus tardif (et le plus informé).
        //
        // Étape #2 additionnelle : drop également les mocks orphelins — types
        // correspondant à un champ dropé sans aucun champ conservé du même type.
        val rawMocks = ctx.mocks.mapValues { it.value.build() }
        val mocksAfterFieldFilter = rawMocks.filterKeys { typeFqn ->
            if (typeFqn !in droppedFieldTypes) true
            else filteredFields.any { it.type.fqName == typeFqn }
        }
        val reconciledMocks = reconcileMocksWithInitProtocol(
            mocksAfterFieldFilter, filteredInitProtocol, filteredFields
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
            initProtocol = filteredInitProtocol,
            existing = ctx.internalLogics.toMap(),
            frameworkPrefixes = config.frameworkPackagePrefixes
        )

        // ── Bug B — Filtrage des dataStructures par usage transitif ───────────
        // Le crawl BLOC 6 a accumulé des DTOs depuis tous les champs et
        // paramètres, y compris ceux dont le @Autowired a été dropé par
        // défaut #2. On les filtre ici pour ne garder que ceux atteints
        // depuis la méthode cible / les mocks / les internes.
        val reachableDtos = computeReachableDataStructures(
            introspector = introspector,
            targetMethod = targetMethod,
            targetAnalysis = targetAnalysis,
            internalLogics = enrichedInternalLogics,
            mocks = reconciledMocks,
            rawDataStructures = ctx.dataStructures.toMap()
        )
        val filteredDataStructures = ctx.dataStructures.filterKeys { it in reachableDtos }

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
            dataStructures = filteredDataStructures,
            staticCalls = ctx.staticCalls.toList(),
            initProtocol = filteredInitProtocol,
            initOrder = filteredInitOrder,
            testabilityDiagnostic = filteredDiagnostic,
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
        existing: Map<String, InternalLogic>,
        frameworkPrefixes: List<String> = emptyList()
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
            // Défaut #4 — un getter trivial intra-SUT découvert via downstreamChain
            // ne mérite pas d'entrée INTERNAL_LOGIC. Cohérence avec BLOC 6c.
            if (isTrivialGetter(introspector, sig)) continue
            // Défaut #1 — frontière framework rencontrée via downstreamChain →
            // enregistrer en STUB_VIA_SPY sans capturer body/callSummaries.
            val hits = detectFrameworkBoundary(introspector, sig, frameworkPrefixes, hierarchyFqns)
            if (hits.isNotEmpty()) {
                out[key] = InternalLogic(
                    signature = sig,
                    stubViaSpy = true,
                    frameworkPrefixesHit = hits
                )
                continue
            }
            // Synthèse minimale : résumé d'appels (= une ligne par appel non-static).
            // Le format `targetType.methodName` est cohérent avec le rendu §6 actuel.
            val callSummaries = introspector.listMethodCalls(sig)
                .filter { !it.isStatic }
                .map { c -> "${c.targetType}.${c.methodName}" }
                .distinct()
            // §3.2 — la méthode est intra-SUT, frontière ouverte : on lit le
            // corps et ses exceptions structurées.
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
        body: String,
        bodyAnalysis: MethodBodyAnalysis
    ): TargetMethodAnalysis {
        val instanceCalls = calls.filterNot { it.isStatic }.map {
            InstanceCall(it.targetType, "", it.methodName, it.argTypes)
        }
        val staticCalls = calls.filter { it.isStatic }.map {
            StaticCall(it.targetType, it.methodName, it.argTypes)
        }
        // §3.1 BLOC 2 — éléments structurels du corps fournis par
        // CodeIntrospector.analyzeMethodBody (un seul parcours AST). Les types
        // extractor (ThrownExceptionRef…) sont traduits vers leurs équivalents
        // model — même pattern que MethodCall → InstanceCall ci-dessus.
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

    // Traduction extractor → model des exceptions du corps — partagée entre
    // BLOC 2 (méthode cible) et §3.2 (sous-méthodes internes).
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
            // §8bis.6 — Lombok actif mais constructeur généré non visible dans
            // PSI (plugin Lombok désactivé) : on le synthétise depuis les champs.
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

    // §3.6 + §8bis.6 — Lombok @RequiredArgsConstructor / @AllArgsConstructor.
    // Quand le plugin Lombok IntelliJ est actif, le constructeur généré apparaît
    // déjà dans listMethods (LightMethod) et cette fonction n'est pas appelée.
    // Quand il est inactif, PSI n'expose aucun constructeur — on simule celui
    // que Lombok produirait :
    //   • @AllArgsConstructor      → tous les champs d'instance ;
    //   • @RequiredArgsConstructor → champs `final` non initialisés + @NonNull.
    // Retourne null si aucune des deux annotations n'est présente sur le SUT.
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
        // Bug D — priorisation par usage transitif depuis target. Les champs
        // touchés directement ou via une chaîne intra-SUT non-framework sont
        // visités en PREMIER : leurs mocks rentrent dans le budget avant que
        // les @Autowired parasites (non touchés) ne le saturent. Sur un
        // controleur Spring avec 10 @Autowired et budget=10, sans cette
        // priorisation, les mocks essentiels arrivés tard (champs hérités du
        // parent, types retour de méthodes mockées) sont dropés en FIFO.
        val reachableFromTarget = computeFieldsReachableFromTarget(
            ctx.introspector, ctx.sutHierarchyFqns, targetMethod,
            ctx.frameworkPrefixes, ctx.budget.maxGraphDepth
        )
        ctor.parameters.forEach { p ->
            recurseDispatch(ctx, p.type.fqName, CallerContext.FIELD_OF_SUT,
                methodToCall = null, depth = depth + 1)
        }
        val (highPriority, lowPriority) = usefulFields.partition { f ->
            f.name in reachableFromTarget
        }
        highPriority.forEach { f ->
            recurseDispatch(ctx, f.type.fqName, CallerContext.FIELD_OF_SUT,
                methodToCall = null, depth = depth + 1)
        }
        lowPriority.forEach { f ->
            recurseDispatch(ctx, f.type.fqName, CallerContext.FIELD_OF_SUT,
                methodToCall = null, depth = depth + 1)
        }

        // 6b — paramètres de methodeCible
        targetMethod.parameters.forEach { p ->
            recurseDispatch(ctx, p.type.fqName, CallerContext.PARAM_OF_METHOD,
                methodToCall = null, depth = depth + 1)
        }

        // 6c — appels d'instance (split intra-SUT vs externe)
        // Défaut #4 — Court-circuit des getters triviaux intra-SUT.
        // Défaut #1 — Court-circuit des méthodes héritées framework : si la
        // méthode appelle directement une classe javax.faces / org.primefaces /
        // java.io etc., on la marque STUB_VIA_SPY et on n'explore pas son
        // corps (évite de polluer les mocks avec FacesContext, etc.).
        targetAnalysis.instanceCalls.forEach { call ->
            val calledMethod = findMethodIn(ctx, call.targetType, call.methodName, call.argTypes)
            if (call.targetType in ctx.sutHierarchyFqns) {
                if (calledMethod == null) return@forEach
                if (isTrivialGetter(ctx.introspector, calledMethod)) return@forEach
                // Bug N — détection transitive : si la chaîne intra-SUT descend
                // dans un préfixe framework, on stub l'ENTRÉE (calledMethod) et
                // on n'explore pas la cascade en internalLogics.
                val hits = detectFrameworkBoundary(
                    ctx.introspector, calledMethod, ctx.frameworkPrefixes,
                    ctx.sutHierarchyFqns
                )
                if (hits.isNotEmpty()) {
                    addStubViaSpyEntry(ctx, call.targetType, calledMethod, hits)
                    return@forEach
                }
                recurseInternalLogic(ctx, call.targetType, calledMethod, depth + 1)
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

        // 6d — instanciations détectées dans le corps (§3.1 BLOC 6d). Un `new X()`
        // dont le type est un DTO doit apparaître en « # Structures de données »
        // même s'il n'est ni champ, ni paramètre, ni type de retour. Les types
        // non-DTO (collections, java.*) sont filtrés par le classifier.
        targetAnalysis.instantiations.forEach { type ->
            recurseDispatch(ctx, type.fqName, CallerContext.INSTANTIATION,
                methodToCall = null, depth = depth + 1)
        }

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
            ctx.truncationReasons += "maxDepth exceeded at depth=$depth (cls=$classFqn)"
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
        val rawMode = ctx.classifier.classify(
            type, descriptor, callerContext, ctx.sutHierarchyFqns, descriptorMethods
        )
        // Bug U — promotion DATA_STRUCTURE → MOCK_EXTERNAL pour les types
        // « modeles » de SUT. Cas concret production : `SupervisionDeltaVecModele`
        // n'a que des setters/getters (rule 10 → DATA_STRUCTURE), mais il est
        // référencé comme champ du contrôleur et target appelle dessus :
        //   this.supervisionDeltaVecModele.setAfficherResultats(true);
        //   this.supervisionDeltaVecModele.setLienExportCsvVisible(...);
        // Sans promotion, il va dans dataStructures (jamais émis comme @Mock)
        // et le LLM attribue les setters au mock voisin par nom
        // (TableauSupervisionDeltaVecModele) → erreurs de compilation.
        // Conditions cumulées (anti faux-positif) :
        //   • DATA_STRUCTURE issu du classifier
        //   • FIELD_OF_SUT (chemin de champ direct, pas un paramètre/typearg DTO)
        //   • type marqué essentiel (Bug M/T → target appelle des méthodes dessus)
        val mode = if (rawMode == ExtractionMode.DATA_STRUCTURE &&
            callerContext == CallerContext.FIELD_OF_SUT &&
            classFqn in ctx.essentialMockTypes) {
            ExtractionMode.MOCK_EXTERNAL
        } else rawMode
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
            ctx.truncationReasons += "maxDepth exceeded — INTERNAL_LOGIC $classFqn#${method.canonical()}"
            return
        }
        val key = VisitKey(ExtractionMode.INTERNAL_LOGIC, classFqn, method.canonical())
        if (!ctx.visits.isNew(key)) return
        if (ctx.internalLogics.size >= ctx.budget.maxInternalLogicCount) {
            ctx.truncationReasons += "maxInternalLogicCount reached — drop $classFqn#${method.name}"
            return
        }

        val calls = ctx.introspector.listMethodCalls(method)
        val callSummaries = calls.map { "${it.targetType}#${it.methodName}(${it.argTypes.joinToString(",")})" }
        // §3.2 ligne 362 : « Corps = AST(Methode) ». On capture le texte source
        // pour rendu dans la layer CONTEXT. Frontière intra-SUT (cette méthode
        // n'est appelée que pour des classes de la hiérarchie du SUT).
        val body = ctx.introspector.readMethodBody(method)
        val bodyAnalysis = ctx.introspector.analyzeMethodBody(method)

        val logicKey = "$classFqn#${method.canonical()}"
        ctx.internalLogics[logicKey] = InternalLogic(
            signature = method,
            callSummaries = callSummaries,
            thrownExceptions = bodyAnalysis.thrownAsModel(),
            caughtExceptions = bodyAnalysis.caughtAsModel(),
            body = body
        )

        // Récursion : ré-appliquer BLOC 6c sur les appels de ce corps.
        // Défaut #4 — getter trivial intra-SUT ignoré (idem BLOC 6c).
        // Défaut #1 — méthode framework boundary → STUB_VIA_SPY, pas de récursion.
        calls.forEach { call ->
            val calledMethod = findMethodIn(ctx, call.targetType, call.methodName, call.argTypes)
            if (call.isStatic) {
                if (calledMethod != null && isUserStatic(call.targetType)) {
                    ctx.staticCalls += StaticCallInfo(call.targetType, call.methodName, calledMethod)
                }
                return@forEach
            }
            if (call.targetType in ctx.sutHierarchyFqns) {
                if (calledMethod == null) return@forEach
                if (isTrivialGetter(ctx.introspector, calledMethod)) return@forEach
                val hits = detectFrameworkBoundary(
                    ctx.introspector, calledMethod, ctx.frameworkPrefixes,
                    ctx.sutHierarchyFqns
                )
                if (hits.isNotEmpty()) {
                    addStubViaSpyEntry(ctx, call.targetType, calledMethod, hits)
                    return@forEach
                }
                recurseInternalLogic(ctx, call.targetType, calledMethod, depth + 1)
            } else {
                recurseDispatch(ctx, call.targetType, CallerContext.CALL_TARGET, calledMethod, depth + 1)
            }
        }

        // §3.2 — `new X()` dans le corps interne : un DTO instancié ici doit
        // être crawlé en DATA_STRUCTURE. Types non-DTO filtrés par le classifier.
        bodyAnalysis.instantiations.forEach { type ->
            recurseDispatch(ctx, type.fqName, CallerContext.INSTANTIATION,
                methodToCall = null, depth = depth + 1)
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
        // Politique d'éviction quand maxMockCount saturé — 3 niveaux :
        //
        // 1. (Défaut #3 / Bug A) Évincer un framework/infrastructure de score
        //    strictement inférieur. Domaine vs domaine interdit (anti yo-yo).
        // 2. (Bug I) Si le nouveau venu est essentiel (paramètre de target ou
        //    returnType d'une signature déjà stubée), évincer un non-essentiel
        //    quelconque. Sinon le test ne pourrait pas construire la valeur.
        // 3. Sinon drop FIFO du nouveau venu.
        if (ctx.mocks.size >= ctx.budget.maxMockCount && classFqn !in ctx.mocks) {
            val newWeight = classifyMockCategoryWeight(classFqn, ctx.frameworkPrefixes)
            val newScore = 1 + newWeight
            val isNewEssential = classFqn in ctx.essentialMockTypes

            val weakestFramework = ctx.mocks.entries
                .filter { it.value.categoryWeight < 0 }
                .minByOrNull { it.value.score() }
            when {
                weakestFramework != null && weakestFramework.value.score() < newScore -> {
                    ctx.truncationReasons +=
                        "maxMockCount LFU eviction — drop ${weakestFramework.key} " +
                            "(framework/infra, score=${weakestFramework.value.score()}) " +
                            "in favor of $classFqn (score=$newScore)"
                    ctx.mocks.remove(weakestFramework.key)
                }
                isNewEssential -> {
                    val weakestNonEssential = ctx.mocks.entries
                        .filter { it.key !in ctx.essentialMockTypes }
                        .minByOrNull { it.value.score() }
                    if (weakestNonEssential != null) {
                        ctx.truncationReasons +=
                            "maxMockCount essential eviction — drop ${weakestNonEssential.key} " +
                                "(non-essential) in favor of $classFqn (essential: target " +
                                "parameter or mock returnType)"
                        ctx.mocks.remove(weakestNonEssential.key)
                    } else {
                        ctx.truncationReasons +=
                            "maxMockCount reached — drop essential mock $classFqn (all essential)"
                        return
                    }
                }
                else -> {
                    ctx.truncationReasons += "maxMockCount reached — drop mock $classFqn"
                    return
                }
            }
        }

        // Phase 1 : type pour le mock = type déclaré (à 4c on prend la classe
        // elle-même ; un retrouverTypeDeclareDansSUT() plus fin viendra avec
        // l'extension d'introspection des champs/params en 4e).
        val builder = ctx.mocks.getOrPut(classFqn) {
            MockBuilder(
                concreteClass = classFqn,
                declaredType = classFqn,
                classAnnotations = descriptor?.annotations.orEmpty(),
                categoryWeight = classifyMockCategoryWeight(classFqn, ctx.frameworkPrefixes)
            )
        }

        // Phase 2 : signature appelée
        if (methodToCall != null && builder.signatures.none { it.canonical() == methodToCall.canonical() }) {
            builder.signatures += methodToCall
            // Bug I — le returnType de cette signature devient essentiel :
            // le LLM devra construire la valeur que `when(mock.method()).thenReturn(...)`
            // attend. Si ce type est un mock dropé du budget, le test ne compile pas
            // (cas observé sur PageDataDTO sans ctor sans args).
            ctx.essentialMockTypes += methodToCall.returnType.fqName
            ctx.essentialMockTypes += methodToCall.returnType.typeArgs.map { it.fqName }
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
            ctx.truncationReasons += "maxDtoCount reached — drop DTO $classFqn"
            return
        }
        if (depth > ctx.budget.maxDepth) {
            ctx.truncationReasons += "maxDepth exceeded — DATA_STRUCTURE $classFqn"
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
            ctx.truncationReasons += "DTO not resolvable: $classFqn"
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
            ctx.truncationReasons += "maxDepth exceeded — DTO field type ${type.fqName}"
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
    // Défaut #1 — détection des frontières framework (§3.2bis STUB_VIA_SPY)
    // ──────────────────────────────────────────────────────────────────────────
    //
    // Une méthode intra-SUT est une frontière framework si elle appelle
    // directement (sans creuser) au moins une méthode d'une classe externe
    // dont le FQN matche un préfixe configuré dans `StrategyConfig.frameworkPackagePrefixes`.
    // Le test ne traversera jamais ce code — il sera stubbé via spy + doAnswer.
    //
    // **Pourquoi un seul saut** : creuser plus profond ouvrirait le risque de
    // tagger toute méthode intra-SUT dès qu'une dépendance lointaine touche
    // javax.io. La sémantique « frontière directe » est plus prévisible et
    // suffit pour les controleurs JSF / Servlet (cas usuel).
    private fun detectFrameworkBoundary(
        introspector: CodeIntrospector,
        method: MethodSignature,
        frameworkPrefixes: List<String>,
        hierarchyFqns: Set<String> = emptySet(),
        maxDepth: Int = 8
    ): List<String> {
        if (frameworkPrefixes.isEmpty()) return emptyList()
        // Bug N — détection transitive. Le but : reporter les hits framework
        // sur l'ENTRÉE de la chaîne (la méthode appelée directement par target
        // ou par une méthode interne) et non sur la méthode la plus profonde.
        //
        // Cas concret : `target → redirige(PageDataDTO) → redirige(2) →
        // redirige(3) → redirige(4) → javax.faces`. Si on stub redirige(4), le
        // SUT exécute toute la cascade jusqu'à 4-arg ; le test n'isole rien.
        // Si on stub redirige(1) (l'entrée), la cascade est court-circuitée
        // à la racine — le pattern qu'attend la référence Mockito 4.x.
        //
        // BFS bornée par maxDepth pour éviter les cycles transitifs et limiter
        // le coût sur les hiérarchies profondes. Mode rétro-compatible : si
        // `hierarchyFqns` vide, on ne fait que la détection directe (depth 0).
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<MethodSignature, Int>>()
        queue.add(method to 0)
        val hits = LinkedHashSet<String>()
        while (queue.isNotEmpty()) {
            val (m, d) = queue.removeFirst()
            if (!visited.add(m.canonical())) continue
            if (d > maxDepth) continue
            val calls = introspector.listMethodCalls(m)
            // 1. Hits directs (depth 0 = retour-compatible avec l'ancien comportement)
            calls.forEach { c ->
                frameworkPrefixes.firstOrNull { p -> c.targetType.startsWith(p) }
                    ?.let { hits += it }
            }
            // 2. Descente transitive intra-SUT (uniquement si hierarchyFqns fourni)
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

    // Défaut #3 — pondération de catégorie pour l'éviction LFU des mocks.
    //
    // Trois niveaux :
    //   -2 : préfixe framework hard (matche `frameworkPackagePrefixes`). Ces
    //        classes sont par définition non testables raisonnablement et
    //        n'apparaissent plus en pratique après le défaut #1, mais le filet
    //        reste utile si un MOCK_EXTERNAL slip through (ex : `java.util.Map`
    //        utilisé comme argument explicite — pas tout à fait framework hard
    //        mais analogue).
    //   -1 : infrastructure de service (logger, contexte Spring) qui pourrait
    //        être créée par BLOC 6 mais qui n'a quasi jamais d'intérêt pour
    //        le test de la logique métier.
    //    0 : domaine utilisateur (par défaut). Ne perd pas la priorité.
    private fun classifyMockCategoryWeight(
        classFqn: String,
        frameworkPrefixes: List<String>
    ): Int {
        if (frameworkPrefixes.any { classFqn.startsWith(it) }) return -2
        if (INFRASTRUCTURE_MOCK_PREFIXES.any { classFqn.startsWith(it) }) return -1
        return 0
    }

    // Enregistre une méthode intra-SUT comme frontière de test. Pas de body
    // capturé (frontière fermée), pas de callSummaries — seul le marqueur
    // stubViaSpy + les préfixes hits informent le renderer.
    //
    // Respecte les budgets : maxInternalLogicCount et VisitRegistry pour éviter
    // les doublons (la même méthode peut être atteinte via plusieurs chemins).
    private fun addStubViaSpyEntry(
        ctx: RecursionContext,
        classFqn: String,
        method: MethodSignature,
        frameworkHits: List<String>
    ) {
        val key = VisitKey(ExtractionMode.INTERNAL_LOGIC, classFqn, method.canonical())
        if (!ctx.visits.isNew(key)) return
        if (ctx.internalLogics.size >= ctx.budget.maxInternalLogicCount) {
            ctx.truncationReasons +=
                "maxInternalLogicCount reached — drop STUB_VIA_SPY $classFqn#${method.name}"
            return
        }
        val logicKey = "$classFqn#${method.canonical()}"
        ctx.internalLogics[logicKey] = InternalLogic(
            signature = method,
            stubViaSpy = true,
            frameworkPrefixesHit = frameworkHits
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Défaut #4 — détection des getters triviaux
    // ──────────────────────────────────────────────────────────────────────────
    //
    // Un getter trivial = méthode dont le corps se résume à `return this.X;`
    // ou `return X;`. La traiter comme une sous-méthode INTERNAL_LOGIC pousse
    // le LLM à laisser exécuter le code, ce qui exige que X soit initialisé
    // sans qu'aucun protocole d'init ne le mentionne (cas typique
    // `BaseAstreaControleur#getUserSession()` qui retourne le champ hérité
    // `userSession`).
    //
    // Heuristique : pas d'appel, pas de branche, pas d'exception, pas
    // d'instanciation, pas d'assignation, exactement 1 accès en lecture. Si le
    // type de retour correspond au type du champ accédé, c'est un getter
    // trivial. Le BFS d'usage transitif (défaut #2) descend quand même dans
    // ces méthodes pour attraper le champ retourné — donc le filtrage ici
    // n'écarte que la mention dans `internalLogics`, pas la collecte du champ.
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
        // Garde-fou body : `return "k-" + this.id;` a un seul accès en lecture
        // sur `id` MAIS contient une expression binaire (concaténation). Sans
        // ce check, on classerait à tort ce code comme getter trivial.
        //
        // Pattern accepté : `return X;` ou `return this.X;` (avec ou sans accolades).
        // Si le body n'est pas exposé (FakeIntrospector non configuré → body
        // vide), on s'en remet aux autres critères (pas de wrapping possible
        // sans appel/binary expr → le compilateur n'aurait nulle part où le
        // produire). Le fait que aucune méthode/instanciation/branche n'ait été
        // observée est déjà une signature comportementale forte.
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
    // `getX()` est un getter trivial qui renvoie le champ `x`, le LLM doit :
    //   • mocker le type retourné (pour stubber `.y()`)
    //   • et savoir que `x` est un champ utile du SUT (pour que `@InjectMocks`
    //     ou le protocole d'init le câble).
    //
    // `resolveTrivialGetterFieldNames` enrichit `activeFields` avec le nom du
    // champ retourné par le getter — utilisé en amont de `collectUsefulFields`.
    //
    // `resolveTrivialGetterFieldTypes` enrichit `essentialMockTypes` avec le
    // type FQN du champ retourné. Cas concret : `BaseControleur#getUserSession`
    // renvoie `userSession: SessionAstreaModele` ; sans cette propagation,
    // SessionAstreaModele pourrait être évincé sur un budget tendu.
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

    private fun resolveTrivialGetterFieldTypes(
        introspector: CodeIntrospector,
        hierarchyFqns: Set<String>,
        calls: List<MethodCall>
    ): Set<String> {
        val out = mutableSetOf<String>()
        forEachTrivialGetterField(introspector, hierarchyFqns, calls) { _, field ->
            out += field.type.fqName
            out += field.type.typeArgs.map { it.fqName }
        }
        return out
    }

    // Visiteur partagé : pour chaque appel d'instance intra-SUT qui résout vers
    // un getter trivial, invoque `accept(nomDuChamp, ClassField)`. Le champ est
    // résolu dans la hiérarchie complète du SUT (le getter peut être déclaré sur
    // un parent et accéder à un champ d'un autre niveau).
    private fun forEachTrivialGetterField(
        introspector: CodeIntrospector,
        hierarchyFqns: Set<String>,
        calls: List<MethodCall>,
        accept: (String, ClassField) -> Unit
    ) {
        // Index pré-calculé des champs de la hiérarchie SUT pour éviter une
        // résolution O(n*m) à chaque appel quand la hiérarchie est profonde.
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
    // BLOC 7. À chaque méthode visitée, accumule les noms de champs accédés
    // (`listFieldAccesses` + `listFieldAssignments`).
    //
    // **Pourquoi inclure les entry points BLOC 7** : sur §9.3 (case93), la
    // méthode cible `calculate` ne touche que `cache`, mais `cache` s'init via
    // `start() → buildCache()` qui touche `loader`. Sans cette seed, `loader`
    // serait dropé alors qu'il est essentiel à l'init.
    //
    // **Frontière intra-SUT** : `listFieldAccesses`/`listFieldAssignments` ne
    // sont appelés que sur des méthodes appartenant à la hiérarchie du SUT
    // (cf contrat §3.3 « STOP » sur les méthodes externes). Le BFS ne suit
    // qu'un appel dont `targetType in hierarchyFqns`.
    // ──────────────────────────────────────────────────────────────────────────
    // Bug B — Filtrage transitif des DataStructures
    // ──────────────────────────────────────────────────────────────────────────
    //
    // Pendant BLOC 6, la stratégie crawle des DTOs depuis tous les champs du
    // SUT, paramètres, types retour et instanciations — y compris ceux des
    // champs @Autowired qui seront finalement dropés par le défaut #2. Ces
    // DTOs orphelins polluent la section « # Structures de données à construire »
    // du prompt (cas observé : `SupervisionDeltaVecModele` sur le controleur
    // JSF, jamais touché par la méthode cible).
    //
    // Critère de conservation — un DTO est gardé s'il est *atteignable* depuis :
    //   • un paramètre de la méthode cible (+ ses type-args)
    //   • le type retour de la méthode cible (+ ses type-args)
    //   • une instanciation observée dans le corps de la méthode cible
    //   • une instanciation observée dans une méthode interne (intra-SUT visitée)
    //   • le type retour d'une signature stubée sur un mock conservé (le LLM
    //     devra construire ce retour avec `when().thenReturn(new DTO(...))`)
    //   • un champ d'un DTO conservé (transitivité — un DTO conservé qui
    //     contient un autre DTO le rend nécessaire pour la construction)
    private fun computeReachableDataStructures(
        introspector: CodeIntrospector,
        targetMethod: MethodSignature,
        targetAnalysis: TargetMethodAnalysis,
        internalLogics: Map<String, InternalLogic>,
        mocks: Map<String, MockInfo>,
        rawDataStructures: Map<String, DataStructureInfo>
    ): Set<String> {
        if (rawDataStructures.isEmpty()) return emptySet()

        // Seeds — types FQN à conserver de manière inconditionnelle.
        val seeds = mutableSetOf<String>()
        // Paramètres + type-args
        targetMethod.parameters.forEach { p ->
            seeds += p.type.fqName
            seeds += p.type.typeArgs.map { it.fqName }
        }
        // Type retour + type-args
        seeds += targetMethod.returnType.fqName
        seeds += targetMethod.returnType.typeArgs.map { it.fqName }
        // Instanciations du body cible
        seeds += targetAnalysis.instantiations.map { it.fqName }
        // Instanciations des méthodes internes intra-SUT visitées (frontière
        // STUB_VIA_SPY exclue car son corps n'a pas été lu → pas d'instantiations).
        internalLogics.values
            .filter { !it.stubViaSpy }
            .forEach { logic ->
                seeds += introspector.analyzeMethodBody(logic.signature)
                    .instantiations.map { it.fqName }
            }
        // Retours des méthodes stubées sur les mocks conservés
        mocks.values.forEach { mock ->
            mock.requiredSignatures.forEach { sig ->
                seeds += sig.returnType.fqName
                seeds += sig.returnType.typeArgs.map { it.fqName }
            }
        }

        // BFS sur les références sortantes d'un DTO conservé. Suit :
        //   • fields (tous patterns sauf SEALED/ENUM/RECORD purs)
        //   • sealedSubs (le LLM doit construire une des sous-classes scellées)
        //   • builderInfo.methods (paramètres exposés par le builder)
        //   • factoryMethods (paramètres + retour des fabriques statiques)
        val kept = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { addAll(seeds) }
        while (queue.isNotEmpty()) {
            val fqn = queue.removeFirst()
            if (!visited.add(fqn)) continue
            val dto = rawDataStructures[fqn] ?: continue
            kept += fqn
            dto.fields.forEach { f ->
                queue.add(f.type.fqName)
                queue.addAll(f.type.typeArgs.map { it.fqName })
            }
            queue.addAll(dto.sealedSubs)
            dto.builderInfo?.methods?.forEach { m ->
                queue.add(m.type.fqName)
                queue.addAll(m.type.typeArgs.map { it.fqName })
            }
            dto.factoryMethods.forEach { fm ->
                queue.add(fm.returnType.fqName)
                queue.addAll(fm.returnType.typeArgs.map { it.fqName })
                fm.parameters.forEach { p ->
                    queue.add(p.type.fqName)
                    queue.addAll(p.type.typeArgs.map { it.fqName })
                }
            }
        }
        return kept
    }

    // Bug D — calcul d'un set de champs *reachable depuis target* utilisé pour
    // PRIORISER l'ordre de visite en BLOC 6a. Le BFS s'arrête à toute méthode
    // framework boundary (respect anticipé de STUB_VIA_SPY). Sans init protocol
    // (calculé en BLOC 7) : ce set sert uniquement à donner la priorité, pas à
    // filtrer définitivement. Les champs hors set sont quand même visités (ils
    // peuvent être nécessaires pour l'init découverte plus tard) mais en queue
    // — ils consommeront les places restantes du budget `maxMockCount`.
    private fun computeFieldsReachableFromTarget(
        introspector: CodeIntrospector,
        hierarchyFqns: Set<String>,
        targetMethod: MethodSignature,
        frameworkPrefixes: List<String>,
        maxGraphDepth: Int
    ): Set<String> {
        val methodIndex: List<Pair<String, MethodSignature>> = hierarchyFqns.flatMap { fqn ->
            val cls = introspector.resolveClass(fqn) ?: return@flatMap emptyList()
            introspector.listMethods(cls).map { fqn to it }
        }
        val accessed = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<MethodSignature, Int>>()
        queue.add(targetMethod to 0)

        while (queue.isNotEmpty()) {
            val (method, depth) = queue.removeFirst()
            if (!visited.add(method.canonical())) continue
            if (depth > maxGraphDepth) continue
            // Respect anticipé de STUB_VIA_SPY (Bug N : détection transitive).
            // Bug E + Bug N — détection DIRECTE (depth 0) intentionnelle.
            // On ne passe PAS hierarchyFqns : sinon le BFS skip le target lui-
            // même dès qu'un descendant intra-SUT descend dans le framework
            // (cas redirige → javax.faces), et on perd les accès champ propres
            // du target. La sémantique transitive est réservée aux call sites
            // 6c/6c-bis qui décident du STUB_VIA_SPY entry.
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

    private fun computeTransitiveFieldUsage(
        introspector: CodeIntrospector,
        hierarchyFqns: Set<String>,
        targetMethod: MethodSignature,
        initProtocol: Map<String, FieldInitProtocol>,
        maxGraphDepth: Int,
        frameworkPrefixes: List<String> = emptyList()
    ): Set<String> {
        // Index hiérarchie : liste de (classFqn, MethodSignature). Sert au
        // lookup des appels intra-SUT et à résoudre les canonicals de
        // `CALL_PUBLIC_TRANSITIVE.downstreamChain`. Construit une seule fois.
        val methodIndex: List<Pair<String, MethodSignature>> = hierarchyFqns.flatMap { fqn ->
            val cls = introspector.resolveClass(fqn) ?: return@flatMap emptyList()
            introspector.listMethods(cls).map { fqn to it }
        }

        val accessed = mutableSetOf<String>()
        val visited = mutableSetOf<String>() // dédup par canonical
        val queue = ArrayDeque<Pair<MethodSignature, Int>>()

        // Seeds — méthode cible + entry points dans le protocole d'init.
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
            // Bug E — respect de la frontière framework. Une méthode qui descend
            // dans javax.faces/* etc. est par construction STUB_VIA_SPY : son
            // corps ne s'exécutera pas dans le test, donc ses accès field
            // observables n'ont pas à entrer dans le « champ utilisé ». Sans ce
            // garde-fou, le BFS récupère filArianeModele/messageErreurModele via
            // les surcharges de redirige, qui restent ensuite dans `mocks`
            // comme parasites.
            // Bug E + Bug N — détection DIRECTE (depth 0) intentionnelle.
            // On ne passe PAS hierarchyFqns : sinon le BFS skip le target lui-
            // même dès qu'un descendant intra-SUT descend dans le framework
            // (cas redirige → javax.faces), et on perd les accès champ propres
            // du target. La sémantique transitive est réservée aux call sites
            // 6c/6c-bis qui décident du STUB_VIA_SPY entry.
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

    // Extrait les MethodSignature qui doivent servir de seed au BFS d'usage
    // selon le type de stratégie d'init. Les stratégies passives (CONSTRUCTOR,
    // IMPLICIT, MOCKITO_INJECT_MOCKS, UNTESTABLE_AS_IS) ne portent pas de
    // méthode d'init et retournent une liste vide.
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
        // Défaut #1 — préfixes framework propagés depuis StrategyConfig pour
        // détecter les frontières STUB_VIA_SPY pendant la récursion.
        val frameworkPrefixes: List<String> = emptyList(),
        val visits: VisitRegistry = VisitRegistry(),
        val mocks: LinkedHashMap<String, MockBuilder> = LinkedHashMap(),
        val internalLogics: LinkedHashMap<String, InternalLogic> = LinkedHashMap(),
        val dataStructures: LinkedHashMap<String, DataStructureInfo> = LinkedHashMap(),
        val staticCalls: MutableList<StaticCallInfo> = mutableListOf(),
        val truncationReasons: MutableList<String> = mutableListOf(),
        // Bug I — types FQN considérés *essentiels* pour le test. Les mocks
        // de ces types ne sont jamais dropés par le budget : éviction d'un
        // non-essentiel si la place manque. Initialisé avec les paramètres
        // de target (le test doit les construire ou les mocker), enrichi au
        // fur et à mesure avec les returnType des signatures stubées (le LLM
        // doit savoir construire la valeur retournée par le mock).
        val essentialMockTypes: MutableSet<String> = mutableSetOf()
    )

    private class MockBuilder(
        val concreteClass: String,
        var declaredType: String,
        var classAnnotations: List<String>,
        val signatures: MutableList<MethodSignature> = mutableListOf(),
        var returnIsNestedMock: Boolean = false,
        // Défaut #3 — pondération de catégorie pour l'éviction LFU.
        // Valeurs typiques : -2 (préfixe framework hard, ex javax.faces.),
        // -1 (infrastructure ex org.slf4j.), 0 (domaine utilisateur).
        val categoryWeight: Int = 0
    ) {
        // Score d'usage pour l'éviction LFU : signatures stubées + bonus
        // négatif pour les classes framework. Un mock à 0 signature et préfixe
        // framework hard a score=-2 → premier candidat à l'éviction.
        fun score(): Int = signatures.size + categoryWeight

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

        // Défaut #3 — préfixes infrastructure pour l'éviction LFU des mocks.
        // Distincts des frameworkPackagePrefixes (défaut #1) qui ciblent les
        // classes non-mockables raisonnablement. Ici on liste les classes
        // mockables mais à faible valeur métier — premières évincées si le
        // budget de mocks est saturé.
        private val INFRASTRUCTURE_MOCK_PREFIXES = listOf(
            "org.slf4j.",
            "org.apache.logging.",
            "org.springframework.context.",
            "org.springframework.beans.",
            "org.springframework.web.context."
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
        private const val LOMBOK_REQUIRED_ARGS_FQN = "lombok.RequiredArgsConstructor"
        private const val LOMBOK_NON_NULL_FQN = "lombok.NonNull"
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

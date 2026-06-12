package com.contextextractor.strategies.recursive.initbloc

import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.model.init.InitPath
import com.contextextractor.core.model.init.InitPathKind
import com.contextextractor.core.model.init.InitSource
import com.contextextractor.core.model.init.InitStrategy
import com.contextextractor.core.model.init.MethodInitKind
import com.contextextractor.core.model.init.MethodKey

// Arbre de décision §4.5 — applique les branches dans l'ordre strict pour
// trancher la stratégie d'initialisation d'un champ (1, 2, 2bis, 3-9, 10bis, 11 ;
// la branche 10 historique est devenue 2bis en V1.4.1 — Fix C).
//
// Une instance par analyse SUT (réutilisée pour tous les champs) — partage le
// callGraph et l'EntryPointFinder entre les appels successifs à `choose()`.
//
// Les helpers diagnostiques (buildReason / buildRefactorHints) sont inlinés
// car §4.6 ne décrit que du rendu de templates conditionnels — aucune logique
// métier à isoler. Un test sur la branche 11 vérifie qu'ils fonctionnent.
class StrategySelector(
    private val introspector: CodeIntrospector,
    private val hierarchyFqns: Set<String>,
    private val callGraph: Map<MethodKey, Set<MethodKey>>,
    private val finder: EntryPointFinder,
    private val targetMethod: MethodSignature,
    private val targetMethodKey: MethodKey,
    // V1.4 — FQN du SUT (premier élément hierarchique). Sert à détecter
    // les champs hérités (declaredIn ≠ sutFqn) qui requièrent une
    // injection par reflection — cf branche 10bis. Optionnel pour rétro-
    // compat des tests existants ; quand null, la branche 10bis est inerte.
    private val sutFqn: String? = null
) {

    fun choose(field: ClassField, sources: List<InitSource>): InitStrategy {
        // 1. Constructor — toujours préféré.
        if (sources.any { it is InitSource.Constructor }) {
            return InitStrategy.CONSTRUCTOR
        }

        // 2. Champ @Autowired/@Inject — InjectMocks.
        if (field.annotations.any { it in INJECT_ANNOTATIONS }) {
            return InitStrategy.MOCKITO_INJECT_MOCKS
        }

        // 2bis. V1.4.1 — auto-init par la cible, déplacée depuis la branche 10
        // (vrai NPE Astrea case 4.1). Si la cible écrit le champ avant toute
        // lecture (ou ne le lit jamais), aucune init n'est requise — laisser la
        // branche 6 élire une méthode publique assignatrice générait un step
        // @BeforeEach inutile (valeur écrasée par la cible) et fragile (LLM
        // mocke le paramètre sans stubber ses getters → NPE). Reste APRÈS 1-2 :
        // CONSTRUCTOR conditionne l'instanciation du SUT, @Autowired est un
        // signal explicite à coût nul (mock non utilisé ≠ erreur en strict).
        if (isAutoInitialized(field)) {
            return InitStrategy.IMPLICIT
        }

        // 3. Setter public direct.
        sources.filterIsInstance<InitSource.Setter>().firstOrNull()
            ?.let { return InitStrategy.SETTER(methodName = it.methodName) }

        // 4. @PostConstruct sans paramètre — directement sur le champ.
        sources.firstOrNull {
            it is InitSource.MethodInitializer
                && it.kind == MethodInitKind.POST_CONSTRUCT
                && it.parametersRequired.isEmpty()
        }?.let { return InitStrategy.CALL_POST_CONSTRUCT((it as InitSource.MethodInitializer).method) }

        // 5. FieldInitializer "valeur sûre".
        sources.filterIsInstance<InitSource.FieldInitializer>().firstOrNull()
            ?.takeIf { isSafeInitType(it.initType.fqName) }
            ?.let { return InitStrategy.IMPLICIT }

        // 6. Méthode publique directe — 3 sous-branches.
        val publicDirect = sources
            .filterIsInstance<InitSource.MethodInitializer>()
            .filter { it.visibility == "public" }
            .minByOrNull { scoreDirect(it) }
        if (publicDirect != null) {
            val params = publicDirect.parametersRequired
            val externals = publicDirect.externalCalls
            return when {
                params.isEmpty() && externals.isEmpty() ->
                    InitStrategy.CALL_PUBLIC(publicDirect.method)
                params.isEmpty() ->
                    InitStrategy.CALL_PUBLIC_WITH_STUBS(publicDirect.method, externals)
                else ->
                    // R3-B (Phase 2 + Phase 2 bis) — propage paramCallsToStub
                    // ET le source body. L'option (c) du body est la solution
                    // robuste : le LLM lit le code et identifie les stubs
                    // requis, même avec héritage / call chains.
                    InitStrategy.CALL_PUBLIC_WITH_ARGS(
                        method = publicDirect.method,
                        args = params,
                        paramCallsToStub = publicDirect.paramCallsToStub,
                        methodBody = runCatching {
                            introspector.readMethodBody(publicDirect.method)
                        }.getOrDefault("")
                    )
            }
        }

        // 7. Méthode privée/package — BFS pour un point d'entrée transitif.
        for (mi in sources.filterIsInstance<InitSource.MethodInitializer>()) {
            val ownerFqn = ownerOfMethod(mi.method) ?: continue
            val seedKey = MethodKey.of(ownerFqn, mi.method)
            val path = finder.find(field.name, seedKey, targetMethodKey, callGraph) ?: continue
            return when (path.kind) {
                InitPathKind.IMPLICIT_VIA_CONSTRUCTOR ->
                    InitStrategy.IMPLICIT_VIA_CONSTRUCTOR
                InitPathKind.PUBLIC_POST_CONSTRUCT -> {
                    val sig = signatureOf(path.entryPoint) ?: continue
                    InitStrategy.CALL_POST_CONSTRUCT(sig)
                }
                InitPathKind.PUBLIC_TRANSITIF -> {
                    val sig = signatureOf(path.entryPoint) ?: continue
                    InitStrategy.CALL_PUBLIC_TRANSITIVE(
                        entryPoint = sig,
                        callChain = path.chain.map { it.canonical },
                        args = path.parametersRequired,
                        stubsRequired = path.externalCallsToStub,
                        sideEffects = path.sideEffects.toList(),
                        downstreamChain = path.downstreamChain.map { it.canonical }
                    )
                }
            }
        }

        // 8. Setter package/protected.
        sources.filterIsInstance<InitSource.Setter>()
            .firstOrNull { /* déjà traité en 3 si public */ false }
            // En pratique 3 a déjà retourné si setter public. La spec laisse
            // ouvert le matching package/protected sur Setter ; SourceCollector
            // V1 n'émet que des Setter publics — donc 8 reste un no-op pour V1.
            ?.let { /* placeholder ; voir SourceCollector V1+ pour activer */ }

        // 9. Méthode initialisatrice protected/package sans paramètre.
        sources.firstOrNull {
            it is InitSource.MethodInitializer
                && it.visibility in PACKAGE_LIKE
                && it.parametersRequired.isEmpty()
        }?.let {
            val mi = it as InitSource.MethodInitializer
            return InitStrategy.CALL_SAME_PACKAGE(
                method = mi.method,
                callChain = listOf(mi.method.name)
            )
        }

        // 10. (V1.4.1 — déplacée en branche 2bis, voir ci-dessus.)

        // 10bis. V1.4 — champ hérité accessible (protected/public/package).
        // Mockito `PropertyAndSetterInjection.scanForInjection` walke la
        // hiérarchie (`while classContext != Object.class`), donc un champ
        // `protected IHMDTO structurePage` déclaré dans une classe parente
        // EST injecté par @InjectMocks à condition que le test déclare un
        // `@Mock IHMDTO structurePage` avec un nom matchant (Mockito résout
        // les ambiguïtés de type par nom). Cf §0.2 invariant « pas de
        // reflection » : MOCKITO_INJECT_MOCKS est conforme, pas de
        // ReflectionTestUtils. Le hint « nom à matcher » est rendu côté
        // ContextRenderStage (cas spécial inherited dans renderInitProtocol).
        // Vrai bug Astrea case 4.1 — `protected IHMDTO structurePage`
        // tombait en UNTESTABLE_AS_IS, IHMDTO disparaissait des mocks, LLM
        // hallucinait le receiver.
        if (sutFqn != null
            && field.declaredIn != sutFqn
            && field.visibility in INJECTABLE_INHERITED_VISIBILITIES) {
            return InitStrategy.MOCKITO_INJECT_MOCKS
        }

        // 11. Aucun chemin → UNTESTABLE_AS_IS.
        return InitStrategy.UNTESTABLE_AS_IS(
            reason = buildReason(field, sources),
            refactorHints = buildRefactorHints(field, sources)
        )
    }

    // §4.5 ligne 740-742 : score(it) sur les méthodes publiques candidates en
    // branche 6. Réutilise EntryPointScorer (depth=0, kind=PUBLIC_TRANSITIF) —
    // cohérent avec le scorer du BFS et évite de réinventer une heuristique.
    private fun scoreDirect(mi: InitSource.MethodInitializer): Int =
        EntryPointScorer.score(
            depth = 0,
            parametersRequired = mi.parametersRequired.size,
            externalCalls = mi.externalCalls.size,
            sideEffects = mi.assignsAlso.size,
            returnsVoid = mi.method.returnType.fqName == "void",
            kind = InitPathKind.PUBLIC_TRANSITIF
        )

    // §4.5 ligne 736 : « Classifier(fieldInit.typeInit) ∈ {SYSTEM_IGNORE, ENUM} ».
    // Inliné pour éviter d'injecter DefaultClassifier (pas de couplage à propager).
    // Tout type sous un préfixe système OU déclaré enum est considéré sûr.
    private fun isSafeInitType(fqName: String): Boolean {
        if (SYSTEM_PREFIXES.any { fqName.startsWith(it) }) return true
        val descriptor = introspector.resolveClass(fqName) ?: return false
        return descriptor.isEnum
    }

    // §4.5 estAutoInitialisé : la première écriture du champ dans la méthode
    // cible précède la première lecture. V1 ne fait pas d'analyse transitive
    // (« ou via appel transitif » §4.5 line 796) ; on regarde uniquement les
    // accès directs dans methodeCible. Suffisant pour les cas 91-95.
    //
    // Bug CC — élargissement : un champ ÉCRIT par target (mais jamais lu) est
    // aussi un cas d'auto-init. Le test n'a aucune action à faire avant d'appeler
    // target — c'est le target lui-même qui assigne la valeur. Sans cet
    // élargissement, le champ tombait en branche 11 → UNTESTABLE_AS_IS, ce qui
    // marquait toute la classe non testable et générait un test `_TODO_untestable`.
    // Avec cet élargissement, le rendu CONTEXT omet le step (IMPLICIT_KINDS).
    //
    // Vérification combinée à `listFieldAssignments` car certains champs ne sont
    // qu'assignés (pas accédés par .read) et n'apparaîtraient pas dans
    // listFieldAccesses selon l'introspecteur.
    private fun isAutoInitialized(field: ClassField): Boolean {
        val accesses = introspector.listFieldAccesses(targetMethod)
            .filter { it.fieldName == field.name }
        val firstWrite = accesses.indexOfFirst { it.write }
        val firstRead = accesses.indexOfFirst { !it.write }
        return when {
            // write-then-read (§4.5 original) vs read-then-write. V1.4.1 :
            // l'ancien fallback retournait vrai dès qu'une assignation existait,
            // même précédée d'une lecture — devenu dangereux en branche 2bis
            // (le champ aurait été IMPLICIT alors que sa valeur d'entrée compte).
            firstWrite >= 0 && firstRead >= 0 -> firstWrite < firstRead
            // Bug CC — write-only : champ output pur, jamais relu par la cible.
            firstWrite >= 0 -> true
            // read-only : la valeur à l'entrée compte, init requise.
            firstRead >= 0 -> false
            // Aucun accès tagué — fallback listFieldAssignments (Bug CC, pour
            // les introspecteurs qui ne taguent pas les écritures en accès).
            else -> introspector.listFieldAssignments(targetMethod)
                .any { it.fieldName == field.name }
        }
    }

    // §4.6 construireRaison — 3 templates conditionnels.
    private fun buildReason(field: ClassField, sources: List<InitSource>): String {
        if (sources.isEmpty()) {
            return "Aucune source d'initialisation détectée pour `${field.name}`. " +
                "Le champ est lu dans `${targetMethod.name}` mais jamais assigné."
        }
        val privateOnly = sources.filterIsInstance<InitSource.MethodInitializer>()
            .all { it.visibility == "private" }
        val privateNames = sources.filterIsInstance<InitSource.MethodInitializer>()
            .filter { it.visibility == "private" }
            .map { it.method.name }
        if (privateOnly && privateNames.isNotEmpty()) {
            // §4.6 ligne 811 : test « appelantes ⊇ {methodeCible} ET cardinalité 1 ».
            // V1 simplifié : on regarde si la seule appelante est target.
            val callers = privateNames.flatMap { callerName ->
                callGraph.entries
                    .filter { it.key.canonical.startsWith("$callerName(") }
                    .flatMap { it.value }
            }.toSet()
            if (callers.size == 1 && callers.first() == targetMethodKey) {
                return "`${field.name}` n'est assigné que par $privateNames, " +
                    "qui n'est appelée que par la méthode cible elle-même."
            }
            return "Toutes les méthodes assignant `${field.name}` sont privées " +
                "($privateNames) et aucune méthode publique du SUT ne les appelle transitivement."
        }
        return "Aucune stratégie d'initialisation viable pour `${field.name}` " +
            "à partir des sources détectées."
    }

    // §4.6 construirePistes — suggestions de refactor.
    private fun buildRefactorHints(field: ClassField, sources: List<InitSource>): List<String> {
        val hints = mutableListOf<String>()
        if (field.annotations.any { it in INJECT_ANNOTATIONS }) {
            hints += "Vérifier que @InjectMocks est bien utilisé."
        } else {
            hints += "Ajouter un constructeur prenant `${field.type.fqName} ${field.name}` en paramètre."
            hints += "Ajouter un setter public `set${field.name.replaceFirstChar { it.uppercase() }}(${field.type.fqName})`."
        }
        val privateMethods = sources.filterIsInstance<InitSource.MethodInitializer>()
            .filter { it.visibility == "private" }
            .map { it.method.name }
        if (privateMethods.isNotEmpty()) {
            hints += "Rendre l'une des méthodes $privateMethods package-private ou public."
            hints += "Annoter une de ces méthodes avec @PostConstruct."
        }
        return hints
    }

    // -- Helpers d'identification de la méthode --------------------------------

    // Le port n'expose pas directement « owner d'une method signature » — on
    // l'index depuis la hiérarchie. Construit une seule fois, partagé.
    private val ownerByMethod: Map<MethodSignature, String> by lazy {
        val out = HashMap<MethodSignature, String>()
        for (classFqn in hierarchyFqns) {
            val cls = introspector.resolveClass(classFqn) ?: continue
            for (m in introspector.listMethods(cls)) {
                out.putIfAbsent(m, classFqn)
            }
        }
        out
    }

    private val signatureByKey: Map<MethodKey, MethodSignature> by lazy {
        val out = HashMap<MethodKey, MethodSignature>()
        for (classFqn in hierarchyFqns) {
            val cls = introspector.resolveClass(classFqn) ?: continue
            for (m in introspector.listMethods(cls)) {
                out[MethodKey.of(classFqn, m)] = m
            }
        }
        out
    }

    private fun ownerOfMethod(m: MethodSignature): String? = ownerByMethod[m]
    private fun signatureOf(key: MethodKey): MethodSignature? = signatureByKey[key]

    companion object {
        private val INJECT_ANNOTATIONS = setOf(
            "org.springframework.beans.factory.annotation.Autowired",
            "jakarta.inject.Inject",
            "javax.inject.Inject"
        )
        private val PACKAGE_LIKE = setOf("protected", "package-private")
        // V1.4 — visibilités d'un champ hérité accessible depuis le SUT.
        // `private` exclu : un champ private hérité est inaccessible même au
        // SUT. Pour `package-private`, le test devra être dans le même package
        // — c'est rappelé dans le rendu §6 mais pas bloquant en V1.4.
        private val INJECTABLE_INHERITED_VISIBILITIES = setOf(
            "protected", "public", "package-private"
        )
        private val SYSTEM_PREFIXES = listOf(
            "java.", "javax.", "jakarta.", "kotlin.", "scala.", "sun.", "com.sun."
        )
    }
}

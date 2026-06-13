package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import com.contextextractor.fakes.Fixtures
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.core.model.init.InitStrategy
import com.contextextractor.strategies.recursive.initbloc.ordinalPriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4e-ζ — vérifie l'intégration complète de BLOC 7 dans
// RecursiveDeepStrategy : CallGraphBuilder construit UNE seule fois, puis
// EntryPointFinder + StrategySelector partagés sur chaque champ utile.
//
// Couvre case91 (chemin nominal @PostConstruct → CALL_POST_CONSTRUCT via BFS),
// un cas synthétique UNTESTABLE_AS_IS (champ assigné uniquement par méthode
// privée non appelée), et l'ordre §4.7 sur initOrder.
class RecursiveDeepStrategyBlock7Test {

    private val strategy = RecursiveDeepStrategy()

    // ── case91 — @PostConstruct prioritaire ─────────────────────────────────
    //
    // Champs utiles : `repository` (@Autowired) + `cache` (lu par calculate).
    //   • repository → MOCKITO_INJECT_MOCKS (branche 2)
    //   • cache       → CALL_POST_CONSTRUCT(init) — assigné par primeCache, dont
    //                   le seul appelant est init (@PostConstruct) (BFS branche 7b)
    //
    // Verrou critique : si CallGraphBuilder n'est pas branché, le BFS retourne
    // null et `cache` tombe en UNTESTABLE_AS_IS — le test casse explicitement.

    @Test
    fun `case91 — repository is MOCKITO_INJECT_MOCKS, cache is CALL_POST_CONSTRUCT via BFS`() {
        val fake = Fixtures.case91()
        val pkg = "com.testproject.case91"
        val sut = fake.resolveClass("$pkg.OrderService")!!
        val target = fake.listMethodsOf("$pkg.OrderService").single { it.name == "calculate" }

        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val repoStrat = result.initProtocol["repository"]?.recommendedStrategy
        assertTrue(repoStrat is InitStrategy.MOCKITO_INJECT_MOCKS,
            "repository @Autowired → MOCKITO_INJECT_MOCKS (était: $repoStrat)")

        val cacheProtocol = result.initProtocol["cache"]
            ?: error("cache attendu dans initProtocol")
        val cacheStrat = cacheProtocol.recommendedStrategy
        assertTrue(cacheStrat is InitStrategy.CALL_POST_CONSTRUCT,
            "cache assigné par primeCache appelée par init(@PostConstruct) → " +
                "CALL_POST_CONSTRUCT via BFS branche 7b (était: $cacheStrat)")
        cacheStrat as InitStrategy.CALL_POST_CONSTRUCT
        assertEquals("init", cacheStrat.method.name)

        // Diagnostic : tout est testable, pas de blocker.
        assertTrue(result.testabilityDiagnostic.testable)
        assertTrue(result.testabilityDiagnostic.blockingFields.isEmpty())
    }

    // initOrder : repository (bucket 0 = MOCKITO) avant cache (bucket 2 = POST_CONSTRUCT).
    @Test
    fun `case91 — initOrder respects priority section 4_7`() {
        val fake = Fixtures.case91()
        val pkg = "com.testproject.case91"
        val sut = fake.resolveClass("$pkg.OrderService")!!
        val target = fake.listMethodsOf("$pkg.OrderService").single { it.name == "calculate" }

        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // repository (priorité 0) doit précéder cache (priorité 2).
        val ordered = result.initOrder
        assertEquals(2, ordered.size)
        assertEquals("repository", ordered[0])
        assertEquals("cache", ordered[1])

        // Verrou : tri monotone croissant des priorités.
        val priorities = ordered.map { result.initProtocol[it]!!.recommendedStrategy.ordinalPriority() }
        assertEquals(priorities, priorities.sorted(),
            "initOrder doit être trié par priorité §4.7 ascendante")
    }

    // intraSutCallGraph : sérialisé en `Map<String, List<String>>`.
    // case91 : init() appelle primeCache() → graphe inverse contient
    //   "$pkg.OrderService#primeCache()" → ["$pkg.OrderService#init()"]
    @Test
    fun `case91 — intraSutCallGraph captures init calls primeCache (inverse)`() {
        val fake = Fixtures.case91()
        val pkg = "com.testproject.case91"
        val sut = fake.resolveClass("$pkg.OrderService")!!
        val target = fake.listMethodsOf("$pkg.OrderService").single { it.name == "calculate" }

        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val callers = result.intraSutCallGraph["$pkg.OrderService#primeCache()"]
        assertNotNull(callers, "primeCache doit avoir un entry dans le graphe inverse")
        assertTrue("$pkg.OrderService#init()" in callers!!,
            "init doit figurer parmi les appelants de primeCache")
    }

    // ── Synthétique UNTESTABLE — verrou diagnostic + branche 11 ─────────────
    //
    // Champ `cache` assigné uniquement par une méthode privée `helper`, qui
    // n'est appelée par AUCUNE méthode publique de la SUT. Le BFS doit échouer
    // → branche 11 → UNTESTABLE_AS_IS, diagnostic.testable = false.

    @Test
    fun `synthetic — field assigned only by orphan private method yields UNTESTABLE`() {
        val pkg = "com.test.untestable"
        val fake = fixture {
            klass("$pkg.SUT") {
                field("cache", T("java.util.Map"))
                method("calculate") {
                    reads("$pkg.SUT", "cache")
                }
                // helper assigne cache mais n'est appelée par personne.
                method("helper", visibility = "private") {
                    assigns("$pkg.SUT", "cache", rhsExpression = "new HashMap<>()")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "calculate" }

        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val cacheStrat = result.initProtocol["cache"]?.recommendedStrategy
        assertTrue(cacheStrat is InitStrategy.UNTESTABLE_AS_IS,
            "helper privée orpheline → UNTESTABLE_AS_IS (était: $cacheStrat)")

        // Diagnostic agrégé.
        val diag = result.testabilityDiagnostic
        assertFalse(diag.testable, "présence d'un UNTESTABLE → diagnostic NON testable")
        assertTrue("cache" in diag.blockingFields)
        assertTrue(diag.reasons.isNotEmpty(), "raison du blocker doit être propagée")
        assertTrue(diag.refactorHints.isNotEmpty(), "pistes de refactor doivent être propagées")
    }

    // ── Verrou de performance — CallGraphBuilder construit UNE seule fois ─────
    //
    // On ne peut pas instrumenter CallGraphBuilder directement, mais on peut
    // mesurer le nombre d'appels à `listMethodCalls` (proxy par compteur dans
    // FakeIntrospector via fixture personnalisée). Si BLOC 7 reconstruisait le
    // graphe par champ, `listMethodCalls` exploserait avec le nombre de champs.
    //
    // Verrou indirect : on vérifie que résoudre 5 champs ne multiplie PAS le
    // graphe sériaisé (autrement dit, intraSutCallGraph est exactement la sortie
    // d'une seule construction — pas d'aliasing dépendant de l'ordre des champs).

    @Test
    fun `synthetic — multiple fields share the same call graph (built once)`() {
        val pkg = "com.test.shared"
        val fake = fixture {
            klass("$pkg.SUT") {
                field("a", T("java.lang.String"))
                field("b", T("java.lang.String"))
                field("c", T("java.lang.String"))
                method("init", annotations = listOf("jakarta.annotation.PostConstruct")) {
                    calls("$pkg.SUT", "fillAll")
                }
                method("fillAll", visibility = "private") {
                    assigns("$pkg.SUT", "a", rhsExpression = "\"x\"")
                    assigns("$pkg.SUT", "b", rhsExpression = "\"y\"")
                    assigns("$pkg.SUT", "c", rhsExpression = "\"z\"")
                }
                method("calculate") {
                    reads("$pkg.SUT", "a")
                    reads("$pkg.SUT", "b")
                    reads("$pkg.SUT", "c")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "calculate" }

        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // Les 3 champs doivent tous résoudre vers CALL_POST_CONSTRUCT(init) —
        // preuve que le graphe inverse est cohérent et que chaque champ remonte
        // bien jusqu'à `init` via fillAll.
        listOf("a", "b", "c").forEach { fname ->
            val s = result.initProtocol[fname]?.recommendedStrategy
            assertTrue(s is InitStrategy.CALL_POST_CONSTRUCT,
                "champ $fname devait élire CALL_POST_CONSTRUCT (était: $s)")
        }

        // Le graphe inverse contient une seule entrée pour fillAll, peuplée
        // par init (l'unique appelant). Si le graphe avait été reconstruit par
        // champ avec un bug d'ordre, on aurait des entrées dupliquées ou vides.
        val callers = result.intraSutCallGraph["$pkg.SUT#fillAll()"]
        assertNotNull(callers)
        assertEquals(listOf("$pkg.SUT#init()"), callers)
    }

    // ── Réconciliation post-BLOC 7 — verrou étape 7 ──────────────────────────
    //
    // Quand un même type T est porté par PLUSIEURS champs avec des stratégies
    // différentes, T reste dans `mocks` ssi AU MOINS UN champ a stratégie
    // MOCKITO_INJECT_MOCKS. Sinon T est retiré.
    //
    // Cas testé : deux champs `Repo` — `repoA` (@Autowired → MOCKITO) et
    // `repoB` (initialisé via setter). Le mock Repo doit rester car repoA en
    // a besoin pour @InjectMocks.
    //
    // Verrou inverse implicite : si `repoA` était aussi non-MOCKITO, Repo
    // serait retiré (cas case92/case93 — tests dédiés dans ces fichiers).

    @Test
    fun `reconciliation — type kept in mocks if any field of that type is MOCKITO`() {
        val pkg = "com.test.multifield"
        val fake = fixture {
            klass("$pkg.Repo", isInterface = true) {
                method("findAll", returns = T("java.lang.String"))
                method("save") { param("v", T("java.lang.String")) }
            }
            klass("$pkg.SUT") {
                field(
                    "repoA",
                    T("$pkg.Repo"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
                )
                field("repoB", T("$pkg.Repo"))
                method("setRepoB", visibility = "public") {
                    param("r", T("$pkg.Repo"))
                    assigns("$pkg.SUT", "repoB", rhsExpression = "r")
                }
                method("calculate", returns = T("java.lang.String"),
                    body = "return repoA.findAll() + repoB.findAll();") {
                    reads("$pkg.SUT", "repoA")
                    reads("$pkg.SUT", "repoB")
                    calls("$pkg.Repo", "findAll")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "calculate" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // Verrou : repoA est MOCKITO, repoB est SETTER. Au moins un MOCKITO
        // sur le type Repo → Repo PRÉSERVÉ dans mocks.
        assertTrue("$pkg.Repo" in result.mocks.keys,
            "Repo doit rester dans mocks car au moins un champ (repoA) est MOCKITO_INJECT_MOCKS")
    }

    @Test
    fun `reconciliation V144 — SETTER strategy keeps the mock (the setter injects it)`() {
        // V1.4.4 Bug MM — INVERSION du verrou historique « tout non-MOCKITO est
        // retiré ». Le protocole SETTER rend `setHelperA(mockOfHelper)` : la
        // valeur injectée EST un mock, donc le type et ses stubs doivent rester
        // dans `# Mocks`. L'ancienne sémantique produisait un prompt
        // contradictoire (vu en prod Astrea 4.4 : mock SaisieMessage01Modele
        // supprimé alors que le protocole demandait de l'injecter → le LLM
        // construisait un vrai modele avec des setters inventés).
        val pkg = "com.test.allnonmockito"
        val fake = fixture {
            klass("$pkg.Helper") {
                method("compute", returns = T("int"))
            }
            klass("$pkg.SUT") {
                field("helperA", T("$pkg.Helper"))
                field("helperB", T("$pkg.Helper"))
                method("setHelperA", visibility = "public") {
                    param("h", T("$pkg.Helper"))
                    assigns("$pkg.SUT", "helperA", rhsExpression = "h")
                }
                method("setHelperB", visibility = "public") {
                    param("h", T("$pkg.Helper"))
                    assigns("$pkg.SUT", "helperB", rhsExpression = "h")
                }
                method("calculate", returns = T("int"),
                    body = "return helperA.compute() + helperB.compute();") {
                    reads("$pkg.SUT", "helperA")
                    reads("$pkg.SUT", "helperB")
                    calls("$pkg.Helper", "compute")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "calculate" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val helperAStrat = result.initProtocol["helperA"]?.recommendedStrategy
        val helperBStrat = result.initProtocol["helperB"]?.recommendedStrategy
        assertTrue(helperAStrat is InitStrategy.SETTER, "helperA → SETTER (était: $helperAStrat)")
        assertTrue(helperBStrat is InitStrategy.SETTER, "helperB → SETTER (était: $helperBStrat)")
        assertTrue("$pkg.Helper" in result.mocks.keys,
            "V1.4.4 Bug MM : Helper doit RESTER — le protocole SETTER injecte un " +
                "mock (`setHelperA(mockOfHelper)`), ses stubs doivent être dans le prompt")
    }

    @Test
    fun `reconciliation V144 — auto-constructive strategy still removes the mock`() {
        // Verrou négatif : un champ initialisé par une méthode publique avec
        // args (le code de prod CONSTRUIT la vraie valeur) ne doit toujours
        // PAS garder son mock — un @Mock serait contradictoire avec l'init.
        val pkg = "com.test.autoconstruct"
        val fake = fixture {
            klass("$pkg.Engine") {
                method("run", returns = T("int"))
            }
            klass("$pkg.Config") {
                method("getSize", returns = T("int"))
            }
            klass("$pkg.SUT2") {
                field("engine", T("$pkg.Engine"))
                method("initEngine", visibility = "public",
                    body = "this.engine = build(config.getSize());") {
                    param("config", T("$pkg.Config"))
                    calls("$pkg.Config", "getSize")
                    assigns("$pkg.SUT2", "engine", rhsExpression = "build(config.getSize())")
                }
                method("calculate", returns = T("int"),
                    body = "return engine.run();") {
                    reads("$pkg.SUT2", "engine")
                    calls("$pkg.Engine", "run")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.SUT2")!!
        val target = fake.listMethodsOf("$pkg.SUT2").single { it.name == "calculate" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val strat = result.initProtocol["engine"]?.recommendedStrategy
        assertTrue(strat is InitStrategy.CALL_PUBLIC_WITH_ARGS,
            "engine doit être initialisé par initEngine (était: $strat)")
        assertFalse("$pkg.Engine" in result.mocks.keys,
            "Engine ne doit PAS être mocké : initEngine construit la vraie valeur")
    }
}

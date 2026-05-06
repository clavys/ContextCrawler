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
}

package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import com.contextextractor.fakes.Fixtures
import com.contextextractor.fakes.listMethodsOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4b — BLOCs 1 à 5 sur case91 (@PostConstruct prioritaire).
// Vérifie que `init` est correctement empilé dans postConstruct et que `cache`
// (champ accédé dans calculate, sans @Autowired) est inventorié comme utile.
class RecursiveDeepStrategyCase91Test {

    private val pkg = "com.testproject.case91"
    private val fake = Fixtures.case91()
    private val strategy = RecursiveDeepStrategy()

    private val sut = fake.resolveClass("$pkg.OrderService")!!
    private val targetMethod = fake.listMethodsOf("$pkg.OrderService").single { it.name == "calculate" }

    private val result = strategy.extractCore(
        introspector = fake,
        classifier = DefaultClassifier(),
        config = StrategyConfig(),
        sut = sut,
        targetMethod = targetMethod
    )

    @Test
    fun `BLOC 1 — hierarchy contains only OrderService`() {
        assertEquals(1, result.hierarchy.size)
        assertEquals("$pkg.OrderService", result.hierarchy[0].classFqn)
    }

    @Test
    fun `BLOC 2 — calculate signature with three parameters`() {
        val sig = result.targetMethod.signature
        assertEquals("calculate", sig.name)
        assertEquals(3, sig.parameters.size)
        assertEquals(listOf("orderId", "discountCode", "rawAmount"), sig.parameters.map { it.name })
        assertEquals("$pkg.OrderDTO", sig.returnType.fqName)

        // calculate() appelle cache.lookup(String) — un seul appel d'instance.
        assertEquals(1, result.targetMethod.instanceCalls.size)
        assertEquals("lookup", result.targetMethod.instanceCalls[0].methodName)
        assertEquals("$pkg.DiscountCache", result.targetMethod.instanceCalls[0].targetType)
    }

    @Test
    fun `BLOC 3 — both cache (accessed) and repository (Autowired) are useful`() {
        val byName = result.fields.associateBy { it.name }
        assertEquals(setOf("cache", "repository"), byName.keys)

        val cache = byName["cache"]!!
        assertEquals("$pkg.DiscountCache", cache.type.fqName)
        // cache est utile parce qu'il est lu dans calculate, PAS parce qu'il est annoté.
        assertTrue(cache.annotations.isEmpty(), "cache must NOT be @Autowired in case91 (auto-built)")

        val repository = byName["repository"]!!
        assertTrue("org.springframework.beans.factory.annotation.Autowired" in repository.annotations)
    }

    @Test
    fun `BLOC 4 — no explicit ctor → synthetic empty constructor`() {
        assertTrue(result.instantiationPlan.selectedConstructor.parameters.isEmpty())
    }

    @Test
    fun `BLOC 5 — init is collected as PostConstruct entry, no setter`() {
        assertEquals(listOf("init"), result.instantiationPlan.postConstruct)
        assertTrue(result.instantiationPlan.setters.isEmpty())
    }

    // ── Sous-étape 4c — BLOC 6 ───────────────────────────────────────────────

    @Test
    fun `BLOC 6a — DiscountRepository (interface) and DiscountCache (business methods) both mocked`() {
        // DiscountRepository : interface @Repository → MOCK_EXTERNAL via rule 8.
        // DiscountCache : a `warm(List)` et `lookup(String)` (pas des accesseurs)
        //   → rule 10 ne fire pas → rule 12 défaut → MOCK_EXTERNAL.
        assertTrue("$pkg.DiscountRepository" in result.mocks.keys)
        assertTrue("$pkg.DiscountCache" in result.mocks.keys)
    }

    @Test
    fun `BLOC 6c — DiscountCache mock has lookup(String) signature from calculate body`() {
        val cache = result.mocks["$pkg.DiscountCache"]!!
        val lookup = cache.requiredSignatures.single { it.name == "lookup" }
        assertEquals(listOf("java.lang.String"), lookup.parameters.map { it.type.fqName })
        assertEquals("double", lookup.returnType.fqName)
    }

    @Test
    fun `BLOC 6e — return type OrderDTO recorded as DATA_STRUCTURE`() {
        assertTrue("$pkg.OrderDTO" in result.dataStructures.keys)
    }

    @Test
    fun `internalLogics empty — calculate ne descend pas dans des méthodes intra-SUT`() {
        // primeCache et init sont des méthodes du SUT, mais calculate ne les
        // appelle pas — donc le dispatcher ne les visite pas en INTERNAL_LOGIC.
        assertTrue(result.internalLogics.isEmpty())
    }
}

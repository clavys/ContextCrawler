package com.contextextractor.fakes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// case94 — STRATEGIE.md §9.4 : auto-init dans methodeCible (IMPLICIT).
// La méthode cible appelle elle-même primeIfNeeded() → cache est garanti
// non-null en runtime. Aucun appel `sut.<method>()` à suggérer en setUp.
class Case94AutoInitTest {

    private val pkg = "com.testproject.case94"
    private val fake = Fixtures.case94()

    @Test
    fun `OrderService has priceProvider (Autowired) and cache (auto)`() {
        val fields = fake.listFieldsOf("$pkg.OrderService").associateBy { it.name }
        assertEquals(2, fields.size)
        assertTrue("org.springframework.beans.factory.annotation.Autowired" in fields["priceProvider"]!!.annotations)
        assertEquals(emptyList<String>(), fields["cache"]!!.annotations)
    }

    @Test
    fun `primeIfNeeded is private and reads then writes cache (null check)`() {
        val prime = fake.listMethodsOf("$pkg.OrderService").single { it.name == "primeIfNeeded" }
        assertEquals("private", prime.visibility)

        val accesses = fake.listFieldAccesses(prime)
        assertTrue(accesses.any { it.fieldName == "cache" && !it.write })
        assertTrue(accesses.any { it.fieldName == "cache" && it.write })
    }

    @Test
    fun `calculate calls primeIfNeeded BEFORE accessing the cache`() {
        val calculate = fake.listMethodsOf("$pkg.OrderService").single { it.name == "calculate" }
        val calls = fake.listMethodCalls(calculate)

        // Le premier call dans le corps doit être primeIfNeeded.
        assertEquals("primeIfNeeded", calls[0].methodName)
        assertEquals("$pkg.OrderService", calls[0].targetType)

        // calculate utilise aussi cache.has, cache.get, cache.put et priceProvider.getPrice.
        assertTrue(calls.any { it.targetType == "$pkg.Cache" && it.methodName == "has" })
        assertTrue(calls.any { it.targetType == "$pkg.PriceProvider" && it.methodName == "getPrice" })
    }

    @Test
    fun `target method has single sku parameter`() {
        val calculate = fake.listMethodsOf("$pkg.OrderService").single { it.name == "calculate" }
        assertEquals(1, calculate.parameters.size)
        assertEquals("sku", calculate.parameters[0].name)
        assertEquals("java.lang.String", calculate.parameters[0].type.fqName)
    }
}

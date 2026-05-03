package com.contextextractor.fakes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// case95 — STRATEGIE.md §9.5 : UNTESTABLE_AS_IS.
// `cache` n'est assigné que par primeCache() qui est privée ET prend Config
// en argument. Aucun appel transitif depuis une méthode publique → la
// stratégie devra produire UNTESTABLE_AS_IS à l'étape 4.
class Case95UntestableTest {

    private val pkg = "com.testproject.case95"
    private val fake = Fixtures.case95()

    @Test
    fun `OrderService has only the cache field, no Autowired dependency`() {
        val fields = fake.listFieldsOf("$pkg.OrderService")
        assertEquals(1, fields.size)
        assertEquals("cache", fields[0].name)
        assertEquals(emptyList<String>(), fields[0].annotations)
    }

    @Test
    fun `primeCache is private and takes a Config parameter`() {
        val primeCache = fake.listMethodsOf("$pkg.OrderService").single { it.name == "primeCache" }
        assertEquals("private", primeCache.visibility)
        assertEquals(1, primeCache.parameters.size)
        assertEquals("$pkg.Config", primeCache.parameters[0].type.fqName)
    }

    @Test
    fun `no public method on the SUT calls primeCache transitively`() {
        val publicMethods = fake.listMethodsOf("$pkg.OrderService")
            .filter { it.visibility == "public" }
        assertTrue(publicMethods.isNotEmpty(), "calculate should be public")

        val callsToPrimeCache = publicMethods.flatMap { fake.listMethodCalls(it) }
            .any { it.targetType == "$pkg.OrderService" && it.methodName == "primeCache" }
        assertFalse(
            callsToPrimeCache,
            "case95 invariant: aucun chemin public n'atteint primeCache → champ non-initialisable"
        )
    }

    @Test
    fun `target method calculate reads cache without any preceding call to initialize it`() {
        val calculate = fake.listMethodsOf("$pkg.OrderService").single { it.name == "calculate" }

        val calls = fake.listMethodCalls(calculate)
        // Aucun appel sur le SUT lui-même (pas de primeIfNeeded, pas de primeCache).
        assertFalse(calls.any { it.targetType == "$pkg.OrderService" })

        val accesses = fake.listFieldAccesses(calculate)
        assertEquals(1, accesses.size)
        assertEquals("cache", accesses[0].fieldName)
        assertFalse(accesses[0].write)
    }
}

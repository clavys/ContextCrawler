package com.contextextractor.fakes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// case93 — STRATEGIE.md §9.3 : chaîne transitive (CALL_PUBLIC_TRANSITIVE).
// Cas critique : start → startInternal → warmup → buildCache(). La fixture
// doit permettre de reconstruire toute la chaîne via `listMethodCalls`.
class Case93TransitiveTest {

    private val pkg = "com.testproject.case93"
    private val fake = Fixtures.case93()

    @Test
    fun `OrderService extends AbstractCacheService and super-chain is reported`() {
        val service = fake.resolveClass("$pkg.OrderService")
        assertNotNull(service)
        assertEquals("$pkg.AbstractCacheService", service!!.superFqn)

        val supers = fake.listSuperClasses(service).map { it.fqn }
        assertEquals(listOf("$pkg.AbstractCacheService"), supers)
    }

    @Test
    fun `cache field lives on the abstract super class`() {
        val cacheFields = fake.listFieldsOf("$pkg.AbstractCacheService")
        assertEquals(1, cacheFields.size)
        assertEquals("cache", cacheFields[0].name)
        assertEquals("$pkg.Cache", cacheFields[0].type.fqName)
        assertEquals("protected", cacheFields[0].visibility)

        // L'OrderService concret n'a que loader (Autowired).
        val concreteFields = fake.listFieldsOf("$pkg.OrderService")
        assertEquals(1, concreteFields.size)
        assertEquals("loader", concreteFields[0].name)
    }

    @Test
    fun `start chain start to startInternal to warmup to buildCache is reconstructible`() {
        val methods = fake.listMethodsOf("$pkg.OrderService").associateBy { it.name }

        // start() appelle startInternal()
        assertTrue(fake.listMethodCalls(methods["start"]!!).any {
            it.targetType == "$pkg.OrderService" && it.methodName == "startInternal"
        })
        // startInternal() appelle warmup()
        assertTrue(fake.listMethodCalls(methods["startInternal"]!!).any {
            it.targetType == "$pkg.OrderService" && it.methodName == "warmup"
        })
        // warmup() appelle buildCache()
        assertTrue(fake.listMethodCalls(methods["warmup"]!!).any {
            it.targetType == "$pkg.OrderService" && it.methodName == "buildCache"
        })
        // buildCache() appelle Loader.load
        assertTrue(fake.listMethodCalls(methods["buildCache"]!!).any {
            it.targetType == "$pkg.Loader" && it.methodName == "load"
        })
    }

    @Test
    fun `warmup is annotated Override and writes the cache field on super class`() {
        val warmup = fake.listMethodsOf("$pkg.OrderService").single { it.name == "warmup" }
        assertTrue("java.lang.Override" in warmup.annotations)

        val accesses = fake.listFieldAccesses(warmup)
        val cacheWrite = accesses.single { it.fieldName == "cache" }
        assertTrue(cacheWrite.write)
        assertEquals("$pkg.AbstractCacheService", cacheWrite.ownerType)
    }

    @Test
    fun `target method calculate reads cache from super and accesses request`() {
        val calculate = fake.listMethodsOf("$pkg.OrderService").single { it.name == "calculate" }
        assertEquals(1, calculate.parameters.size)
        assertEquals("$pkg.OrderRequest", calculate.parameters[0].type.fqName)

        val cacheRead = fake.listFieldAccesses(calculate).single { it.fieldName == "cache" }
        assertEquals("$pkg.AbstractCacheService", cacheRead.ownerType)
    }
}

package com.contextextractor.fakes

import com.contextextractor.core.extractor.AnnotatedTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// case91 — STRATEGIE.md §9.1 : @PostConstruct prioritaire (CALL_POST_CONSTRUCT).
// Vérifie que la fixture représente : init() annotée, primeCache() privée
// qui auto-construit cache, repository à mocker.
class Case91PostConstructTest {

    private val pkg = "com.testproject.case91"
    private val fake = Fixtures.case91()

    @Test
    fun `OrderService has cache (auto-built) and repository (Autowired) fields`() {
        val fields = fake.listFieldsOf("$pkg.OrderService").associateBy { it.name }
        assertEquals(2, fields.size)

        val repository = fields["repository"]!!
        assertEquals("$pkg.DiscountRepository", repository.type.fqName)
        assertTrue("org.springframework.beans.factory.annotation.Autowired" in repository.annotations)

        val cache = fields["cache"]!!
        assertEquals("$pkg.DiscountCache", cache.type.fqName)
        // cache n'est PAS @Autowired — c'est le marqueur clé du cas 91.
        assertFalse("org.springframework.beans.factory.annotation.Autowired" in cache.annotations)
    }

    @Test
    fun `init method is annotated with PostConstruct and calls primeCache`() {
        val init = fake.listMethodsOf("$pkg.OrderService").single { it.name == "init" }

        val annotations = fake.listAnnotations(
            AnnotatedTarget.OnMethod("$pkg.OrderService", init.canonical())
        )
        assertEquals(1, annotations.size)
        assertEquals("jakarta.annotation.PostConstruct", annotations[0].fqn)

        val calls = fake.listMethodCalls(init)
        assertTrue(calls.any { it.targetType == "$pkg.OrderService" && it.methodName == "primeCache" })
    }

    @Test
    fun `primeCache is private and writes the cache field`() {
        val primeCache = fake.listMethodsOf("$pkg.OrderService").single { it.name == "primeCache" }
        assertEquals("private", primeCache.visibility)

        val accesses = fake.listFieldAccesses(primeCache)
        val cacheWrite = accesses.single { it.fieldName == "cache" }
        assertTrue(cacheWrite.write, "primeCache must write the cache field")
    }

    @Test
    fun `target method calculate has 3 parameters and reads cache only`() {
        val calculate = fake.listMethodsOf("$pkg.OrderService").single { it.name == "calculate" }
        assertEquals(3, calculate.parameters.size)
        assertEquals("Long,String,double", calculate.parameters.joinToString(",") { it.type.rawType })

        val accesses = fake.listFieldAccesses(calculate)
        assertEquals(1, accesses.size)
        assertEquals("cache", accesses[0].fieldName)
        assertFalse(accesses[0].write)
    }

    @Test
    fun `DiscountRepository is interface and DiscountEntity is JPA entity`() {
        val repo = fake.resolveClass("$pkg.DiscountRepository")
        assertNotNull(repo)
        assertTrue(repo!!.isInterface)

        val entity = fake.resolveClass("$pkg.DiscountEntity")
        assertNotNull(entity)
        assertTrue("jakarta.persistence.Entity" in entity!!.annotations)
    }
}

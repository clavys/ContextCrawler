package com.contextextractor.fakes

import com.contextextractor.core.extractor.AnnotatedTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// case00 — baseline : un service @Autowired + 1 repo + 1 DTO. Pas de Bloc 7.
// Mappe sur EXPECTED_PROMPTS.md case00 et test-project/.../case00_baseline/.
class Case00BaselineTest {

    private val pkg = "com.testproject.case00_baseline"
    private val fake = Fixtures.case00Baseline()

    @Test
    fun `OrderService has Service annotation and one Autowired repository field`() {
        val service = fake.resolveClass("$pkg.OrderService")
        assertNotNull(service)
        assertTrue("org.springframework.stereotype.Service" in service!!.annotations)

        val fields = fake.listFieldsOf("$pkg.OrderService")
        assertEquals(1, fields.size)
        val repo = fields.single()
        assertEquals("repository", repo.name)
        assertEquals("$pkg.OrderRepository", repo.type.fqName)
        assertTrue("org.springframework.beans.factory.annotation.Autowired" in repo.annotations)
    }

    @Test
    fun `findOrder signature matches expected target method`() {
        val findOrder = fake.listMethodsOf("$pkg.OrderService")
            .single { it.name == "findOrder" }

        assertEquals("$pkg.OrderDTO", findOrder.returnType.fqName)
        assertTrue(findOrder.returnType.nullable)
        assertEquals(1, findOrder.parameters.size)
        assertEquals("reference", findOrder.parameters[0].name)
        assertEquals("java.lang.String", findOrder.parameters[0].type.fqName)
        assertEquals("public", findOrder.visibility)
    }

    @Test
    fun `findOrder reads repository and calls findByReference plus three entity getters`() {
        val findOrder = fake.listMethodsOf("$pkg.OrderService")
            .single { it.name == "findOrder" }

        val accesses = fake.listFieldAccesses(findOrder)
        assertEquals(1, accesses.size)
        assertEquals("repository", accesses[0].fieldName)
        assertFalse(accesses[0].write)

        val calls = fake.listMethodCalls(findOrder)
        assertTrue(calls.any { it.targetType == "$pkg.OrderRepository" && it.methodName == "findByReference" })
        listOf("getId", "getReference", "getAmount").forEach { getter ->
            assertTrue(
                calls.any { it.targetType == "$pkg.OrderEntity" && it.methodName == getter },
                "expected call to OrderEntity#$getter"
            )
        }
    }

    @Test
    fun `OrderEntity is a JPA entity with id annotation on field`() {
        val entity = fake.resolveClass("$pkg.OrderEntity")
        assertNotNull(entity)
        assertTrue("jakarta.persistence.Entity" in entity!!.annotations)

        val idAnnotations = fake.listAnnotations(AnnotatedTarget.OnField("$pkg.OrderEntity", "id"))
        assertTrue(idAnnotations.any { it.fqn == "jakarta.persistence.Id" })
    }

    @Test
    fun `OrderRepository is an interface extending CrudRepository`() {
        val repo = fake.resolveClass("$pkg.OrderRepository")
        assertNotNull(repo)
        assertTrue(repo!!.isInterface)
        assertTrue("org.springframework.data.repository.CrudRepository" in repo.interfaces)
        assertTrue("org.springframework.stereotype.Repository" in repo.annotations)
    }

    @Test
    fun `OrderDTO has setter-based and constructor-based shapes available`() {
        val dto = fake.resolveClass("$pkg.OrderDTO")
        assertNotNull(dto)

        val constructors = fake.listMethodsOf("$pkg.OrderDTO").filter { it.name == "<init>" }
        // Un constructeur sans args + un avec 3 args (Long, String, double).
        assertEquals(2, constructors.size)
        assertTrue(constructors.any { it.parameters.isEmpty() })
        assertTrue(constructors.any {
            it.parameters.map { p -> p.type.fqName } ==
                listOf("java.lang.Long", "java.lang.String", "double")
        })
    }
}

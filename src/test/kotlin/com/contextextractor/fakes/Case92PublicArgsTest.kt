package com.contextextractor.fakes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// case92 — STRATEGIE.md §9.2 : méthode publique avec arguments
// (CALL_PUBLIC_WITH_ARGS). Vérifie que configure(int, String) est exposable
// au test pour initialiser le champ `config` avant d'appeler calculate.
class Case92PublicArgsTest {

    private val pkg = "com.testproject.case92"
    private val fake = Fixtures.case92()

    @Test
    fun `OrderService has pricingGateway (Autowired) and config (built by configure)`() {
        val fields = fake.listFieldsOf("$pkg.OrderService").associateBy { it.name }
        assertEquals(2, fields.size)
        assertTrue("org.springframework.beans.factory.annotation.Autowired" in fields["pricingGateway"]!!.annotations)
        assertEquals(emptyList<String>(), fields["config"]!!.annotations)
    }

    @Test
    fun `configure has int and String parameters and writes config field`() {
        val configure = fake.listMethodsOf("$pkg.OrderService").single { it.name == "configure" }
        assertEquals("public", configure.visibility)
        assertEquals(2, configure.parameters.size)
        assertEquals(listOf("int", "java.lang.String"), configure.parameters.map { it.type.fqName })

        val accesses = fake.listFieldAccesses(configure)
        assertTrue(accesses.any { it.fieldName == "config" && it.write })
        assertTrue(accesses.any { it.fieldName == "pricingGateway" && !it.write })
    }

    @Test
    fun `calculate declares IllegalArgumentException and reads config`() {
        val calculate = fake.listMethodsOf("$pkg.OrderService").single { it.name == "calculate" }
        assertTrue("java.lang.IllegalArgumentException" in calculate.declaredThrows)

        val accesses = fake.listFieldAccesses(calculate)
        assertTrue(accesses.any { it.fieldName == "config" && !it.write })
    }

    @Test
    fun `Config is a regular POJO (no Spring annotations)`() {
        val config = fake.resolveClass("$pkg.Config")
        assertNotNull(config)
        assertEquals(emptyList<String>(), config!!.annotations)
        // Doit avoir un constructeur (int, String).
        val ctors = fake.listMethodsOf("$pkg.Config").filter { it.name == "<init>" }
        assertTrue(ctors.any {
            it.parameters.map { p -> p.type.fqName } == listOf("int", "java.lang.String")
        })
    }

    @Test
    fun `PricingGateway is a Component interface`() {
        val gateway = fake.resolveClass("$pkg.PricingGateway")
        assertNotNull(gateway)
        assertTrue(gateway!!.isInterface)
        assertTrue("org.springframework.stereotype.Component" in gateway.annotations)
    }
}

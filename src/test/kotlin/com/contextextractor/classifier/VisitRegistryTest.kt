package com.contextextractor.classifier

import com.contextextractor.core.classifier.ExtractionMode
import com.contextextractor.core.classifier.VisitKey
import com.contextextractor.core.classifier.VisitRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisitRegistryTest {

    @Test
    fun `isNew returns true on first call and false on second`() {
        val registry = VisitRegistry()
        val key = VisitKey(ExtractionMode.SUT_BOOTSTRAP, "com.demo.OrderService", "calculate")

        assertTrue(registry.isNew(key), "first visit should be new")
        assertFalse(registry.isNew(key), "second visit should be a duplicate")
    }

    @Test
    fun `same class in different modes counts as separate entries`() {
        // Cas réel : OrderRepository visité d'abord en MOCK_EXTERNAL (mock du SUT),
        // puis en DATA_STRUCTURE (valeur de retour). Les deux doivent passer.
        val registry = VisitRegistry()
        val asMock = VisitKey(ExtractionMode.MOCK_EXTERNAL, "com.demo.OrderRepository", "findById")
        val asData = VisitKey(ExtractionMode.DATA_STRUCTURE, "com.demo.OrderRepository", null)

        assertTrue(registry.isNew(asMock))
        assertTrue(registry.isNew(asData), "different mode → distinct VisitKey, must be new")
        assertEquals(2, registry.size())
    }

    @Test
    fun `same class same mode different methods counts as separate entries`() {
        // Deux méthodes mockées sur le même repo doivent toutes deux être enregistrées.
        val registry = VisitRegistry()
        val findById = VisitKey(ExtractionMode.MOCK_EXTERNAL, "com.demo.OrderRepository", "findById(Long)")
        val save = VisitKey(ExtractionMode.MOCK_EXTERNAL, "com.demo.OrderRepository", "save(Order)")

        assertTrue(registry.isNew(findById))
        assertTrue(registry.isNew(save))
        assertEquals(2, registry.size())
    }

    @Test
    fun `contains reports presence without mutating the registry`() {
        val registry = VisitRegistry()
        val key = VisitKey(ExtractionMode.DATA_STRUCTURE, "com.demo.OrderDTO", null)

        assertFalse(registry.contains(key))
        registry.add(key)
        assertTrue(registry.contains(key))
        // contains() ne doit pas marquer la clé comme « new » à un appel ultérieur d'isNew.
        assertFalse(registry.isNew(key), "contains() does not consume the slot")
    }

    @Test
    fun `add is idempotent — same key added twice keeps size at 1`() {
        val registry = VisitRegistry()
        val key = VisitKey(ExtractionMode.INTERNAL_LOGIC, "com.demo.OrderService", "primeCache()")

        registry.add(key)
        registry.add(key)
        assertEquals(1, registry.size())
    }
}

package com.contextextractor.strategy.initbloc

import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.ResolvedType
import com.contextextractor.core.model.init.InitStrategy
import com.contextextractor.strategies.recursive.initbloc.ordinalPriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4e-ζ — verrou sur l'ordre §4.7 implémenté comme une fonction
// d'extension `Int`-valuée plutôt qu'un vrai tri topologique.
class InitStrategyPriorityTest {

    private val voidT = ResolvedType(rawType = "void", fqName = "void")
    private val sig = MethodSignature(
        name = "any", returnType = voidT, parameters = emptyList(),
        annotations = emptyList(), declaredThrows = emptyList(), visibility = "public"
    )

    // Bucket 0 — les 4 stratégies "implicites au new SUT(...)" sont équipriorité.
    @Test
    fun `bucket 0 — implicit-at-construction strategies share priority 0`() {
        assertEquals(0, InitStrategy.CONSTRUCTOR.ordinalPriority())
        assertEquals(0, InitStrategy.MOCKITO_INJECT_MOCKS.ordinalPriority())
        assertEquals(0, InitStrategy.IMPLICIT.ordinalPriority())
        assertEquals(0, InitStrategy.IMPLICIT_VIA_CONSTRUCTOR.ordinalPriority())
    }

    @Test
    fun `bucket 1 — SETTER`() {
        assertEquals(1, InitStrategy.SETTER("setX").ordinalPriority())
    }

    @Test
    fun `bucket 2 — CALL_POST_CONSTRUCT`() {
        assertEquals(2, InitStrategy.CALL_POST_CONSTRUCT(sig).ordinalPriority())
    }

    @Test
    fun `bucket 3 — all CALL_PUBLIC variants and CALL_SAME_PACKAGE share priority 3`() {
        assertEquals(3, InitStrategy.CALL_PUBLIC(sig).ordinalPriority())
        assertEquals(3, InitStrategy.CALL_PUBLIC_WITH_STUBS(sig, emptyList()).ordinalPriority())
        assertEquals(3, InitStrategy.CALL_PUBLIC_WITH_ARGS(sig, emptyList()).ordinalPriority())
        assertEquals(3, InitStrategy.CALL_PUBLIC_TRANSITIVE(sig, emptyList(), emptyList(), emptyList(), emptyList()).ordinalPriority())
        assertEquals(3, InitStrategy.CALL_SAME_PACKAGE(sig, emptyList()).ordinalPriority())
    }

    @Test
    fun `bucket 4 — UNTESTABLE_AS_IS goes last`() {
        assertEquals(4, InitStrategy.UNTESTABLE_AS_IS("r", emptyList()).ordinalPriority())
    }

    // Verrou de l'ordre — un `sortedBy { ordinalPriority() }` doit produire
    // l'ordre §4.7 quel que soit l'ordre d'insertion.
    @Test
    fun `sortedBy ordinalPriority respects spec section 4_7 order`() {
        val shuffled = listOf<InitStrategy>(
            InitStrategy.UNTESTABLE_AS_IS("r", emptyList()),
            InitStrategy.CALL_PUBLIC(sig),
            InitStrategy.SETTER("setX"),
            InitStrategy.CONSTRUCTOR,
            InitStrategy.CALL_POST_CONSTRUCT(sig)
        )
        val sorted = shuffled.sortedBy { it.ordinalPriority() }
        // Constructor (0) → SETTER (1) → CALL_POST_CONSTRUCT (2) → CALL_PUBLIC (3) → UNTESTABLE (4)
        assertTrue(sorted[0] is InitStrategy.CONSTRUCTOR)
        assertTrue(sorted[1] is InitStrategy.SETTER)
        assertTrue(sorted[2] is InitStrategy.CALL_POST_CONSTRUCT)
        assertTrue(sorted[3] is InitStrategy.CALL_PUBLIC)
        assertTrue(sorted[4] is InitStrategy.UNTESTABLE_AS_IS)
    }

    // En cas d'égalité, l'ordre source est conservé (sortedBy est stable).
    // Verrou indispensable car §4.7 ne définit pas de tie-breaker entre les
    // 4 stratégies du bucket 0 — on s'appuie sur la stabilité de Kotlin.
    @Test
    fun `sortedBy preserves insertion order within the same bucket (stability)`() {
        val sameBucket = listOf<InitStrategy>(
            InitStrategy.CONSTRUCTOR,
            InitStrategy.MOCKITO_INJECT_MOCKS,
            InitStrategy.IMPLICIT,
            InitStrategy.IMPLICIT_VIA_CONSTRUCTOR
        )
        val sorted = sameBucket.sortedBy { it.ordinalPriority() }
        assertEquals(sameBucket, sorted, "stable sort must preserve order within bucket 0")
    }
}

package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import com.contextextractor.fakes.Fixtures
import com.contextextractor.fakes.listMethodsOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4c — case92 (méthode publique avec arguments).
// Vérifie l'aiguillage MOCK_EXTERNAL vs DATA_STRUCTURE :
//  - PricingGateway (interface @Component) → mock
//  - OrderRequest (POJO ctor + getters) → DATA_STRUCTURE via rule 10 « no business method »
//  - configure() (méthode du SUT non appelée par calculate) ne doit PAS apparaître
//    en internalLogics — verrou contre les régressions du dispatcher.
class RecursiveDeepStrategyCase92Test {

    private val pkg = "com.testproject.case92"
    private val fake = Fixtures.case92()
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
    fun `PricingGateway is mocked (interface + Spring stereotype Component)`() {
        val mock = result.mocks["$pkg.PricingGateway"]
            ?: error("PricingGateway attendu dans result.mocks")
        assertEquals("$pkg.PricingGateway", mock.declaredType)
        // L'annotation Spring @Component doit être conservée pour le rendu.
        assertTrue("org.springframework.stereotype.Component" in mock.classAnnotations)
    }

    @Test
    fun `OrderRequest is NOT mocked — POJO with only ctor and getters → DATA_STRUCTURE`() {
        // Régression à éviter : sans la branche « no business method » de rule 10,
        // OrderRequest tomberait sur rule 12 → MOCK_EXTERNAL.
        assertFalse("$pkg.OrderRequest" in result.mocks.keys,
            "OrderRequest est un DTO ; ne doit pas être listé comme mock")
        assertTrue("$pkg.OrderRequest" in result.dataStructures.keys,
            "OrderRequest doit être enregistré comme DATA_STRUCTURE")
    }

    @Test
    fun `OrderDTO is recorded as DATA_STRUCTURE (return type of calculate)`() {
        assertTrue("$pkg.OrderDTO" in result.dataStructures.keys)
    }

    @Test
    fun `Config is captured (current classifier — apply method blocks rule 10)`() {
        // Documentation du comportement courant : Config a `apply(double)` qui
        // n'est pas un accesseur → règle 10 ne déclenche pas → règle 12 défaut
        // → MOCK_EXTERNAL. BLOC 7 (sous-étape 4e) raffinera la stratégie d'init
        // de `config` en CALL_PUBLIC_WITH_ARGS(configure), ce qui dominera la
        // classification mock pour le rendu final.
        assertTrue("$pkg.Config" in result.mocks.keys)

        val cfg = result.mocks["$pkg.Config"]!!
        // Deux signatures appelées dans calculate : apply(double) et getRegion().
        val sigNames = cfg.requiredSignatures.map { it.name }.toSet()
        assertEquals(setOf("apply", "getRegion"), sigNames)
    }

    @Test
    fun `configure method is NOT in internalLogics (calculate ne l'appelle pas)`() {
        val key = "$pkg.OrderService#configure(int,java.lang.String)"
        assertFalse(key in result.internalLogics.keys,
            "configure() n'est pas dans le call graph de calculate ; ne doit pas être visité")
        // En fait calculate n'appelle aucune méthode intra-SUT → internalLogics vide.
        assertTrue(result.internalLogics.isEmpty(),
            "calculate délègue uniquement à des dépendances externes (Config, OrderRequest)")
    }

    @Test
    fun `staticCalls empty — pas d'appel statique utilisateur dans calculate`() {
        assertTrue(result.staticCalls.isEmpty())
    }

    @Test
    fun `result not truncated for case92 with default budget`() {
        assertFalse(result.truncated, "case92 doit tenir dans le budget par défaut")
    }
}

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
    fun `Config is reconciled out — BLOC 7 dominates BLOC 6 classification`() {
        // Évolution étape 7 (verrou EXPECTED_PROMPTS.md case92) :
        // Config a `apply(double)` qui n'est pas un accesseur → règle 10 ne
        // déclenche pas → règle 12 défaut → BLOC 6 le classe MOCK_EXTERNAL.
        // PUIS BLOC 7 décide CALL_PUBLIC_WITH_ARGS(configure) pour le champ
        // `config`. La réconciliation post-BLOC 7 retire Config du mocks map
        // car le champ s'auto-construit via configure() — sinon le prompt
        // serait contradictoire (« mocke Config » + « sut.configure() crée
        // config » dans le même prompt).
        assertFalse("$pkg.Config" in result.mocks.keys,
            "Config doit être retiré des mocks après la réconciliation post-BLOC 7 " +
                "(champ config → CALL_PUBLIC_WITH_ARGS)")

        // PricingGateway reste mocké : son champ pricingGateway est @Autowired
        // → MOCKITO_INJECT_MOCKS, donc la réconciliation le préserve.
        assertTrue("$pkg.PricingGateway" in result.mocks.keys)
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

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

// Sous-étape 4b — BLOCs 1 à 5 sur case00_baseline.
// Vérifie l'extraction baseline (hiérarchie plate, 1 champ @Autowired, pas de
// constructeur explicite, pas de @PostConstruct).
class RecursiveDeepStrategyCase00Test {

    private val pkg = "com.testproject.case00_baseline"
    private val fake = Fixtures.case00Baseline()
    private val strategy = RecursiveDeepStrategy()

    private val sut = fake.resolveClass("$pkg.OrderService")!!
    private val targetMethod = fake.listMethodsOf("$pkg.OrderService").single { it.name == "findOrder" }

    private val result = strategy.extractCore(
        introspector = fake,
        classifier = DefaultClassifier(),
        config = StrategyConfig(),
        sut = sut,
        targetMethod = targetMethod
    )

    @Test
    fun `BLOC 1 — hierarchy contains only OrderService (no super-class)`() {
        assertEquals("$pkg.OrderService", result.sutFqName)
        assertEquals(1, result.hierarchy.size)
        assertEquals("$pkg.OrderService", result.hierarchy[0].classFqn)
        assertTrue(
            "org.springframework.stereotype.Service" in result.hierarchy[0].annotations,
            "@Service annotation should be captured at the SUT level"
        )
    }

    @Test
    fun `BLOC 2 — targetMethod signature and instance calls captured`() {
        assertEquals("findOrder", result.targetMethod.signature.name)
        assertEquals("$pkg.OrderDTO", result.targetMethod.signature.returnType.fqName)
        assertEquals(1, result.targetMethod.signature.parameters.size)
        assertEquals("reference", result.targetMethod.signature.parameters[0].name)

        // Le corps appelle 4 méthodes d'instance : findByReference + getId/getReference/getAmount.
        val callTargets = result.targetMethod.instanceCalls.map { it.methodName }.toSet()
        assertEquals(setOf("findByReference", "getId", "getReference", "getAmount"), callTargets)

        // Aucun appel statique dans le body.
        assertTrue(result.targetMethod.staticCalls.isEmpty())
    }

    @Test
    fun `BLOC 3 — only the Autowired repository field is kept as useful`() {
        assertEquals(1, result.fields.size)
        val repo = result.fields.single()
        assertEquals("repository", repo.name)
        assertEquals("$pkg.OrderRepository", repo.type.fqName)
        assertTrue("org.springframework.beans.factory.annotation.Autowired" in repo.annotations)
        assertEquals("$pkg.OrderService", repo.declaredIn)
    }

    @Test
    fun `BLOC 4 — no explicit ctor declared yields a synthetic empty constructor`() {
        val ctor = result.instantiationPlan.selectedConstructor
        assertTrue(ctor.parameters.isEmpty(), "no <init> declared on OrderService → empty params")
        assertEquals(null, ctor.triggerAnnotation)
    }

    @Test
    fun `BLOC 5 — no setters and no PostConstruct on case00`() {
        assertTrue(result.instantiationPlan.setters.isEmpty())
        assertTrue(result.instantiationPlan.postConstruct.isEmpty())
    }

    @Test
    fun `BLOC 7 — repository (Autowired) elected as MOCKITO_INJECT_MOCKS, fully testable`() {
        // case00 a 1 champ utile : `repository` (@Autowired). Branche 2 du sélecteur.
        val protocol = result.initProtocol["repository"]
            ?: error("repository attendu dans initProtocol")
        assertTrue(
            protocol.recommendedStrategy is com.contextextractor.core.model.init.InitStrategy.MOCKITO_INJECT_MOCKS,
            "repository @Autowired → MOCKITO_INJECT_MOCKS"
        )
        // initOrder ne contient que ce champ ; testabilité OK (aucun blocker).
        assertEquals(listOf("repository"), result.initOrder)
        assertTrue(result.testabilityDiagnostic.testable)
        assertTrue(result.testabilityDiagnostic.blockingFields.isEmpty())
        assertFalse(result.truncated, "case00 doit tenir dans le budget par défaut")
    }

    // ── Sous-étape 4c — BLOC 6 + MOCK_EXTERNAL + DATA_STRUCTURE minimal ──────

    @Test
    fun `BLOC 6c — OrderRepository captured as mock with findByReference signature`() {
        val mock = result.mocks["$pkg.OrderRepository"]
            ?: error("OrderRepository attendu dans result.mocks")
        assertEquals("$pkg.OrderRepository", mock.declaredType)
        assertEquals("$pkg.OrderRepository", mock.concreteClass)

        // Une signature attendue : findByReference(String) → OrderEntity.
        assertEquals(1, mock.requiredSignatures.size)
        val sig = mock.requiredSignatures.single()
        assertEquals("findByReference", sig.name)
        assertEquals(listOf("java.lang.String"), sig.parameters.map { it.type.fqName })
        assertEquals("$pkg.OrderEntity", sig.returnType.fqName)
    }

    @Test
    fun `BLOC 6c + MOCK_EXTERNAL phase 3 — OrderEntity recorded as DATA_STRUCTURE`() {
        // Atteint via deux chemins équivalents :
        //   (a) BLOC 6c : entity.getId() / getReference() / getAmount() — OrderEntity en CALL_TARGET
        //   (b) §3.3 phase 3 sur findByReference (returnType = OrderEntity)
        // VisitRegistry dédoublonne — une seule entrée présente.
        assertTrue("$pkg.OrderEntity" in result.dataStructures.keys)
    }

    @Test
    fun `BLOC 6e — return type OrderDTO recorded as DATA_STRUCTURE`() {
        // findOrder returns OrderDTO (nullable=true). Classifier rule 10 fire grâce
        // à descriptorMethods (que des accesseurs + ctor).
        assertTrue("$pkg.OrderDTO" in result.dataStructures.keys)
    }

    @Test
    fun `internalLogics empty — findOrder n'appelle aucune méthode intra-SUT`() {
        // OrderService n'a pas de super-classe et findOrder n'appelle pas
        // d'autre méthode de OrderService → INTERNAL_LOGIC inactif.
        assertTrue(result.internalLogics.isEmpty())
    }

    @Test
    fun `staticCalls empty — pas d'appel statique utilisateur dans findOrder`() {
        assertTrue(result.staticCalls.isEmpty())
    }
}

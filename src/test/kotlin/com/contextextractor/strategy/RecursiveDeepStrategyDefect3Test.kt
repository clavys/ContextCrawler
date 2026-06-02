package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.Budget
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Défaut #3 — Priorité d'éviction LFU des mocks quand `maxMockCount` saturé.
//
// Filet de sécurité : quand le budget de mocks est atteint, plutôt que de
// dropper systématiquement le nouveau venu (FIFO), on regarde si un mock
// existant a un score plus faible (préfixe framework, peu de signatures
// stubées) et on l'évince au profit du nouveau s'il est plus prometteur.
//
// En pratique, avec les défauts #1/#2/#4 actifs, ce filet ne se déclenche
// quasiment jamais — il existe pour les cas pathologiques où la stratégie
// produit encore trop de mocks malgré les autres filtres.
class RecursiveDeepStrategyDefect3Test {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.defect3"

    @Test
    fun `framework mock evicted when budget saturated by domain mock with higher score`() {
        // Setup : budget réduit à 2 mocks. On amorce avec 2 mocks pré-saturants :
        //   - org.slf4j.Logger (infrastructure → poids -1)
        //   - DomainA (poids 0)
        // Puis on tente d'ajouter DomainB (poids 0, score = 1 + 0 = 1).
        // Le score de slf4j.Logger = 1 + (-1) = 0 < 1 → évincé.
        val fake = fixture {
            klass("org.slf4j.Logger", isInterface = true) {
                method("info") { param("msg", T("java.lang.String")) }
            }
            klass("$pkg.DomainA", isInterface = true) {
                method("doA", returns = T("java.lang.String"))
            }
            klass("$pkg.DomainB", isInterface = true) {
                method("doB", returns = T("java.lang.String"))
            }
            klass("$pkg.Svc") {
                field("logger", T("org.slf4j.Logger"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("a", T("$pkg.DomainA"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("b", T("$pkg.DomainB"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "logger.info(\"hi\"); a.doA(); return b.doB();") {
                    reads("$pkg.Svc", "logger")
                    reads("$pkg.Svc", "a")
                    reads("$pkg.Svc", "b")
                    calls("org.slf4j.Logger", "info")
                    calls("$pkg.DomainA", "doA")
                    calls("$pkg.DomainB", "doB")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Svc")!!
        val target = fake.listMethodsOf("$pkg.Svc").single { it.name == "handle" }
        val tightBudget = StrategyConfig(budget = Budget(maxMockCount = 2))
        val result = strategy.extractCore(fake, DefaultClassifier(), tightBudget, sut, target)

        // Logger (infrastructure) doit avoir été évincé par DomainB.
        assertFalse("org.slf4j.Logger" in result.mocks.keys,
            "org.slf4j.Logger doit être évincé par l'éviction LFU. Mocks: ${result.mocks.keys}")
        assertTrue("$pkg.DomainA" in result.mocks.keys)
        assertTrue("$pkg.DomainB" in result.mocks.keys,
            "DomainB doit avoir été ajouté à la place de Logger via éviction LFU")

        // Trace dans truncationReasons : une éviction LFU enregistrée.
        assertTrue(result.truncationReasons.any { it.contains("éviction LFU") },
            "une éviction LFU doit être tracée. Vu: ${result.truncationReasons}")
    }

    @Test
    fun `domain mock with score 0 is NOT evicted by another domain mock with score 1`() {
        // Bug A — correctif : on n'évince que des mocks framework/infrastructure
        // (categoryWeight < 0). Sans ce verrou, un mock domaine visité tôt
        // (encore 0 signature) serait perdu au profit du suivant qui arrive
        // avec score=1, créant un yo-yo qui peut perdre des mocks essentiels.
        //
        // Setup : budget=2. On amorce 2 mocks domaine (A et B). On tente
        // d'ajouter un 3e mock domaine C. A et B ont score=0 ou 1 mais sont
        // domaine purs → impossible à évincer. C doit être dropé.
        val fake = fixture {
            klass("$pkg.DomainA", isInterface = true) {
                method("doA", returns = T("java.lang.String"))
            }
            klass("$pkg.DomainB", isInterface = true) {
                method("doB", returns = T("java.lang.String"))
            }
            klass("$pkg.DomainC", isInterface = true) {
                method("doC", returns = T("java.lang.String"))
            }
            klass("$pkg.SvcA") {
                field("a", T("$pkg.DomainA"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("b", T("$pkg.DomainB"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("c", T("$pkg.DomainC"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "a.doA(); b.doB(); return c.doC();") {
                    reads("$pkg.SvcA", "a")
                    reads("$pkg.SvcA", "b")
                    reads("$pkg.SvcA", "c")
                    calls("$pkg.DomainA", "doA")
                    calls("$pkg.DomainB", "doB")
                    calls("$pkg.DomainC", "doC")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.SvcA")!!
        val target = fake.listMethodsOf("$pkg.SvcA").single { it.name == "handle" }
        val tightBudget = StrategyConfig(budget = Budget(maxMockCount = 2))
        val result = strategy.extractCore(fake, DefaultClassifier(), tightBudget, sut, target)

        // Ni DomainA ni DomainB ne doivent avoir été évincés par DomainC.
        assertTrue("$pkg.DomainA" in result.mocks.keys,
            "DomainA (premier domaine) doit rester — pas d'éviction domaine vs domaine")
        assertTrue("$pkg.DomainB" in result.mocks.keys,
            "DomainB (second domaine) doit rester")
        assertFalse("$pkg.DomainC" in result.mocks.keys,
            "DomainC doit avoir été dropé en FIFO, pas via éviction")
        // Aucune trace d'éviction LFU : pas de mock évinçable.
        assertFalse(result.truncationReasons.any { it.contains("éviction LFU") },
            "aucune éviction LFU ne doit avoir lieu entre mocks domaine. " +
                "Vu: ${result.truncationReasons}")
    }

    @Test
    fun `all domain mocks of equal score fall back to FIFO drop`() {
        // Inverse : 3 mocks de domaine, tous score=1. Aucun n'a un score
        // inférieur à un autre → pas d'éviction, le dernier est dropé (FIFO).
        val fake = fixture {
            klass("$pkg.DomainA", isInterface = true) {
                method("doA", returns = T("java.lang.String"))
            }
            klass("$pkg.DomainB", isInterface = true) {
                method("doB", returns = T("java.lang.String"))
            }
            klass("$pkg.DomainC", isInterface = true) {
                method("doC", returns = T("java.lang.String"))
            }
            klass("$pkg.Svc2") {
                field("a", T("$pkg.DomainA"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("b", T("$pkg.DomainB"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("c", T("$pkg.DomainC"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "a.doA(); b.doB(); return c.doC();") {
                    reads("$pkg.Svc2", "a")
                    reads("$pkg.Svc2", "b")
                    reads("$pkg.Svc2", "c")
                    calls("$pkg.DomainA", "doA")
                    calls("$pkg.DomainB", "doB")
                    calls("$pkg.DomainC", "doC")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Svc2")!!
        val target = fake.listMethodsOf("$pkg.Svc2").single { it.name == "handle" }
        val tightBudget = StrategyConfig(budget = Budget(maxMockCount = 2))
        val result = strategy.extractCore(fake, DefaultClassifier(), tightBudget, sut, target)

        // Sans signal d'éviction, le 3e est dropé : on garde A et B (premiers
        // visités dans l'ordre instanceCalls), C est dropé. Score égal → pas
        // d'éviction LFU.
        assertTrue("$pkg.DomainA" in result.mocks.keys)
        assertTrue("$pkg.DomainB" in result.mocks.keys)
        assertFalse("$pkg.DomainC" in result.mocks.keys,
            "DomainC doit être dropé par le filet FIFO (scores égaux, pas d'éviction)")
        assertTrue(result.truncationReasons.any { it.contains("maxMockCount atteint") },
            "trace FIFO attendue. Vu: ${result.truncationReasons}")
    }
}

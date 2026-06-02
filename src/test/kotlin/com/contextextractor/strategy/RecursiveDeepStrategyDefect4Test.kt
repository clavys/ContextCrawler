package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Défaut #4 — Court-circuit des getters triviaux.
//
// Reproduit `BaseAstreaControleur#getUserSession()` qui se résume à
// `return this.userSession;`. Sans court-circuit, le LLM voit ce getter dans
// la section « Sous-méthodes internes » avec une consigne « ne pas mocker »,
// ce qui force l'exécution réelle — qui exige `userSession` initialisé, sans
// que BLOC 7 fournisse de protocole d'init pour ce champ hérité.
//
// Avec le court-circuit :
//   • getUserSession() disparaît de `internalLogics`
//   • le champ `userSession` reste capté par le BFS d'usage transitif (défaut
//     #2 descend dans le corps du getter pour collecter le champ retourné)
//   • le mock `SessionAstreaModele` apparaît directement dans `mocks`
class RecursiveDeepStrategyDefect4Test {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.defect4"

    @Test
    fun `trivial getter is not listed in internalLogics`() {
        val fake = fixture {
            klass("$pkg.Session", isInterface = true) {
                method("getId", returns = T("java.lang.String"))
            }
            klass("$pkg.Controller") {
                field("session", T("$pkg.Session"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("getSession", returns = T("$pkg.Session")) {
                    reads("$pkg.Controller", "session")
                }
                method("handle", returns = T("java.lang.String"),
                    body = "return this.getSession().getId();") {
                    calls("$pkg.Controller", "getSession")
                    calls("$pkg.Session", "getId")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Controller")!!
        val target = fake.listMethodsOf("$pkg.Controller").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // getSession ne doit PAS être dans internalLogics — c'est un getter trivial.
        val internalLogicKeys = result.internalLogics.keys
        assertFalse(internalLogicKeys.any { it.contains("#getSession") },
            "le getter trivial getSession ne doit pas apparaître dans internalLogics, " +
                "vu: $internalLogicKeys")

        // Le champ `session` reste capté (le BFS d'usage transitif descend
        // dans le corps du getter pour le collecter) et le mock Session existe.
        assertTrue("session" in result.fields.map { it.name },
            "le champ session doit rester capté via le corps du getter trivial")
        assertTrue("$pkg.Session" in result.mocks.keys,
            "le mock Session doit être conservé pour stubber le SUT")
    }

    @Test
    fun `non-trivial getter with conditional is still listed in internalLogics`() {
        // Garde-fou inverse : un getter avec une branche conditionnelle
        // (lazy init) n'est PAS un getter trivial et doit rester dans
        // internalLogics — le LLM doit voir cette logique.
        val fake = fixture {
            klass("$pkg.Cache") {
                method("get", returns = T("java.lang.String"))
            }
            klass("$pkg.Service") {
                field("cache", T("$pkg.Cache"))
                method("getCache", returns = T("$pkg.Cache")) {
                    reads("$pkg.Service", "cache")
                    branch("IF", "this.cache == null")
                    instantiates("$pkg.Cache")
                    assigns("$pkg.Service", "cache", rhsExpression = "new Cache()")
                }
                method("handle", returns = T("java.lang.String"),
                    body = "return this.getCache().get();") {
                    calls("$pkg.Service", "getCache")
                    calls("$pkg.Cache", "get")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Service")!!
        val target = fake.listMethodsOf("$pkg.Service").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // getCache a une branche + instanciation → PAS trivial → dans internalLogics.
        val internalLogicKeys = result.internalLogics.keys
        assertTrue(internalLogicKeys.any { it.contains("#getCache") },
            "getCache n'est pas trivial (branche + instanciation) ; doit rester " +
                "dans internalLogics. Vu: $internalLogicKeys")
    }

    @Test
    fun `getter that delegates to another method is NOT trivial`() {
        // Variante : `return this.delegate.get();` n'est pas un getter trivial
        // (présence d'un appel d'instance). Doit rester dans internalLogics.
        val fake = fixture {
            klass("$pkg.Delegate", isInterface = true) {
                method("get", returns = T("java.lang.String"))
            }
            klass("$pkg.Holder") {
                field("delegate", T("$pkg.Delegate"))
                method("getValue", returns = T("java.lang.String")) {
                    reads("$pkg.Holder", "delegate")
                    calls("$pkg.Delegate", "get")
                }
                method("handle", returns = T("java.lang.String"),
                    body = "return this.getValue();") {
                    calls("$pkg.Holder", "getValue")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Holder")!!
        val target = fake.listMethodsOf("$pkg.Holder").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val internalLogicKeys = result.internalLogics.keys
        assertTrue(internalLogicKeys.any { it.contains("#getValue") },
            "getValue contient un appel délégué ; n'est pas trivial. Vu: $internalLogicKeys")
    }
}

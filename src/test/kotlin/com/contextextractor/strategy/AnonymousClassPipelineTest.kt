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

// V1.4 étape 3 — anonymous classes (option A+α).
//
// Le port `JavaPsiIntrospector.visitNewExpression` skip les anonymous
// (`new Foo() {...}`) — ils ne sont PAS ajoutés à `bodyAnalysis.instantiations`.
// Le code body de l'anonymous est rendu via `Source code:` du target naturellement.
//
// Conséquence côté pipeline : si un body utilise un anonymous, le type parent
// (ex: Comparator, Listener) NE doit PAS être promu DATA_STRUCTURE via la
// règle 7bis (AsInstantiationInBody → DATA_STRUCTURE). Le LLM ne reçoit pas
// d'instruction de `new Foo()` qui ne compile pas ou ne reflète pas l'override.
//
// Test simule un port post-fix : le verbe `instantiates(...)` du FakeIntrospector
// N'EST PAS appelé pour le type anonymous. Le test vérifie que dans cette
// configuration, le type parent n'apparaît pas comme DATA_STRUCTURE.
class AnonymousClassPipelineTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.anon"

    @Test
    fun `anonymous of INTERFACE does NOT become DATA_STRUCTURE when port skips it`() {
        // Vrai cas que le fix V1.4 step 3 résout : `new Listener() {...}` où
        // Listener EST une interface. Le LLM ne peut pas faire `new Listener()`
        // (interface non instanciable) — sans le fix port, règle 7bis
        // (AsInstantiationInBody → DATA_STRUCTURE) écrasait la règle 12
        // (interface → MOCK_EXTERNAL). Avec le fix port, règle 12 gagne →
        // MOCK_EXTERNAL → le LLM utilise `@Mock Listener listener;` correctement.
        val fake = fixture {
            klass("$pkg.Listener", isInterface = true) {
                method("onEvent", returns = T("void"))
            }
            klass("$pkg.SomeService") {
                method("process", returns = T("void")) {
                    param("listener", T("$pkg.Listener"))
                }
            }
            klass("$pkg.SUT") {
                field("service", T("$pkg.SomeService"))
                method(
                    "target",
                    returns = T("void"),
                    body = "service.process(new Listener() { public void onEvent(Object e) {} });"
                ) {
                    // Post-fix : aucune `instantiates("$pkg.Listener")` ici.
                    // Seul le call est enregistré.
                    calls("$pkg.SomeService", "process", "$pkg.Listener")
                    reads("$pkg.SUT", "service")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // Listener (interface) NE doit PAS apparaître comme DATA_STRUCTURE —
        // sans le fix, règle 7bis y aurait poussé.
        assertFalse(result.dataStructures.containsKey("$pkg.Listener"),
            "Anonymous d'une INTERFACE ne doit PAS être DATA_STRUCTURE — " +
                "le LLM ne doit pas être incité à faire `new Listener()` " +
                "(qui ne compile pas). Option α : code body inline via Source code:")
    }

    @Test
    fun `non-anonymous new is still captured normally — non-regression`() {
        // Garde-fou critique : le fix port ne doit PAS supprimer les
        // instantiations normales. `new ArrayList<>()` doit rester capturée
        // pour finir DATA_STRUCTURE et permettre au LLM de bâtir.
        val fake = fixture {
            klass("$pkg.SimpleDTO") {
                field("name", T("java.lang.String"))
            }
            klass("$pkg.SUT") {
                method(
                    "target",
                    returns = T("$pkg.SimpleDTO"),
                    body = "return new SimpleDTO();"
                ) {
                    // Présent : c'est une instantiation NORMALE (non-anonymous).
                    instantiates("$pkg.SimpleDTO")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // SimpleDTO doit toujours apparaître comme DATA_STRUCTURE (règle 7bis).
        assertTrue(result.dataStructures.containsKey("$pkg.SimpleDTO"),
            "Une instantiation normale doit toujours produire DATA_STRUCTURE — " +
                "le fix V1.4 step 3 ne doit PAS toucher ce cas (non-anonymous)")
    }

    @Test
    fun `inner static class instantiated in body is captured normally`() {
        // Garde-fou option A : pour les inner statiques (`new SUT.Tri(...)`),
        // le pipeline existant les traite déjà correctement comme DATA_STRUCTURE.
        // Aucun changement de comportement V1.4 step 3 pour ce cas.
        val fake = fixture {
            klass("$pkg.SUT.Tri") {
                field("col", T("java.lang.String"))
                field("asc", T("boolean"))
            }
            klass("$pkg.SUT") {
                method(
                    "target",
                    returns = T("$pkg.SUT.Tri"),
                    body = "return new Tri(\"col1\", true);"
                ) {
                    // Une inner static EST une instantiation normale.
                    instantiates("$pkg.SUT.Tri")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue(result.dataStructures.containsKey("$pkg.SUT.Tri"),
            "Inner static class (option A) reste DATA_STRUCTURE comme avant — " +
                "le LLM peut faire `new SUT.Tri(...)` correctement")
    }
}

package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.model.init.InitStrategy
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4 étape 1 — vraie cause case 4.1 Astrea (champ protected hérité).
//
// Scénario : `protected IHMDTO structurePage` déclaré dans `DeepRoot`, lu
// depuis `SUT.rechercher()`. La chaîne d'héritage est :
//   SUT → MiddleParent → DeepRoot
//
// **Avant V1.4** (bug observé) :
//   - `structurePage` détecté dans `usefulFields` (collectUsefulFields itère
//     la hiérarchie) MAIS
//   - StrategySelector → branche 11 → `UNTESTABLE_AS_IS` (pas de setter,
//     pas de @PostConstruct, pas annoté @Autowired)
//   - `IHMDTO` jamais classifié comme MOCK_EXTERNAL côté `mocks` (filtré)
//   - LLM hallucine le receiver (génère `astreaModele.getStructurePage()`)
//
// **Après V1.4** (conforme STRATEGIE.md §0.2 « pas de reflection ») :
//   - StrategySelector → branche 10bis → `MOCKITO_INJECT_MOCKS`
//     (Mockito.PropertyAndSetterInjection.scanForInjection walke
//     `classContext.getSuperclass() != Object.class`)
//   - `IHMDTO` conservé dans `mocks` (réconciliation existante : un champ
//     MOCKITO_INJECT_MOCKS de type T garde T dans mocks)
//   - Renderer ajoute une section « # Inherited fields requiring @Mock by name »
//     pour rappeler au LLM le nom EXACT du @Mock à déclarer (sinon Mockito
//     perd l'injection sur ambiguïté de type).
class InheritedProtectedFieldTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.inherited"

    @Test
    fun `protected inherited field gets MOCKITO_INJECT_MOCKS strategy`() {
        val result = runScenario()
        val structurePage = result.fields.firstOrNull { it.name == "structurePage" }
        assertNotNull(structurePage, "Le champ hérité doit apparaître dans fields")
        assertEquals("$pkg.DeepRoot", structurePage!!.declaredIn,
            "declaredIn doit pointer vers la classe racine où il est déclaré")
        assertEquals("protected", structurePage.visibility)

        val proto = result.initProtocol["structurePage"]
        assertNotNull(proto, "Le champ doit avoir un init protocol")
        assertTrue(proto!!.recommendedStrategy is InitStrategy.MOCKITO_INJECT_MOCKS,
            "Champ protected hérité doit avoir MOCKITO_INJECT_MOCKS, pas UNTESTABLE_AS_IS " +
                "(Mockito walke la hiérarchie pour la field injection). " +
                "Était : ${proto.recommendedStrategy::class.simpleName}")
    }

    @Test
    fun `IHMDTO type appears in mocks via reconciliation`() {
        val result = runScenario()
        assertTrue(result.mocks.containsKey("$pkg.IHMDTO"),
            "Le type du champ hérité (IHMDTO) doit être présent dans mocks via " +
                "la réconciliation standard MOCKITO_INJECT_MOCKS.")
    }

    @Test
    fun `package-private inherited field also gets MOCKITO_INJECT_MOCKS`() {
        // Garde-fou : la branche 10bis couvre protected, public ET package-private.
        val fake = fixture {
            klass("$pkg.SomeService") {
                method("doSomething", returns = T("int"))
            }
            klass("$pkg.Parent") {
                field(
                    name = "svc",
                    type = T("$pkg.SomeService"),
                    visibility = "package-private"
                )
            }
            klass("$pkg.SUT", superFqn = "$pkg.Parent") {
                method(
                    "act",
                    returns = T("int"),
                    body = "return svc.doSomething();"
                ) {
                    reads("$pkg.Parent", "svc")
                    calls("$pkg.SomeService", "doSomething")
                }
            }
            superChain("$pkg.SUT", "$pkg.Parent")
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "act" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val proto = result.initProtocol["svc"]
        assertTrue(proto?.recommendedStrategy is InitStrategy.MOCKITO_INJECT_MOCKS,
            "Champ package-private hérité doit aussi être MOCKITO_INJECT_MOCKS")
    }

    @Test
    fun `private inherited field stays UNTESTABLE_AS_IS — branch 10bis ignores private`() {
        // Vrai garde-fou : un champ private hérité n'est PAS accessible depuis
        // le SUT, donc la branche 10bis doit le laisser passer. Il tombe en
        // branche 11 → UNTESTABLE_AS_IS comme avant V1.4.
        val fake = fixture {
            klass("$pkg.X") {
                method("call", returns = T("int"))
            }
            klass("$pkg.Parent") {
                field(name = "hidden", type = T("$pkg.X"), visibility = "private")
            }
            klass("$pkg.SUT", superFqn = "$pkg.Parent") {
                method("target", returns = T("int"), body = "return 0;")
            }
            superChain("$pkg.SUT", "$pkg.Parent")
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // Le champ private hérité n'est pas dans `bodyMentionedFieldNames`
        // (pas lu par target) donc il peut être filtré. Mais s'il l'était, sa
        // stratégie doit rester UNTESTABLE (jamais REFLECTION_INJECTION).
        val proto = result.initProtocol["hidden"]
        if (proto != null) {
            // Branche 10bis ne doit JAMAIS s'appliquer à un champ private hérité
            // (inaccessible depuis le SUT — sa propre méthode ne peut pas le lire,
            // donc il ne devrait même pas arriver ici via le scan body).
            // Si malgré tout il finit dans le protocole, sa stratégie doit rester
            // UNTESTABLE_AS_IS (pas MOCKITO_INJECT_MOCKS via branche 10bis).
            assertTrue(proto.recommendedStrategy is InitStrategy.UNTESTABLE_AS_IS,
                "Champ private hérité doit rester UNTESTABLE_AS_IS. " +
                    "Était : ${proto.recommendedStrategy::class.simpleName}")
        }
    }

    @Test
    fun `field declared in SUT itself does not trigger branch 10bis`() {
        // Non-régression : un champ déclaré DIRECTEMENT dans le SUT (declaredIn
        // == sutFqn) ne doit jamais tomber en branche 10bis. La branche 10bis
        // ne s'applique qu'aux champs hérités. Pour un champ SUT-déclaré sans
        // setter / @Autowired / etc., on retombe en UNTESTABLE_AS_IS comme avant.
        val fake = fixture {
            klass("$pkg.Dep") {
                method("compute", returns = T("int"))
            }
            klass("$pkg.SUT") {
                field(
                    name = "dep",
                    type = T("$pkg.Dep"),
                    visibility = "protected",  // protected mais déclaré dans SUT
                    declaredIn = "$pkg.SUT"
                )
                method("target", returns = T("int"), body = "return dep.compute();") {
                    reads("$pkg.SUT", "dep")
                    calls("$pkg.Dep", "compute")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val proto = result.initProtocol["dep"]
        if (proto != null) {
            // Le champ déclaré dans le SUT doit suivre le flow classique
            // (UNTESTABLE_AS_IS faute de setter/@PostConstruct/@Autowired).
            // PAS de bascule vers MOCKITO_INJECT_MOCKS via branche 10bis.
            assertTrue(proto.recommendedStrategy is InitStrategy.UNTESTABLE_AS_IS,
                "Champ protected déclaré dans le SUT (non hérité) ne doit PAS " +
                    "déclencher la branche 10bis. Sinon on dégraderait les " +
                    "champs locaux en MOCKITO_INJECT_MOCKS au lieu de remonter " +
                    "le vrai problème (manque de setter / @Autowired). " +
                    "Était : ${proto.recommendedStrategy::class.simpleName}")
        }
    }

    private fun runScenario() = run {
        val fake = fixture {
            klass("$pkg.IHMDTO") {
                method("getNombreMax", returns = T("int"))
            }
            klass("$pkg.DeepRoot") {
                field(
                    name = "structurePage",
                    type = T("$pkg.IHMDTO"),
                    visibility = "protected"
                )
            }
            klass("$pkg.MiddleParent", superFqn = "$pkg.DeepRoot")
            klass("$pkg.SUT", superFqn = "$pkg.MiddleParent") {
                method(
                    "rechercher",
                    returns = T("int"),
                    body = "return structurePage.getNombreMax();"
                ) {
                    reads("$pkg.DeepRoot", "structurePage")
                    calls("$pkg.IHMDTO", "getNombreMax")
                }
            }
            superChain("$pkg.SUT", "$pkg.MiddleParent", "$pkg.DeepRoot")
            superChain("$pkg.MiddleParent", "$pkg.DeepRoot")
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "rechercher" }
        strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
    }
}

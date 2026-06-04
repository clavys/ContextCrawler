package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptBuilder
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.Fixtures
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bug Q — Prompt entièrement en anglais (CONTEXT layer + truncation reasons).
// Bug R — Désambiguer l'abréviation « SUT » : le texte du prompt utilise
// « class under test », et la variable suggérée dans les patterns est dérivée
// du type (TypeName → typeName) au lieu du générique `sut`.
//
// Demande utilisateur : « le prompt généré doit uniquement être en anglais
// et sans abréviation comme SUT ».
class PromptLanguageAndNamingTest {

    private val builder = PromptBuilder.defaultPipeline()
    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()

    private fun buildFor(fake: com.contextextractor.fakes.FakeIntrospector,
                          sutFqn: String, methodName: String): String {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == methodName }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        return builder.build(mapper.map(result))
    }

    @Test
    fun `Bug Q — CONTEXT layer headers are entirely in English`() {
        val output = buildFor(Fixtures.case91(),
            "com.testproject.case91.OrderService", "calculate")

        // En-têtes principaux traduits.
        assertTrue(output.contains("# Class under test"),
            "header en anglais attendu : '# Class under test'")
        assertTrue(output.contains("# Target method"),
            "header en anglais attendu : '# Target method'")
        assertTrue(output.contains("# Class under test instantiation"),
            "header en anglais attendu : '# Class under test instantiation'")
        assertTrue(output.contains("# Field initialization protocol"),
            "header en anglais attendu : '# Field initialization protocol'")
        assertTrue(output.contains("# Mocks (annotate with @Mock {declaredType})"),
            "header en anglais attendu : '# Mocks (annotate with @Mock {declaredType})'")

        // L'ancien français NE doit plus apparaître.
        assertFalse(output.contains("# Classe sous test"),
            "ancien header français '# Classe sous test' doit avoir disparu")
        assertFalse(output.contains("# Méthode cible"),
            "ancien header français '# Méthode cible' doit avoir disparu")
        assertFalse(output.contains("# Instanciation du SUT"),
            "ancien header français '# Instanciation du SUT' doit avoir disparu")
        assertFalse(output.contains("# Protocole d'initialisation"),
            "ancien header français '# Protocole d'initialisation' doit avoir disparu")
        assertFalse(output.contains("Stratégie :"),
            "label français 'Stratégie :' doit être traduit en 'Strategy:'")
        assertFalse(output.contains("Hiérarchie :"),
            "label français 'Hiérarchie :' doit être traduit en 'Hierarchy:'")
        assertFalse(output.contains("Code source :"),
            "label français 'Code source :' doit être traduit en 'Source code:'")
    }

    @Test
    fun `Bug Q — truncation reasons are emitted in English`() {
        // Force le truncation en saturant maxMockCount=1 avec un service essentiel
        // ET un parasite. Le parasite est dropé avec un message en anglais.
        val pkg = "com.test.bugq.trunc"
        val fake = fixture {
            klass("$pkg.SvcA", isInterface = true) {
                method("doA", returns = T("java.lang.String"))
            }
            klass("$pkg.Parasite", isInterface = true)
            klass("$pkg.Ctrl") {
                field("svcA", T("$pkg.SvcA"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("parasite", T("$pkg.Parasite"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return svcA.doA();") {
                    reads("$pkg.Ctrl", "svcA")
                    calls("$pkg.SvcA", "doA")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "handle" }
        val tightBudget = StrategyConfig(
            budget = com.contextextractor.core.strategy.Budget(maxMockCount = 1)
        )
        val result = strategy.extractCore(fake, DefaultClassifier(), tightBudget, sut, target)

        // Au moins un message de truncation doit être présent, en anglais.
        assertTrue(result.truncationReasons.any {
            it.contains("maxMockCount reached") || it.contains("drop mock")
        }, "trace truncation en anglais attendue. Vu: ${result.truncationReasons}")
        // Les anciens messages français ne doivent plus jamais sortir.
        assertFalse(result.truncationReasons.any { it.contains("atteint") },
            "le mot 'atteint' (FR) ne doit plus apparaître. Vu: ${result.truncationReasons}")
        assertFalse(result.truncationReasons.any { it.contains("éviction") },
            "le mot 'éviction' (FR) ne doit plus apparaître. Vu: ${result.truncationReasons}")
    }

    @Test
    fun `Bug R — CONSTRAINTS section refers to 'class under test' instead of 'SUT'`() {
        val output = buildFor(Fixtures.case91(),
            "com.testproject.case91.OrderService", "calculate")
        // Le terme désambigué apparaît.
        assertTrue(output.contains("class under test"),
            "le texte du prompt doit utiliser 'class under test' au lieu de 'SUT'")
        // Le « SUT via @InjectMocks » historique doit avoir disparu.
        assertFalse(output.contains("SUT via @InjectMocks"),
            "l'abréviation 'SUT via @InjectMocks' doit être désambiguée " +
                "en 'the class under test via @InjectMocks'")
        // Le « stub a method on the SUT » historique doit avoir disparu.
        assertFalse(output.contains("stub a method on the SUT"),
            "'stub a method on the SUT' doit être désambigué")
    }

    @Test
    fun `Bug R — spy pattern uses class-derived variable name instead of generic sut`() {
        // Setup : SUT extends Base, Base#redirige descend dans javax.faces →
        // STUB_VIA_SPY déclenché.
        val pkg = "com.test.bugr.spy"
        val fake = fixture {
            klass("$pkg.Page", isInterface = true)
            klass("$pkg.Base") {
                method("redirige", returns = T("void")) {
                    param("p", T("$pkg.Page"))
                    calls("javax.faces.context.FacesContext", "getCurrentInstance")
                }
            }
            klass("$pkg.SupervisionDeltaVecControleur", superFqn = "$pkg.Base") {
                method("handle", returns = T("void"),
                    body = "this.redirige(new Page());") {
                    calls("$pkg.Base", "redirige", "$pkg.Page")
                    instantiates("$pkg.Page")
                }
            }
            superChain("$pkg.SupervisionDeltaVecControleur", "$pkg.Base")
        }
        val output = buildFor(fake, "$pkg.SupervisionDeltaVecControleur", "handle")

        // Bug R — variable dérivée du nom court du SUT en camelCase
        // (`SupervisionDeltaVecControleur` → `supervisionDeltaVecControleur`).
        assertTrue(output.contains("supervisionDeltaVecControleur = spy(supervisionDeltaVecControleur);"),
            "le pattern spy doit utiliser la variable typeName dérivée du SUT, " +
                "pas le générique `sut`. Output:\n$output")
        assertTrue(output.contains("doAnswer(invocation -> null).when(supervisionDeltaVecControleur).redirige"),
            "le stub spy doit cibler la variable typeName, pas `sut`")

        // L'ancien `sut = spy(sut)` ne doit plus apparaître dans les patterns
        // suggérés (la variable `sut` peut figurer dans CONSTRAINTS car c'est
        // la convention Mockito acceptée, mais pas dans les patterns rendus
        // par ContextRenderStage).
        val spySection = output.substringAfter("# Methods to stub via spy", "")
        assertFalse(spySection.contains("sut = spy(sut);"),
            "ancien pattern 'sut = spy(sut);' doit avoir disparu de la section spy")
    }
}

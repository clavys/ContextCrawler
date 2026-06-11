package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptContext
import com.contextextractor.core.prompt.meta.LayerKind
import com.contextextractor.core.prompt.stages.ContextRenderStage
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4 Fix B — visibilité des méthodes internes signalée au LLM.
//
// Vrai bug Astrea case 4.1 :
//   protected void afficherMessagePourLesRecherchesVolumineuses(...) { ... }
// déclarée dans TableauPagineControleur (package fr.x.transverse.controleur).
// SUT dans fr.y.idt.coordination.controleur. Le test est généré dans
// fr.y.idt.coordination.controleur — un package DIFFÉRENT du parent.
// Conséquence : le LLM tentait :
//     doNothing().when(sut).afficherMessage(any(), anyInt());
//     verify(sut).afficherMessage(eq(ctxt), eq(2));
// → Java refuse la compilation (`protected access`).
//
// Fix : la section `# Internal sub-methods` annote chaque méthode
// non-public avec `[protected]` ou `[package-private]`, et un commentaire
// d'avertissement explique au LLM de ne PAS la stubber/verify.
class InternalMethodVisibilityTest {

    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val stage = ContextRenderStage()

    private fun render(
        fake: com.contextextractor.fakes.FakeIntrospector,
        sutFqn: String, methodName: String
    ): String {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == methodName }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        val tree = mapper.map(result)
        val ctx = PromptContext(tree = tree)
        stage.apply(ctx)
        return ctx.layers[LayerKind.CONTEXT]
            ?: error("ContextRenderStage doit peupler la layer CONTEXT")
    }

    @Test
    fun `protected internal method has visibility marker and warning`() {
        val pkg = "com.test.vis_protected"
        val fake = fixture {
            klass("$pkg.Parent") {
                method(
                    "afficherMessage",
                    visibility = "protected",
                    returns = T("void"),
                    body = "// some logic"
                ) {
                    param("contexte", T("$pkg.Contexte"))
                    param("nombre", T("int"))
                }
            }
            klass("$pkg.Contexte") {
                method("addMessage", returns = T("void")) {
                    param("m", T("java.lang.String"))
                }
            }
            klass("$pkg.SUT", superFqn = "$pkg.Parent") {
                method(
                    "target",
                    returns = T("void"),
                    body = "super.afficherMessage(ctxt, 42);"
                ) {
                    calls("$pkg.Parent", "afficherMessage", "$pkg.Contexte", "int")
                }
            }
            superChain("$pkg.SUT", "$pkg.Parent")
        }
        val output = render(fake, "$pkg.SUT", "target")

        assertTrue(output.contains("# Internal sub-methods"),
            "section Internal sub-methods attendue")
        assertTrue(output.contains("afficherMessage"),
            "la méthode héritée appelée doit apparaître dans Internal sub-methods")
        assertTrue(output.contains("`[protected]`"),
            "marqueur [protected] attendu à côté du nom de méthode. Output:\n$output")
        assertTrue(output.contains("Visibility = `protected`"),
            "commentaire d'avertissement avec la visibilité attendu")
        assertTrue(output.contains("CANNOT call, stub via `doReturn/doNothing`, or `verify`"),
            "warning explicit sur l'interdiction de stub/verify attendu")
    }

    @Test
    fun `public internal method has NO visibility marker`() {
        val pkg = "com.test.vis_public"
        val fake = fixture {
            klass("$pkg.Parent") {
                method(
                    "doPublicStuff",
                    visibility = "public",
                    returns = T("void"),
                    body = "// some logic"
                ) {
                    param("x", T("int"))
                }
            }
            klass("$pkg.SUT", superFqn = "$pkg.Parent") {
                method(
                    "target",
                    returns = T("void"),
                    body = "super.doPublicStuff(1);"
                ) {
                    calls("$pkg.Parent", "doPublicStuff", "int")
                }
            }
            superChain("$pkg.SUT", "$pkg.Parent")
        }
        val output = render(fake, "$pkg.SUT", "target")

        // public → pas de [public] (pas de bruit), pas de warning.
        assertFalse(output.contains("`[public]`"),
            "pas de marqueur pour public — signal inutile")
        assertFalse(output.contains("CANNOT call, stub via"),
            "pas de warning pour public — la méthode est appelable normalement")
    }

    @Test
    fun `BaseConstraints contains the V1_4 rule about protected internal methods`() {
        // Verrou structurel : la règle vit dans BaseConstraints (universelle,
        // pas dans QwenTuning) — toutes les exécutions doivent la voir.
        val baseText = com.contextextractor.core.prompt.constraints.BaseConstraints.TEXT
        assertTrue(baseText.contains("V1.4 — methods listed under"),
            "règle V1.4 protected/package methods attendue dans BaseConstraints")
        assertTrue(baseText.contains("[protected]"),
            "exemple de marqueur [protected] attendu dans la règle")
        assertTrue(baseText.contains("INFORMATIONAL ONLY"),
            "rappel INFORMATIONAL ONLY attendu dans la règle")
    }
}

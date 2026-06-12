package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptBuilder
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4.1 Bug HH — les statics utilitaires purs ne doivent PAS être prescrits
// en mockStatic.
//
// Vrai bug Astrea case 4.2 (`rechercherTypeMessage`) :
//   Le prompt listait `CollectionUtils#isNotEmpty` sous
//   « Mock via Mockito.mockStatic » → le LLM wrappait
//   `mockStatic(CollectionUtils.class)` SANS stubber `isNotEmpty`.
//   Or dans un MockedStatic, une méthode non stubbée retourne la valeur par
//   défaut (false), PAS la vraie implémentation → `trierListeDeroulanteParCode`
//   sautait le sort → les stubs `getCleAssociee()` (imposés par Bug EE)
//   n'étaient jamais consommés → UnnecessaryStubbingException.
//
// Fix : renderStaticCalls partitionne par préfixe de package —
//   - statics domaine projet → « Mock via Mockito.mockStatic » (inchangé)
//   - utilitaires purs (org.apache.commons, guava, JDK, spring-util) →
//     bloc « do NOT wrap these in mockStatic » avec explication du piège.
class PureUtilityStaticsTest {

    private val builder = PromptBuilder.defaultPipeline()
    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val pkg = "com.test.bughh"

    private fun buildFor(fake: FakeIntrospector,
                         sutFqn: String, methodName: String): String {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == methodName }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        return builder.build(mapper.map(result))
    }

    // Reproduit le pattern case 4.2 : un static projet (AutoCompletionUtils)
    // + un static commons (CollectionUtils) appelés par la cible.
    private fun mixedStaticsScenario(): String {
        val fake = fixture {
            klass("org.apache.commons.collections4.CollectionUtils") {
                method("isNotEmpty", returns = T("boolean"), isStatic = true) {
                    param("coll", T("java.util.Collection"))
                }
            }
            klass("$pkg.AutoCompletionUtils") {
                method("autocompleter", returns = T("java.util.List"), isStatic = true) {
                    param("valeur", T("java.lang.String"))
                    param("liste", T("java.util.List"))
                }
            }
            klass("$pkg.Modele", isInterface = true) {
                method("getListe", returns = T("java.util.List"))
            }
            klass("$pkg.Ctrl") {
                field("modele", T("$pkg.Modele"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("target", returns = T("java.util.List"),
                    body = "List l = AutoCompletionUtils.autocompleter(v, this.modele.getListe()); " +
                        "if (CollectionUtils.isNotEmpty(l)) { } return l;") {
                    param("v", T("java.lang.String"))
                    reads("$pkg.Ctrl", "modele")
                    calls("$pkg.Modele", "getListe")
                    calls("$pkg.AutoCompletionUtils", "autocompleter",
                        "java.lang.String", "java.util.List", isStatic = true)
                    calls("org.apache.commons.collections4.CollectionUtils", "isNotEmpty",
                        "java.util.Collection", isStatic = true)
                }
            }
        }
        return buildFor(fake, "$pkg.Ctrl", "target")
    }

    // Les assertions sont scoppées à la partie CONTEXT : la phrase « Pure
    // utility statics » apparaît AUSSI dans le texte Bug HH des CONSTRAINTS —
    // sans le scope, les asserts seraient vacueusement vrais.
    private fun contextPart(output: String): String =
        output.substringBefore("=== CONSTRAINTS ===")

    @Test
    fun `Bug HH — commons static lands in the do-NOT-mockStatic block`() {
        val context = contextPart(mixedStaticsScenario())
        assertTrue(context.contains("Pure utility statics — do NOT wrap these in mockStatic"),
            "bloc utilitaires purs attendu dans CONTEXT. Section statics:\n" +
                context.substringAfter("# Detected user static calls", "<SECTION ABSENTE>").take(800))
        assertTrue(context.contains("- org.apache.commons.collections4.CollectionUtils#isNotEmpty"),
            "CollectionUtils#isNotEmpty doit être listé (dans le bloc pure). Section:\n" +
                context.substringAfter("# Detected user static calls", "<SECTION ABSENTE>").take(800))
        assertTrue(context.contains("DEFAULT value (false/0/null)"),
            "l'explication du piège valeur-par-défaut doit être rendue dans CONTEXT")
    }

    @Test
    fun `Bug HH — project static stays under Mock via mockStatic`() {
        val context = contextPart(mixedStaticsScenario())
        assertTrue(context.contains("Mock via Mockito.mockStatic({class}.class):"),
            "header mockStatic attendu pour le static projet. Section:\n" +
                context.substringAfter("# Detected user static calls", "<SECTION ABSENTE>").take(800))
        assertTrue(context.contains("- $pkg.AutoCompletionUtils#autocompleter"),
            "le static domaine projet doit rester à mocker")
        // CollectionUtils ne doit PAS apparaître dans la liste « Mock via » —
        // c'est-à-dire entre le header mockStatic et le bloc pure.
        val mockViaBlock = context
            .substringAfter("Mock via Mockito.mockStatic({class}.class):", "")
            .substringBefore("Pure utility statics", "")
        assertFalse(mockViaBlock.contains("CollectionUtils"),
            "CollectionUtils ne doit JAMAIS être prescrit en mockStatic. " +
                "Bloc Mock via:\n$mockViaBlock")
    }

    @Test
    fun `Bug HH — only pure statics renders no mockStatic instruction at all`() {
        // Variante case 4.1 : CollectionUtils est le SEUL static détecté.
        // Le header « Mock via mockStatic » ne doit pas apparaître du tout.
        val fake = fixture {
            klass("org.apache.commons.collections4.CollectionUtils") {
                method("isNotEmpty", returns = T("boolean"), isStatic = true) {
                    param("coll", T("java.util.Collection"))
                }
            }
            klass("$pkg.Modele2", isInterface = true) {
                method("getListe", returns = T("java.util.List"))
            }
            klass("$pkg.Ctrl2") {
                field("modele2", T("$pkg.Modele2"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("target", returns = T("boolean"),
                    body = "return CollectionUtils.isNotEmpty(this.modele2.getListe());") {
                    reads("$pkg.Ctrl2", "modele2")
                    calls("$pkg.Modele2", "getListe")
                    calls("org.apache.commons.collections4.CollectionUtils", "isNotEmpty",
                        "java.util.Collection", isStatic = true)
                }
            }
        }
        val context = contextPart(buildFor(fake, "$pkg.Ctrl2", "target"))
        assertFalse(context.contains("Mock via Mockito.mockStatic"),
            "aucun static à mocker → pas d'instruction mockStatic dans CONTEXT")
        assertTrue(context.contains("- org.apache.commons.collections4.CollectionUtils#isNotEmpty"),
            "le bloc pure doit quand même signaler le static rencontré. Section:\n" +
                context.substringAfter("# Detected user static calls", "<SECTION ABSENTE>").take(800))
    }
}

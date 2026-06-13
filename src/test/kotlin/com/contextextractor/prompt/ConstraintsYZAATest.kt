package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptBuilder
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.Fixtures
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bug Y/Z/AA — Trois sections dans CONSTRAINTS pour réduire les erreurs
// LLM observées en production sur Qwen 3.6 35B :
//   Y  — déclarer `throws` sur les test methods quand target déclare une
//        checked exception (cas prod : AstreaFonctionnelleException).
//   Z  — typer Collections.emptyMap() / emptyList() pour matcher les
//        signatures génériques de stub.
//   AA — utiliser any(Type.class) au lieu de `new Type()` dans verify(...)
//        quand l'instance est créée à l'intérieur du corps cible
//        (cas prod : putModele(class, new ReferenceModele())).
class ConstraintsYZAATest {

    private val builder = PromptBuilder.defaultPipeline()
    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()

    private fun buildAnyPrompt(): String {
        val fake = Fixtures.case91()
        val sut = fake.resolveClass("com.testproject.case91.OrderService")!!
        val target = fake.listMethodsOf("com.testproject.case91.OrderService")
            .single { it.name == "calculate" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        return builder.build(mapper.map(result))
    }

    @Test
    fun `Bug Y — CONSTRAINTS includes throws declaration rule`() {
        val output = buildAnyPrompt()
        assertTrue(output.contains("# Checked exceptions on test methods"),
            "Bug Y : section dédiée checked exceptions attendue")
        assertTrue(output.contains("throws (declared): ExceptionType"),
            "Bug Y : référence explicite au format CONTEXT 'throws (declared)'")
        assertTrue(output.contains("throws AstreaFonctionnelleException"),
            "Bug Y : exemple concret valide attendu")
    }

    // Bug UU — Astrea 4.1 : thenThrow(checked) sur une méthode qui ne déclare
    // pas la checked → MockitoException runtime « Checked exception is invalid
    // for this method! ». La règle : ne stubber une checked que sur une méthode
    // dont la signature montre `throws X`, sinon omettre le test d'exception.
    @Test
    fun `Bug UU — CONSTRAINTS includes checked-exception thenThrow rule`() {
        val output = buildAnyPrompt()
        assertTrue(output.contains("# Throwing a CHECKED exception from a stub (Bug UU)"),
            "Bug UU : section dédiée thenThrow(checked) attendue")
        assertTrue(output.contains("Checked exception is invalid for this method!"),
            "Bug UU : l'erreur Mockito concrète doit être citée")
        assertTrue(output.contains("throws X` after the return type") ||
            output.contains("shows `throws X`"),
            "Bug UU : la règle doit lier l'autorisation au `throws X` rendu sur la signature")
        assertTrue(output.contains("OMIT that test method"),
            "Bug UU : consigne d'omettre le test si aucune méthode ne déclare la checked")
    }

    @Test
    fun `Bug Z — CONSTRAINTS includes typed Collections rule`() {
        val output = buildAnyPrompt()
        assertTrue(output.contains("# Typed Collections in stubs"),
            "Bug Z : section sur Collections typés attendue")
        assertTrue(output.contains("Collections.emptyMap()") &&
            output.contains("Collections.<String, "),
            "Bug Z : pattern Collections.<K,V>emptyMap() attendu en exemple")
    }

    @Test
    fun `Bug AA — CONSTRAINTS includes any() matcher rule for in-body new`() {
        val output = buildAnyPrompt()
        assertTrue(output.contains("# Verifying calls that receive in-body `new` instances"),
            "Bug AA : section dédiée matcher any() attendue")
        assertTrue(output.contains("any(Type.class)") ||
            output.contains("any(ReferenceModele.class)"),
            "Bug AA : exemple any(Type.class) attendu")
        assertTrue(output.contains("eq(") &&
            output.contains("when ANY matcher is used"),
            "Bug AA : rappel règle Mockito (tous args matchers si un l'est)")
    }

    // Bug RR — Astrea 4.4 : getter chaîné retournant `List<E>` → when().thenReturn()
    // ne compile pas (`Cannot resolve method 'thenReturn(List<E>)'`). La parade
    // est `doReturn(...).when(mock).getX()` (non typé au call-site).
    @Test
    fun `Bug RR — CONSTRAINTS includes doReturn rule for type-variable returns`() {
        val output = buildAnyPrompt()
        assertTrue(output.contains("# Type-variable returns — use doReturn"),
            "Bug RR : section dédiée doReturn pour retours à variable de type attendue")
        assertTrue(output.contains("thenReturn(List<E>)"),
            "Bug RR : l'erreur de compile concrète doit être citée")
        assertTrue(output.contains("doReturn(") && output.contains(".when("),
            "Bug RR : la forme doReturn(...).when(mock) doit être donnée en exemple")
        assertTrue(output.contains("element type") || output.contains("ELEMENT type"),
            "Bug RR : guidance sur le choix du type d'élément depuis le source attendue")
        assertTrue(output.contains("DECISION RULE") &&
            output.contains("Methods to stub on"),
            "Bug RR V1.4.7 : la règle de décision actionnable (getter listé ? sinon " +
                "doReturn) doit être présente — le déclencheur ne dépend plus de savoir " +
                "que le retour est List<E>")
    }
}

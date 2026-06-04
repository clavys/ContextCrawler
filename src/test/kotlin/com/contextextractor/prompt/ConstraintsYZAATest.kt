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
}

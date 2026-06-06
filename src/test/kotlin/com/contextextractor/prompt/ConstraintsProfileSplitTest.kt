package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptBuilder
import com.contextextractor.core.prompt.constraints.BaseConstraints
import com.contextextractor.core.prompt.constraints.NoTuningProfile
import com.contextextractor.core.prompt.constraints.Qwen36b35bProfile
import com.contextextractor.core.prompt.constraints.QwenTuningConstraints
import com.contextextractor.core.prompt.constraints.resolveConstraintsProfile
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.Fixtures
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Phase 5 — verrous du split CONSTRAINTS : Base universel vs tuning Qwen.
// Cf RAPPORT_CONTEXT §9.9.
class ConstraintsProfileSplitTest {

    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()

    private fun buildPrompt(builder: PromptBuilder): String {
        val fake = Fixtures.case91()
        val sut = fake.resolveClass("com.testproject.case91.OrderService")!!
        val target = fake.listMethodsOf("com.testproject.case91.OrderService")
            .single { it.name == "calculate" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        return builder.build(mapper.map(result))
    }

    @Test
    fun `Qwen profile includes Y Z AA DD tuning sections`() {
        val out = buildPrompt(PromptBuilder.defaultPipeline(Qwen36b35bProfile))
        assertTrue(out.contains("# Checked exceptions on test methods (Bug Y)"),
            "Qwen profile : section Bug Y attendue")
        assertTrue(out.contains("# Typed Collections in stubs (Bug Z)"),
            "Qwen profile : section Bug Z attendue")
        assertTrue(out.contains("# Verifying calls that receive in-body `new` instances (Bug AA)"),
            "Qwen profile : section Bug AA attendue")
        assertTrue(out.contains("# Building instances for stub return values (Bug DD)"),
            "Qwen profile : section Bug DD attendue")
    }

    @Test
    fun `NoTuning profile omits Y Z AA DD tuning sections`() {
        val out = buildPrompt(PromptBuilder.defaultPipeline(NoTuningProfile))
        assertFalse(out.contains("# Checked exceptions on test methods (Bug Y)"),
            "NoTuning profile : Bug Y NE doit PAS être présent")
        assertFalse(out.contains("# Typed Collections in stubs (Bug Z)"),
            "NoTuning profile : Bug Z NE doit PAS être présent")
        assertFalse(out.contains("# Verifying calls that receive in-body `new` instances (Bug AA)"),
            "NoTuning profile : Bug AA NE doit PAS être présent")
        assertFalse(out.contains("# Building instances for stub return values (Bug DD)"),
            "NoTuning profile : Bug DD NE doit PAS être présent")
    }

    @Test
    fun `NoTuning profile still includes base universal rules`() {
        val out = buildPrompt(PromptBuilder.defaultPipeline(NoTuningProfile))
        // Les règles universelles doivent rester quel que soit le profil.
        assertTrue(out.contains("# Critical rules — verification checklist"),
            "Base : Critical rules toujours présent")
        assertTrue(out.contains("# Test class"),
            "Base : Test class section toujours présent")
        assertTrue(out.contains("# Style (strict)"),
            "Base : Style section toujours présent")
        assertTrue(out.contains("# Hard prohibitions"),
            "Base : Hard prohibitions toujours présent")
        assertTrue(out.contains("# Compilation contract"),
            "Base : Compilation contract toujours présent")
    }

    @Test
    fun `default pipeline preserves Qwen behavior for V1_1 backward compat`() {
        // Verrou pivot : `defaultPipeline()` sans argument DOIT continuer à
        // produire les patches Y/Z/AA/DD (les tests existants en dépendent).
        val out = buildPrompt(PromptBuilder.defaultPipeline())
        assertTrue(out.contains("(Bug Y)") && out.contains("(Bug Z)") &&
            out.contains("(Bug AA)") && out.contains("(Bug DD)"),
            "defaultPipeline() défaut = Qwen, doit garder Bug Y/Z/AA/DD")
    }

    @Test
    fun `resolveConstraintsProfile maps known ids and falls back to qwen for unknown`() {
        assertEquals(Qwen36b35bProfile, resolveConstraintsProfile("qwen"))
        assertEquals(Qwen36b35bProfile, resolveConstraintsProfile("QWEN"))  // case-insensitive
        assertEquals(Qwen36b35bProfile, resolveConstraintsProfile(null))   // null → Qwen
        assertEquals(Qwen36b35bProfile, resolveConstraintsProfile(""))     // empty → Qwen
        assertEquals(NoTuningProfile, resolveConstraintsProfile("none"))
        assertEquals(NoTuningProfile, resolveConstraintsProfile("no-tuning"))
        assertEquals(NoTuningProfile, resolveConstraintsProfile("baseline"))
        // Fallback safe : id inconnu → Qwen (jamais NoTuning par accident).
        assertEquals(Qwen36b35bProfile, resolveConstraintsProfile("gpt-5"))
    }

    @Test
    fun `BaseConstraints does not contain Qwen tuning bugs`() {
        // Verrou structurel : la base PURE ne doit pas contenir les bugs Qwen-
        // specific. Si quelqu'un ajoute du contenu par erreur, ce test crashe.
        assertFalse(BaseConstraints.TEXT.contains("(Bug Y)"))
        assertFalse(BaseConstraints.TEXT.contains("(Bug Z)"))
        assertFalse(BaseConstraints.TEXT.contains("(Bug AA)"))
        assertFalse(BaseConstraints.TEXT.contains("(Bug DD)"))
    }

    @Test
    fun `QwenTuningConstraints only contains the 4 expected sections`() {
        val text = QwenTuningConstraints.TEXT
        assertTrue(text.contains("(Bug Y)"))
        assertTrue(text.contains("(Bug Z)"))
        assertTrue(text.contains("(Bug AA)"))
        assertTrue(text.contains("(Bug DD)"))
        // Garde-fou : pas de pollution avec des règles universelles
        // (le test échoue si quelqu'un déplace par erreur du contenu Base).
        assertFalse(text.contains("# Critical rules"))
        assertFalse(text.contains("# Test class"))
        assertFalse(text.contains("# Hard prohibitions"))
    }
}

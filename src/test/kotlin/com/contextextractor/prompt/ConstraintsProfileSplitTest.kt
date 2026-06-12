package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.config.ContextExtractorConfig.MockitoStrictness
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
    fun `Qwen profile includes Y Z AA DD EE FF GG tuning sections`() {
        val out = buildPrompt(PromptBuilder.defaultPipeline(Qwen36b35bProfile))
        assertTrue(out.contains("# Checked exceptions on test methods (Bug Y)"),
            "Qwen profile : section Bug Y attendue")
        assertTrue(out.contains("# Typed Collections in stubs (Bug Z)"),
            "Qwen profile : section Bug Z attendue")
        assertTrue(out.contains("# Verifying calls that receive in-body `new` instances (Bug AA)"),
            "Qwen profile : section Bug AA attendue")
        assertTrue(out.contains("# Building instances for stub return values (Bug DD)"),
            "Qwen profile : section Bug DD attendue")
        assertTrue(out.contains("# Sorting/comparing on mocks (Bug EE)"),
            "Qwen profile : section Bug EE (Astrea R3-C) attendue")
        assertTrue(out.contains("# Avoid unnecessary stubbings (Bug FF — Mockito strict)"),
            "Qwen profile : section Bug FF (Astrea R4-NEW-2) attendue")
        assertTrue(out.contains("# Identity assertions on rebuilt collections (Bug GG)"),
            "Qwen profile : section Bug GG (Astrea case 4.1 isSameAs) attendue")
        assertTrue(out.contains("# MockedStatic default-value trap (Bug HH)"),
            "Qwen profile : section Bug HH (Astrea case 4.2 mockStatic) attendue")
    }

    @Test
    fun `NoTuning profile omits Y Z AA DD EE FF tuning sections`() {
        val out = buildPrompt(PromptBuilder.defaultPipeline(NoTuningProfile))
        assertFalse(out.contains("# Checked exceptions on test methods (Bug Y)"),
            "NoTuning profile : Bug Y NE doit PAS être présent")
        assertFalse(out.contains("# Typed Collections in stubs (Bug Z)"),
            "NoTuning profile : Bug Z NE doit PAS être présent")
        assertFalse(out.contains("# Verifying calls that receive in-body `new` instances (Bug AA)"),
            "NoTuning profile : Bug AA NE doit PAS être présent")
        assertFalse(out.contains("# Building instances for stub return values (Bug DD)"),
            "NoTuning profile : Bug DD NE doit PAS être présent")
        assertFalse(out.contains("# Sorting/comparing on mocks (Bug EE)"),
            "NoTuning profile : Bug EE NE doit PAS être présent")
        assertFalse(out.contains("# Avoid unnecessary stubbings (Bug FF — Mockito strict)"),
            "NoTuning profile : Bug FF NE doit PAS être présent")
        assertFalse(out.contains("# Identity assertions on rebuilt collections (Bug GG)"),
            "NoTuning profile : Bug GG NE doit PAS être présent")
        assertFalse(out.contains("# MockedStatic default-value trap (Bug HH)"),
            "NoTuning profile : Bug HH NE doit PAS être présent")
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
        // produire les patches Y/Z/AA/DD/EE/FF (les tests existants en dépendent).
        val out = buildPrompt(PromptBuilder.defaultPipeline())
        assertTrue(out.contains("(Bug Y)") && out.contains("(Bug Z)") &&
            out.contains("(Bug AA)") && out.contains("(Bug DD)") &&
            out.contains("(Bug EE)") && out.contains("(Bug FF"),
            "defaultPipeline() défaut = Qwen, doit garder Bug Y/Z/AA/DD/EE/FF")
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
        assertFalse(BaseConstraints.TEXT.contains("(Bug EE)"))
        assertFalse(BaseConstraints.TEXT.contains("(Bug FF"))
        assertFalse(BaseConstraints.TEXT.contains("(Bug GG)"))
        assertFalse(BaseConstraints.TEXT.contains("(Bug HH)"))
    }

    // ── V1.4 — mockitoStrictness team policy ────────────────────────────────

    @Test
    fun `default mockitoStrictness STRICT_STUBS does not inject any Mockito strictness block`() {
        // Verrou rétro-compat : par défaut, le prompt V1.x est inchangé.
        val out = buildPrompt(PromptBuilder.defaultPipeline(NoTuningProfile))
        assertFalse(out.contains("# Mockito strictness (team policy)"),
            "STRICT_STUBS = pas de signal, pas de section parasite")
        assertFalse(out.contains("@MockitoSettings"),
            "STRICT_STUBS = pas d'annotation à demander")
    }

    @Test
    fun `WARN strictness injects @MockitoSettings instruction with imports`() {
        // Cas Astrea — convention équipe : autorise les stubs inutiles.
        val out = buildPrompt(PromptBuilder.defaultPipeline(NoTuningProfile, MockitoStrictness.WARN))
        assertTrue(out.contains("# Mockito strictness (team policy)"),
            "section dédiée doit apparaître")
        assertTrue(out.contains("@MockitoSettings(strictness = Strictness.WARN)"),
            "annotation exacte attendue dans le prompt")
        assertTrue(out.contains("org.mockito.junit.jupiter.MockitoSettings"),
            "import MockitoSettings demandé au LLM")
        assertTrue(out.contains("org.mockito.quality.Strictness"),
            "import Strictness demandé au LLM")
    }

    @Test
    fun `LENIENT strictness injects the same block with LENIENT value`() {
        val out = buildPrompt(PromptBuilder.defaultPipeline(NoTuningProfile, MockitoStrictness.LENIENT))
        assertTrue(out.contains("@MockitoSettings(strictness = Strictness.LENIENT)"),
            "LENIENT doit produire la même structure avec la valeur LENIENT")
    }

    @Test
    fun `strictness block composes with Qwen tuning without losing either`() {
        // Verrou composition : on doit pouvoir avoir Qwen patches + strictness simultanément.
        val out = buildPrompt(PromptBuilder.defaultPipeline(Qwen36b35bProfile, MockitoStrictness.WARN))
        assertTrue(out.contains("(Bug Y)"),
            "Qwen profile préservé même quand strictness est injecté")
        assertTrue(out.contains("@MockitoSettings(strictness = Strictness.WARN)"),
            "strictness préservé même avec Qwen profile")
    }

    @Test
    fun `BaseConstraints does not contain any Mockito strictness directive`() {
        // Verrou structurel symétrique au verrou « pas de Bug X dans Base » :
        // la base PURE ne doit pas contenir le bloc strictness (injecté seulement
        // si la team policy le demande).
        assertFalse(BaseConstraints.TEXT.contains("# Mockito strictness"))
        assertFalse(BaseConstraints.TEXT.contains("@MockitoSettings"))
    }

    @Test
    fun `QwenTuningConstraints does not advertise the team-policy strictness section`() {
        // La section EN-TÊTE « # Mockito strictness (team policy) » est unique
        // au compositeur V1.4 — ne doit pas apparaître dans le tuning Qwen.
        // N.B. : QwenTuningConstraints Bug FF mentionne `@MockitoSettings`
        // dans une directive d'interdiction (« NEVER add LENIENT globally »),
        // ce qui est distinct de la directive d'ajout V1.4 (« Annotate with
        // strictness=WARN »). Conflit potentiel uniquement si team-policy =
        // LENIENT + profil Qwen actif — limite connue, documentée §6bis.
        assertFalse(QwenTuningConstraints.TEXT.contains("# Mockito strictness (team policy)"),
            "header team-policy ne doit pas être dans le tuning Qwen")
        assertFalse(QwenTuningConstraints.TEXT.contains("Annotate the test class with @MockitoSettings"),
            "la directive d'ajout V1.4 ne doit pas être dans le tuning Qwen")
    }

    @Test
    fun `QwenTuningConstraints only contains the 8 expected sections`() {
        val text = QwenTuningConstraints.TEXT
        assertTrue(text.contains("(Bug Y)"))
        assertTrue(text.contains("(Bug Z)"))
        assertTrue(text.contains("(Bug AA)"))
        assertTrue(text.contains("(Bug DD)"))
        assertTrue(text.contains("(Bug EE)"))
        assertTrue(text.contains("(Bug FF"))
        assertTrue(text.contains("(Bug GG)"))
        assertTrue(text.contains("(Bug HH)"))
        // Garde-fou : pas de pollution avec des règles universelles
        // (le test échoue si quelqu'un déplace par erreur du contenu Base).
        assertFalse(text.contains("# Critical rules"))
        assertFalse(text.contains("# Test class"))
        assertFalse(text.contains("# Hard prohibitions"))
    }
}

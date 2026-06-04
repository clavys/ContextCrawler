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

// Bug DD — Section CONSTRAINTS qui demande au LLM de privilégier
// `Mockito.mock(Type.class)` à `new Type(...)` quand il a besoin d'une
// instance d'un type non listé dans `# Mocks` (typiquement le contenu d'un
// `List<T>` retourné par un stub, ou un type évincé du budget de mocks).
//
// Cas prod : `AutoCompletionUtils.autocompleterTexteSurListe(...)` retourne
// `List<ElementsListeDeroulante>`. Le type `ElementsListeDeroulante` a été
// évincé (cf truncation "drop mock"). Le LLM Qwen 3.6 35B tentait
// `new ElementsListeDeroulante()` mais le constructeur exige (String, String)
// → compile error. Avec Bug DD : le LLM sait qu'il doit faire
// `mock(ElementsListeDeroulante.class)` à la place.
class ConstraintsBugDDTest {

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
    fun `Bug DD — CONSTRAINTS includes mock-over-new rule for non-mocked types`() {
        val output = buildAnyPrompt()
        assertTrue(output.contains("# Building instances for stub return values"),
            "Bug DD : section dédiée Mockito.mock(Type.class) attendue")
        assertTrue(output.contains("mock(Type.class)") ||
            output.contains("Mockito.mock(Type.class)"),
            "Bug DD : référence explicite à `mock(Type.class)` attendue. " +
                "Output:\n${output.substringAfter("# Building instances").substringBefore("# ").take(800)}")
        assertTrue(output.contains("DO NOT call") &&
            output.contains("new "),
            "Bug DD : interdiction explicite de `new` sur types non-mockés attendue")
    }

    @Test
    fun `Bug DD — CONSTRAINTS mentions truncation drop mock case`() {
        // Verrou : la règle doit explicitement gérer le cas des types évincés
        // listés dans truncation reasons (cf cas production).
        val output = buildAnyPrompt()
        val section = output.substringAfter("# Building instances for stub return values",
            "").substringBefore("\n# ", "").take(1500)
        assertTrue(section.contains("truncation") ||
            section.contains("drop mock"),
            "Bug DD : la règle doit mentionner le cas des mocks évincés. " +
                "Section:\n$section")
    }

    @Test
    fun `Bug DD — CONSTRAINTS provides concrete invalid-vs-valid example`() {
        // Le LLM Qwen 3.6 répond mieux aux exemples concrets qu'aux règles
        // abstraites — on vérifie qu'on lui donne les deux exemples (avant/après).
        val output = buildAnyPrompt()
        val section = output.substringAfter("# Building instances for stub return values",
            "").substringBefore("\n# ", "").take(2000)
        assertTrue(section.contains("EXAMPLE invalid"),
            "Bug DD : un exemple `EXAMPLE invalid` attendu. Section:\n$section")
        assertTrue(section.contains("EXAMPLE valid"),
            "Bug DD : un exemple `EXAMPLE valid` attendu. Section:\n$section")
        assertTrue(section.contains("mock(") && section.contains(".class)"),
            "Bug DD : l'exemple valide doit utiliser `mock(X.class)`. Section:\n$section")
    }
}

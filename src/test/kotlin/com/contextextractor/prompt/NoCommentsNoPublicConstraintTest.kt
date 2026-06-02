package com.contextextractor.prompt

import com.contextextractor.core.model.BasicContextNode
import com.contextextractor.core.model.ContextNode
import com.contextextractor.core.model.ContextTree
import com.contextextractor.core.model.MetaKeys
import com.contextextractor.core.model.NodeKind
import com.contextextractor.core.prompt.PromptContext
import com.contextextractor.core.prompt.meta.LayerKind
import com.contextextractor.core.prompt.stages.LayerCompositionStage
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Verrou des contraintes prompt étape 5 — pas de commentaires + pas de `public`.
//
// Demande utilisateur explicite : le LLM ne doit pas générer de commentaires
// (qui polluent le code) ni utiliser `public` sur les méthodes de test
// (JUnit 5 supporte package-private — `public` est inutile depuis JUnit 5).
class NoCommentsNoPublicConstraintTest {

    private fun makeTree(): ContextTree {
        val root: ContextNode = BasicContextNode(
            id = "target:com.acme.OrderService#calculate()",
            kind = NodeKind.TARGET_METHOD,
            title = "com.acme.OrderService#calculate()",
            metadata = mapOf(MetaKeys.METHOD_CANONICAL to "calculate()")
        )
        return ContextTree(
            root = root,
            index = mapOf(root.id to root),
            byKind = mapOf(NodeKind.TARGET_METHOD to listOf(root))
        )
    }

    private fun renderedPrompt(): String {
        val pc = PromptContext(tree = makeTree())
        pc.layers[LayerKind.CONTEXT] = "CONTEXT_BODY"
        LayerCompositionStage().apply(pc)
        return pc.finalText ?: error("finalText absent")
    }

    @Test
    fun `SYSTEM layer instructs no comments`() {
        val out = renderedPrompt()
        assertTrue(
            out.contains("NO comments inside the code"),
            "SYSTEM doit instruire : pas de commentaires dans le code généré"
        )
    }

    @Test
    fun `SYSTEM layer instructs package-private tests`() {
        val out = renderedPrompt()
        assertTrue(
            out.contains("package-private") && out.contains("no `public`"),
            "SYSTEM doit interdire `public` sur les méthodes de test"
        )
    }

    @Test
    fun `CONSTRAINTS section repeats both rules in dedicated Style block`() {
        val out = renderedPrompt()
        assertTrue(out.contains("# Style (strict)"),
            "section Style doit exister dans CONSTRAINTS")
        assertTrue(out.contains("NO comments anywhere in the file"),
            "règle anti-commentaires explicite dans CONSTRAINTS")
        assertTrue(out.contains("Test class and test methods MUST be package-private"),
            "règle package-private explicite dans CONSTRAINTS")
    }

    @Test
    fun `old french-comment rule is gone`() {
        // Avant l'étape 5 : "All comments inside the generated code must be
        // written in French." → contradictoire avec « pas de commentaire ».
        val out = renderedPrompt()
        assertFalse(
            out.contains("All comments inside the generated code must be written in French"),
            "l'ancienne règle « commentaires en français » doit avoir disparu"
        )
    }

    @Test
    fun `CONSTRAINTS forbids snake_case and hyphen in method names`() {
        val out = renderedPrompt()
        assertTrue(out.contains("Method naming: camelCase only"),
            "règle nommage méthodes camelCase explicite")
        assertTrue(out.contains("NO underscore") && out.contains("NO hyphen"),
            "interdictions `_` et `-` explicites")
    }

    @Test
    fun `CONSTRAINTS prescribes explicit variable naming matching the type`() {
        val out = renderedPrompt()
        assertTrue(out.contains("variable of type `TypeName` MUST be named `typeName`"),
            "règle nommage variable = camelCase du type")
        assertTrue(out.contains("PageDataDTO pageDataDTO"),
            "exemple explicite PageDataDTO pageDataDTO")
        assertTrue(out.contains("No abbreviations"),
            "abréviations interdites")
    }

    @Test
    fun `CONSTRAINTS includes anti-hallucination contract for SUT method stubbing`() {
        val out = renderedPrompt()
        assertTrue(out.contains("Anti-hallucination contract"),
            "section anti-hallucination explicite")
        assertTrue(out.contains("NEVER stub a method on the SUT"),
            "verrou anti-hallucination sur les méthodes du SUT")
        assertTrue(out.contains("Méthodes à stubber par spy"),
            "renvoi explicite à la section spy autorisée")
    }

    @Test
    fun `CONSTRAINTS requires @Test annotation on every test method`() {
        // Bug F — sans cette règle, le LLM oublie systematiquement @Test
        // et les méthodes sont silencieusement skipped par JUnit (0 coverage).
        val out = renderedPrompt()
        assertTrue(out.contains("EVERY test method MUST be annotated `@Test`"),
            "règle @Test obligatoire explicite")
        assertTrue(out.contains("0-coverage report") || out.contains("invisible to JUnit"),
            "explication du risque (test silencieusement skipped)")
    }

    @Test
    fun `CONSTRAINTS lists required static imports for Mockito methods`() {
        // Bug H — le LLM oublie souvent `when` et `eq` imports, ce qui casse
        // la compilation. Lister explicitement les imports requis.
        val out = renderedPrompt()
        assertTrue(out.contains("# Required static imports"),
            "section dédiée aux imports statiques")
        assertTrue(out.contains("import static org.mockito.Mockito.when"),
            "import when explicité")
        assertTrue(out.contains("import static org.mockito.ArgumentMatchers.eq"),
            "import eq explicité")
        assertTrue(out.contains("import static org.mockito.Mockito.verify"),
            "import verify explicité")
        assertTrue(out.contains("import static org.assertj.core.api.Assertions.assertThat"),
            "import AssertJ explicité")
    }

    @Test
    fun `CONSTRAINTS requires FQN verbatim copy from CONTEXT for imports`() {
        // Bug J — le LLM hallucine les packages d'import par analogie avec
        // d'autres types listés (ex : imports en `.dto.` au lieu de `.service.`).
        val out = renderedPrompt()
        assertTrue(out.contains("Imports — strict FQN copy") ||
                   out.contains("FQN VERBATIM"),
            "section dédiée à la copie verbatim des FQN d'import")
        assertTrue(out.contains("never invent or shorten") ||
                   out.contains("NEVER shorten or substitute"),
            "interdiction explicite d'inventer ou raccourcir les paths")
        assertTrue(out.contains("character-by-character") || out.contains("hallucination"),
            "exemple ou mention du risque d'hallucination")
    }

    @Test
    fun `CONSTRAINTS contains INVALID-VALID example for mock instantiation`() {
        // Bug K — la règle "NO instantiation of types listed under Mocks"
        // est noyée dans Hard prohibitions. Renforcer avec un INVALID/VALID.
        val out = renderedPrompt()
        assertTrue(out.contains("INVALID:") && out.contains("VALID:"),
            "section INVALID/VALID explicite pour l'instantiation de mock")
        assertTrue(out.contains("@Mock PageDataDTO pageDataDTO") ||
                   out.contains("@Mock"),
            "exemple VALID avec @Mock")
        assertTrue(out.contains("new PageDataDTO()") ||
                   out.contains("most violated rule"),
            "exemple INVALID ou avertissement explicite")
    }

    @Test
    fun `SYSTEM layer uses R1 R2 R3 R4 markers for the four most-violated rules`() {
        // Bug L — les règles violées en boucle (manque de @Test, public, comments,
        // new sur mock) sont remontées au sommet du SYSTEM avec marqueurs visuels
        // [R1]..[R4] pour maximiser la chance de lecture par le LLM.
        val out = renderedPrompt()
        assertTrue(out.contains("[R1]") && out.contains("[R2]") &&
                   out.contains("[R3]") && out.contains("[R4]"),
            "SYSTEM doit numéroter les 4 règles critiques avec [R1]..[R4]")
        assertTrue(out.contains("CRITICAL RULES"),
            "SYSTEM doit annoncer la section « CRITICAL RULES »")
    }

    @Test
    fun `SYSTEM places critical rules block BEFORE the CONTEXT marker`() {
        // L'idée : les 4 règles critiques doivent être lues AVANT le CONTEXT
        // (donc encore plus tôt que CONSTRAINTS) pour amorcer le LLM.
        val out = renderedPrompt()
        val r1Idx = out.indexOf("[R1]")
        val contextIdx = out.indexOf("=== CONTEXT ===")
        assertTrue(r1Idx in 0 until contextIdx,
            "Le bloc CRITICAL RULES doit précéder la section CONTEXT. " +
                "r1=$r1Idx, context=$contextIdx")
    }

    @Test
    fun `CONSTRAINTS opens with a verification checklist repeating R1 to R4`() {
        // La checklist transforme les règles en actions (« scan », « search »,
        // « verify ») pour pousser le LLM à un audit final avant output.
        val out = renderedPrompt()
        assertTrue(out.contains("# Critical rules"),
            "CONSTRAINTS doit ouvrir avec une section « # Critical rules »")
        assertTrue(out.contains("verification checklist"),
            "Le titre doit indiquer que c'est une checklist de vérification")
        assertTrue(out.contains("- [R1]") && out.contains("- [R2]") &&
                   out.contains("- [R3]") && out.contains("- [R4]"),
            "La checklist doit lister explicitement R1..R4")
        // Action verbs présents — pousse le LLM à scanner avant de sortir.
        assertTrue(out.contains("Scan:") || out.contains("Search for"),
            "La checklist doit contenir des verbes d'action (Scan/Search)")
    }

    @Test
    fun `CONSTRAINTS critical rules section appears BEFORE Test class section`() {
        // Bug L — ordre par criticité. La checklist doit être la toute première
        // section de CONSTRAINTS, avant même la description de la classe à générer.
        val out = renderedPrompt()
        val critIdx = out.indexOf("# Critical rules")
        val testClassIdx = out.indexOf("# Test class")
        assertTrue(critIdx in 0 until testClassIdx,
            "« # Critical rules » doit précéder « # Test class ». " +
                "critical=$critIdx, testClass=$testClassIdx")
    }

    @Test
    fun `Test class section no longer duplicates the @Test annotation rule`() {
        // Bug L — la règle @Test est désormais dans Critical rules (top).
        // La répétition dans `# Test class` est supprimée pour réduire le bruit.
        val out = renderedPrompt()
        val testClassIdx = out.indexOf("# Test class")
        val styleIdx = out.indexOf("# Style (strict)")
        assertTrue(testClassIdx in 0 until styleIdx,
            "« # Test class » et « # Style » doivent exister dans cet ordre")
        val testClassBlock = out.substring(testClassIdx, styleIdx)
        // La règle @Test ne doit plus figurer dans le bloc Test class.
        assertFalse(
            testClassBlock.contains("EVERY test method MUST be annotated"),
            "La règle @Test doit être déplacée vers # Critical rules, pas dupliquée dans # Test class"
        )
    }

    @Test
    fun `untestable section no longer asks for a Javadoc comment`() {
        // L'ancienne règle : "Add a Javadoc comment quoting the reason..."
        // Contradictoire avec « zéro commentaire » → doit être remplacée.
        val out = renderedPrompt()
        assertFalse(
            out.contains("Add a Javadoc comment"),
            "la règle Javadoc sur untestable doit avoir disparu — pas de commentaire"
        )
    }
}

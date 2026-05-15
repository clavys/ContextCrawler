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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 5-α — vérifie que LayerCompositionStage :
//   • substitue les placeholders [TargetClassName] / [targetMethodName] /
//     [same package as target class] dans CONSTRAINTS et INSTRUCTION ;
//   • garde le bloc `{IF tree.tronque == true}…{END IF}` quand truncated=true,
//     en injectant {raisonsTroncature} ;
//   • supprime le bloc IF entièrement quand truncated=false ;
//   • inclut === ADDITIONAL INSTRUCTIONS === seulement si USER_ENRICHMENT non vide ;
//   • assemble dans l'ordre attendu PROMPT_FORMAT.md ;
//   • jette explicitement si la layer CONTEXT n'a pas été produite en amont.
class LayerCompositionStageTest {

    private fun makeTree(
        classFqn: String = "com.acme.OrderService",
        canonical: String = "calculate(java.lang.Long)",
        truncated: Boolean = false,
        truncationReasons: List<String> = emptyList()
    ): ContextTree {
        val root: ContextNode = BasicContextNode(
            id = "target:$classFqn#$canonical",
            kind = NodeKind.TARGET_METHOD,
            title = "$classFqn#$canonical",
            metadata = mapOf(MetaKeys.METHOD_CANONICAL to canonical)
        )
        return ContextTree(
            root = root,
            index = mapOf(root.id to root),
            byKind = mapOf(NodeKind.TARGET_METHOD to listOf(root)),
            truncated = truncated,
            truncationReasons = truncationReasons
        )
    }

    private fun ctx(tree: ContextTree, contextLayer: String = "CONTEXT_BODY"): PromptContext =
        PromptContext(tree = tree).also { it.layers[LayerKind.CONTEXT] = contextLayer }

    @Test
    fun `substitutes class name and method name and package`() {
        val pc = ctx(makeTree())
        LayerCompositionStage().apply(pc)
        val out = pc.finalText!!
        assertTrue(out.contains("Class name: OrderServiceTest"),
            "[TargetClassName] doit être remplacé par le nom court (sans package)")
        assertTrue(out.contains("Package: com.acme"),
            "[same package as target class] doit être remplacé par le package")
        assertTrue(out.contains("Cover the nominal path of calculate"),
            "[targetMethodName] doit être remplacé par le nom de la méthode (sans args)")
        assertTrue(out.contains("Generate the complete Java test class for method calculate"),
            "INSTRUCTION doit aussi recevoir [targetMethodName]")
        assertTrue(out.contains("in class OrderService."),
            "INSTRUCTION doit aussi recevoir [TargetClassName]")
    }

    @Test
    fun `IF tree-tronque block is kept when truncated and reasons are injected`() {
        val pc = ctx(makeTree(
            truncated = true,
            truncationReasons = listOf("budget tokens", "profondeur max")
        ))
        LayerCompositionStage().apply(pc)
        val out = pc.finalText!!
        assertTrue(out.contains("WARNING: extracted context is partial."),
            "le bloc tronqué doit apparaître quand truncated=true")
        assertTrue(out.contains("Reasons: budget tokens, profondeur max"),
            "{raisonsTroncature} doit être joint par ', '")
        // Les marqueurs `{IF ...}` / `{END IF}` ne doivent JAMAIS fuiter dans
        // la sortie finale.
        assertFalse(out.contains("{IF "), "marker {IF ...} ne doit pas fuiter")
        assertFalse(out.contains("{END IF}"), "marker {END IF} ne doit pas fuiter")
    }

    @Test
    fun `IF tree-tronque block is removed entirely when not truncated`() {
        val pc = ctx(makeTree(truncated = false))
        LayerCompositionStage().apply(pc)
        val out = pc.finalText!!
        assertFalse(out.contains("WARNING: extracted context is partial."),
            "le bloc tronqué doit disparaître quand truncated=false")
        assertFalse(out.contains("{raisonsTroncature}"),
            "le placeholder ne doit jamais fuiter (vivait dans le bloc IF)")
        assertFalse(out.contains("{IF "), "marker {IF ...} ne doit pas fuiter")
    }

    @Test
    fun `USER_ENRICHMENT section is added only when non-blank`() {
        val pcWithout = ctx(makeTree())
        LayerCompositionStage().apply(pcWithout)
        assertFalse(pcWithout.finalText!!.contains("=== ADDITIONAL INSTRUCTIONS ==="),
            "section ADDITIONAL INSTRUCTIONS absente quand pas de USER_ENRICHMENT")

        val pcWith = ctx(makeTree())
        pcWith.layers[LayerKind.USER_ENRICHMENT] = "Use BDDMockito style"
        LayerCompositionStage().apply(pcWith)
        val out = pcWith.finalText!!
        assertTrue(out.contains("=== ADDITIONAL INSTRUCTIONS ==="),
            "section ADDITIONAL INSTRUCTIONS présente quand USER_ENRICHMENT non vide")
        assertTrue(out.contains("Use BDDMockito style"),
            "le contenu USER_ENRICHMENT doit apparaître après le marker")
    }

    @Test
    fun `USER_ENRICHMENT blank string is treated as absent`() {
        val pc = ctx(makeTree())
        pc.layers[LayerKind.USER_ENRICHMENT] = "   \n  "
        LayerCompositionStage().apply(pc)
        assertFalse(pc.finalText!!.contains("=== ADDITIONAL INSTRUCTIONS ==="),
            "USER_ENRICHMENT blanc = pas de section (évite un bloc vide dans le prompt)")
    }

    @Test
    fun `assembled order is SYSTEM then CONTEXT then CONSTRAINTS then INSTRUCTION`() {
        val pc = ctx(makeTree(), contextLayer = "RENDERED_CONTEXT")
        LayerCompositionStage().apply(pc)
        val out = pc.finalText!!
        val iSystem = out.indexOf("expert Java unit test writer")
        val iContextMarker = out.indexOf("=== CONTEXT ===")
        val iContextBody = out.indexOf("RENDERED_CONTEXT")
        val iConstraintsMarker = out.indexOf("=== CONSTRAINTS ===")
        val iInstructionMarker = out.indexOf("=== INSTRUCTION ===")
        assertTrue(iSystem in 0..iContextMarker,
            "SYSTEM doit précéder === CONTEXT === (PROMPT_FORMAT.md §Full Prompt Structure)")
        assertTrue(iContextMarker < iContextBody,
            "le marker === CONTEXT === doit précéder le contenu rendu")
        assertTrue(iContextBody < iConstraintsMarker,
            "CONTEXT doit précéder === CONSTRAINTS ===")
        assertTrue(iConstraintsMarker < iInstructionMarker,
            "CONSTRAINTS doit précéder === INSTRUCTION ===")
    }

    @Test
    fun `missing CONTEXT layer raises explicitly`() {
        val pc = PromptContext(tree = makeTree())
        // Pas de CONTEXT pré-rempli → ContextRenderStage n'a pas tourné.
        val ex = assertThrows(IllegalStateException::class.java) {
            LayerCompositionStage().apply(pc)
        }
        assertTrue(ex.message!!.contains("CONTEXT"),
            "le message doit pointer la layer CONTEXT comme manquante")
    }

    @Test
    fun `caller-supplied SYSTEM template overrides the default`() {
        val pc = ctx(makeTree())
        pc.layers[LayerKind.SYSTEM] = "CUSTOM SYSTEM PROMPT"
        LayerCompositionStage().apply(pc)
        val out = pc.finalText!!
        assertTrue(out.contains("CUSTOM SYSTEM PROMPT"))
        assertFalse(out.contains("expert Java unit test writer"),
            "le SYSTEM par défaut doit être éclipsé par celui pré-rempli")
    }
}

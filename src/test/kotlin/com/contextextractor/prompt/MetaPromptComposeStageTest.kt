package com.contextextractor.prompt

import com.contextextractor.core.model.BasicContextNode
import com.contextextractor.core.model.ContextTree
import com.contextextractor.core.model.NodeKind
import com.contextextractor.core.prompt.PromptContext
import com.contextextractor.core.prompt.meta.LayerKind
import com.contextextractor.core.prompt.stages.MetaPromptComposeStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Sous-étape 5-α — vérifie que MetaPromptComposeStage :
//   • laisse les layers intactes quand aucun partial n'est livré (V1) ;
//   • résout `{{>name}}` en présence d'une map de partials ;
//   • laisse intact un `{{>nomInconnu}}` (signal au lieu de trou silencieux).
class MetaPromptComposeStageTest {

    private fun ctxWithLayer(kind: LayerKind, text: String): PromptContext {
        val tree = ContextTree(
            root = BasicContextNode(id = "target:X", kind = NodeKind.TARGET_METHOD, title = "X"),
            index = mapOf("target:X" to BasicContextNode(
                id = "target:X", kind = NodeKind.TARGET_METHOD, title = "X")),
            byKind = emptyMap()
        )
        return PromptContext(tree = tree).also { it.layers[kind] = text }
    }

    @Test
    fun `passthrough when no partials configured`() {
        val ctx = ctxWithLayer(LayerKind.SYSTEM, "Bonjour {{>greeting}} fin")
        MetaPromptComposeStage().apply(ctx)
        assertEquals("Bonjour {{>greeting}} fin", ctx.layers[LayerKind.SYSTEM])
    }

    @Test
    fun `replaces partial when key matches`() {
        val ctx = ctxWithLayer(LayerKind.CONSTRAINTS, "Header :\n{{>junit5-rules}}\nFooter")
        val stage = MetaPromptComposeStage(partials = mapOf(
            "junit5-rules" to "- use @Test\n- use AssertJ"
        ))
        stage.apply(ctx)
        val output = ctx.layers[LayerKind.CONSTRAINTS]!!
        assertEquals("Header :\n- use @Test\n- use AssertJ\nFooter", output)
    }

    @Test
    fun `unknown partial is left as marker for visibility`() {
        // V1 : un partial inconnu ne doit PAS produire de trou silencieux.
        // Le marqueur reste, l'utilisateur le voit dans le prompt → corrige.
        val ctx = ctxWithLayer(LayerKind.INSTRUCTION, "ok {{>missing}} ok")
        MetaPromptComposeStage(partials = mapOf("autre" to "X")).apply(ctx)
        assertEquals("ok {{>missing}} ok", ctx.layers[LayerKind.INSTRUCTION])
    }

    @Test
    fun `tolerant of whitespace inside partial marker`() {
        val ctx = ctxWithLayer(LayerKind.SYSTEM, "A {{> name }} B")
        MetaPromptComposeStage(partials = mapOf("name" to "VAL")).apply(ctx)
        assertEquals("A VAL B", ctx.layers[LayerKind.SYSTEM])
    }
}

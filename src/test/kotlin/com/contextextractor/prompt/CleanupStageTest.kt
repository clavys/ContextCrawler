package com.contextextractor.prompt

import com.contextextractor.core.model.BasicContextNode
import com.contextextractor.core.model.ContextTree
import com.contextextractor.core.model.NodeKind
import com.contextextractor.core.prompt.PromptContext
import com.contextextractor.core.prompt.stages.CleanupStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 5-α — vérifie que CleanupStage :
//   • compacte 3+ sauts de ligne consécutifs en exactement 2 ;
//   • strip le whitespace en fin de ligne ;
//   • normalise CRLF → LF ;
//   • garantit un unique '\n' final sans queue blanche ;
//   • jette si finalText n'a pas été pré-rempli (LayerComposition oublié).
class CleanupStageTest {

    private fun ctxWith(finalText: String?): PromptContext {
        val root = BasicContextNode(id = "X", kind = NodeKind.TARGET_METHOD, title = "X")
        val tree = ContextTree(root = root, index = mapOf("X" to root), byKind = emptyMap())
        return PromptContext(tree = tree).also { it.finalText = finalText }
    }

    @Test
    fun `collapses three or more consecutive newlines to exactly two`() {
        val pc = ctxWith("a\n\n\n\nb\n\n\n\n\nc")
        CleanupStage().apply(pc)
        // Une seule ligne vide (= deux '\n' consécutifs) entre paragraphes.
        assertEquals("a\n\nb\n\nc\n", pc.finalText)
    }

    @Test
    fun `keeps single blank line untouched`() {
        val pc = ctxWith("para1\n\npara2")
        CleanupStage().apply(pc)
        assertEquals("para1\n\npara2\n", pc.finalText)
    }

    @Test
    fun `strips trailing whitespace per line`() {
        val pc = ctxWith("line1   \nline2\t \nline3")
        CleanupStage().apply(pc)
        assertEquals("line1\nline2\nline3\n", pc.finalText)
    }

    @Test
    fun `normalizes CRLF and CR to LF`() {
        val pc = ctxWith("a\r\nb\rc\nd")
        CleanupStage().apply(pc)
        assertFalse(pc.finalText!!.contains('\r'),
            "aucun \\r ne doit subsister dans la sortie")
        assertEquals("a\nb\nc\nd\n", pc.finalText)
    }

    @Test
    fun `trims surrounding blank lines and keeps single trailing newline`() {
        val pc = ctxWith("\n\n\nbody\n\n\n")
        CleanupStage().apply(pc)
        assertEquals("body\n", pc.finalText,
            "leading/trailing blank lines trimmées, exactement un '\\n' final")
    }

    @Test
    fun `throws when finalText was not produced upstream`() {
        val pc = ctxWith(null)
        val ex = assertThrows(IllegalStateException::class.java) {
            CleanupStage().apply(pc)
        }
        assertTrue(ex.message!!.contains("finalText"),
            "le message doit pointer finalText / LayerComposition manquante")
    }

    @Test
    fun `idempotent on already-clean output`() {
        val clean = "a\n\nb\n\nc\n"
        val pc = ctxWith(clean)
        CleanupStage().apply(pc)
        assertEquals(clean, pc.finalText, "le stage doit être idempotent")
    }
}

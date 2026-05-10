package com.contextextractor.ide.dialog

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

// Dialog "copier ce prompt" — chemin nominal V1 du mode COPY.
//
// **UX** :
//   • Texte dans une JTextArea read-only (pas d'édition pour ne pas laisser
//     l'utilisateur "améliorer" le prompt par mégarde — il peut toujours
//     copier puis modifier dans son éditeur).
//   • Police monospace pour préserver l'alignement des sections markdown.
//   • Action "Copy to clipboard" + bouton "Close" standard.
//
// **Validation runIde** (étape 7) : ouvrir test-project/, placer le curseur
// sur une méthode @TestTarget, déclencher l'action, vérifier que le prompt
// affiché correspond aux assertions binaires de EXPECTED_PROMPTS.md.
class PromptCopyDialog(
    project: Project,
    private val prompt: String
) : DialogWrapper(project) {

    init {
        title = "ContextCrawler — Generated Prompt"
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(900, 600)

        val textArea = JTextArea(prompt).apply {
            isEditable = false
            lineWrap = false
            // Police monospace : préserve les blocs de code et l'alignement
            // des sections markdown (### Champ ..., === CONTEXT ===, etc.).
            font = font.deriveFont(font.size.toFloat()).let {
                java.awt.Font("Monospaced", java.awt.Font.PLAIN, it.size)
            }
            caretPosition = 0
        }

        val tokenEstimate = estimateTokens(prompt)
        val header = JBLabel("Generated prompt — ${prompt.length} chars (~$tokenEstimate tokens)")
        panel.add(header, BorderLayout.NORTH)
        panel.add(JBScrollPane(textArea), BorderLayout.CENTER)
        return panel
    }

    // L'action "Copy" est ajoutée à gauche du panel d'actions (zone "leftSide")
    // pour ne pas concurrencer le bouton "Close" standard à droite.
    override fun createLeftSideActions(): Array<Action> = arrayOf(CopyAction())

    private inner class CopyAction : javax.swing.AbstractAction("Copy to Clipboard") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            CopyPasteManager.getInstance().setContents(StringSelection(prompt))
            // Pas de notification verbose côté V1 — le bouton qui clignote
            // suffit comme feedback. Étape 8 ajoutera une notification IDE
            // discrète si l'UX manque de clarté en runIde.
        }
    }

    // Estimation grossière : 1 token ≈ 4 caractères côté Anthropic/OpenAI.
    // Affichée à titre indicatif — pas de garantie de précision.
    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
}

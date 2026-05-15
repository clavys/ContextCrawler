package com.contextextractor.ide.dialog

import com.contextextractor.core.model.ContextTree
import com.contextextractor.core.prompt.PromptBuilder
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
//   • Zone éditable en haut pour saisir des « Instructions supplémentaires »
//     (étape 8-β). Vide par défaut → comportement strict V1 préservé.
//   • Preview du prompt initial dans une JTextArea read-only (police mono).
//   • Action "Copy to clipboard" : reconstruit le prompt en injectant le
//     contenu de la zone d'instructions via le seam USER_ENRICHMENT déjà
//     câblé dans LayerCompositionStage. Le preview NE reflète PAS le rebuild
//     — il sert de référence stable ; le label au-dessus du clipboard
//     explicite que le clic Copy inclut les instructions.
//
// **Pourquoi ne PAS auto-rebuild le preview à chaque keystroke** : le prompt
// peut faire 30k+ caractères. Réinjecter à chaque touche frappée alourdit
// l'UX sans valeur ajoutée — l'utilisateur veut taper, pas regarder la
// preview clignoter.
//
// **Validation runIde** : ouvrir test-project/, placer le curseur sur une
// méthode @TestTarget, déclencher Alt+G, taper une instruction dans la
// zone haut, copier, coller dans un éditeur externe et vérifier la présence
// du bloc `=== ADDITIONAL INSTRUCTIONS ===`.
class PromptCopyDialog(
    project: Project,
    private val tree: ContextTree,
    private val initialPrompt: String
) : DialogWrapper(project) {

    private val enrichmentArea = JTextArea(3, 80).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "ContextCrawler — Generated Prompt"
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(900, 700)

        // ── En-tête : zone enrichment + libellé ───────────────────────────────
        val northPanel = JPanel(BorderLayout())
        val enrichmentLabel = JBLabel(
            "Instructions supplémentaires (optionnel) — insérées dans le prompt copié sous '=== ADDITIONAL INSTRUCTIONS ==='"
        )
        northPanel.add(enrichmentLabel, BorderLayout.NORTH)
        northPanel.add(JBScrollPane(enrichmentArea), BorderLayout.CENTER)
        val tokenEstimate = estimateTokens(initialPrompt)
        val statsLabel = JBLabel(
            "Generated prompt — ${initialPrompt.length} chars (~$tokenEstimate tokens, preview sans instructions)"
        )
        northPanel.add(statsLabel, BorderLayout.SOUTH)
        panel.add(northPanel, BorderLayout.NORTH)

        // ── Centre : preview read-only ────────────────────────────────────────
        val textArea = JTextArea(initialPrompt).apply {
            isEditable = false
            lineWrap = false
            // Police monospace : préserve les blocs de code et l'alignement
            // des sections markdown (### Champ ..., === CONTEXT ===, etc.).
            font = font.deriveFont(font.size.toFloat()).let {
                java.awt.Font("Monospaced", java.awt.Font.PLAIN, it.size)
            }
            caretPosition = 0
        }
        panel.add(JBScrollPane(textArea), BorderLayout.CENTER)
        return panel
    }

    // L'action "Copy" est ajoutée à gauche du panel d'actions (zone "leftSide")
    // pour ne pas concurrencer le bouton "Close" standard à droite.
    override fun createLeftSideActions(): Array<Action> = arrayOf(CopyAction())

    private inner class CopyAction : javax.swing.AbstractAction("Copy to Clipboard") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            // Rebuild AU MOMENT du Copy avec l'enrichment courant. Si l'aire
            // est vide / blanche, `PromptBuilder.build()` ignore la layer
            // USER_ENRICHMENT (cf. PromptBuilder.build : `isNullOrBlank`).
            val enrichment = enrichmentArea.text.trim().takeIf { it.isNotEmpty() }
            val finalPrompt = PromptBuilder.defaultPipeline().build(tree, enrichment)
            CopyPasteManager.getInstance().setContents(StringSelection(finalPrompt))
        }
    }

    // Estimation grossière : 1 token ≈ 4 caractères côté Anthropic/OpenAI.
    // Affichée à titre indicatif — pas de garantie de précision.
    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
}

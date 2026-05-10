package com.contextextractor.ide.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

// Dialog d'alerte pour le cas SUT-untestable au niveau global —
// PROMPT_FORMAT.md §"Special case — UNTESTABLE_AS_IS at SUT level".
//
// **Pourquoi un dialog distinct de PromptCopyDialog** : le contenu n'est PAS
// un prompt à envoyer au LLM. C'est un diagnostic humain (champs bloquants,
// raisons, pistes de refactoring). Présenter un bouton "Copy to Clipboard"
// laisserait penser qu'on peut le coller dans Claude — c'est exactement le
// piège qu'on évite. Bouton "Close" uniquement.
//
// **Format du contenu** : le PromptBuilder produit déjà la sortie textuelle
// formatée (préfixe `STOP-UNTESTABLE`, ⚠️, sections Champs/Raisons/Pistes).
// On retire le préfixe sentinelle pour l'affichage utilisateur (il sert
// uniquement au routage, pas à l'UX).
class UntestableDiagnosticDialog(
    project: Project,
    private val rawPromptOutput: String
) : DialogWrapper(project) {

    init {
        title = "ContextCrawler — Méthode non testable automatiquement"
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(700, 400)

        val displayed = rawPromptOutput.removePrefix("STOP-UNTESTABLE\n")
        val textArea = JTextArea(displayed).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = java.awt.Font("Dialog", java.awt.Font.PLAIN, font.size)
            caretPosition = 0
        }

        val header = JBLabel(
            "Le SUT a au moins un champ non initialisable sans refactor du code testé."
        )
        panel.add(header, BorderLayout.NORTH)
        panel.add(JBScrollPane(textArea), BorderLayout.CENTER)
        return panel
    }

    // Pas d'action "Copy" — verrou UX explicite contre le piège « copier ce
    // diagnostic vers le LLM par habitude ». Voir doc de classe.
}

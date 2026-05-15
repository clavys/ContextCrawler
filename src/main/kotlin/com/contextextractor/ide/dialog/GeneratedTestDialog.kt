package com.contextextractor.ide.dialog

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

// Dialog "preview du test généré" — chemin nominal V1 du mode LLM_CALL.
//
// **UX** :
//   • Texte preview read-only (police monospace).
//   • Label avec le chemin cible : `Will write to: <path>`.
//   • Bouton "Write to file" : crée le fichier .java (WriteCommandAction)
//     puis ouvre dans l'éditeur IntelliJ pour validation immédiate.
//   • Bouton "Close" standard.
//
// **Chemin cible** : déduit du path source SUT (remplace `/src/main/java/`
// par `/src/test/java/` et renomme `XxxTest.java`). Si la déduction échoue
// (projet sans convention Maven/Gradle), l'utilisateur voit le path candidat
// et peut refuser (le bouton "Close" sans écriture est le no-op safe).
//
// **WriteCommandAction** : passage obligatoire pour toute mutation du VFS
// IntelliJ. Sans ça, on aurait des "Write access is allowed" exceptions
// remontant de la plateforme.
class GeneratedTestDialog(
    private val project: Project,
    private val testCode: String,
    private val targetPath: String
) : DialogWrapper(project) {

    init {
        title = "ContextCrawler — Generated Test"
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(900, 700)

        // En-tête : chemin cible + estimation taille
        val header = JBLabel(
            "Will write to: $targetPath (${testCode.length} chars)"
        )
        panel.add(header, BorderLayout.NORTH)

        val textArea = JTextArea(testCode).apply {
            isEditable = false
            lineWrap = false
            font = font.deriveFont(font.size.toFloat()).let {
                java.awt.Font("Monospaced", java.awt.Font.PLAIN, it.size)
            }
            caretPosition = 0
        }
        panel.add(JBScrollPane(textArea), BorderLayout.CENTER)
        return panel
    }

    override fun createLeftSideActions(): Array<Action> = arrayOf(WriteAction())

    private inner class WriteAction : javax.swing.AbstractAction("Write to file") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            val targetFile = File(targetPath)
            // Confirmation avant écrasement — protège contre un Alt+Shift+G
            // accidentel sur une méthode déjà testée. La V1 prend le parti
            // sûr : on n'écrase JAMAIS sans validation explicite.
            if (targetFile.exists()) {
                val confirm = Messages.showYesNoDialog(
                    project,
                    "Le fichier existe déjà :\n$targetPath\n\nL'écraser ?",
                    "ContextCrawler — Overwrite ?",
                    Messages.getQuestionIcon()
                )
                if (confirm != Messages.YES) return
            }
            try {
                writeTestFile(targetFile)
                openInEditor(targetFile)
                close(OK_EXIT_CODE)
            } catch (t: Throwable) {
                Messages.showErrorDialog(
                    project,
                    "Échec de l'écriture du fichier de test : ${t.message ?: t.javaClass.simpleName}",
                    "ContextCrawler"
                )
            }
        }
    }

    private fun writeTestFile(target: File) {
        target.parentFile?.mkdirs()
        // I/O brute hors WriteCommandAction (le filesystem n'est pas le VFS).
        target.writeText(testCode, Charsets.UTF_8)
        // Puis on synchronise le VFS pour que l'IDE voie le fichier.
        // Le refresh DOIT vivre dans une WriteCommandAction côté plateforme.
        WriteCommandAction.runWriteCommandAction(project) {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
        }
    }

    private fun openInEditor(target: File) {
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
            if (vf != null) {
                FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }
    }
}

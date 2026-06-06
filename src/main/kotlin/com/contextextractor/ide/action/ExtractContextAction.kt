package com.contextextractor.ide.action

import com.contextextractor.core.config.ContextExtractorConfig
import com.contextextractor.ide.dialog.PromptCopyDialog
import com.contextextractor.ide.dialog.UntestableDiagnosticDialog
import com.contextextractor.ide.service.ContextExtractorService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

// Action point d'entrée — Tools → Extract Context (Alt+G).
//
// **Routage selon outputMode (V1)** :
//   • COPY     → PromptCopyDialog (étape 7) [chemin nominal V1]
//   • LLM_CALL → repoussé à l'étape 8, fallback sur COPY pour V1.
//   • ASK      → repoussé à l'étape 8, fallback sur COPY pour V1.
//
// **Routage spécial UNTESTABLE-SUT** : si PromptBuilder a court-circuité
// (préfixe `STOP-UNTESTABLE`), on bascule sur UntestableDiagnosticDialog —
// PAS sur PromptCopyDialog. Verrou central pour ne JAMAIS laisser un prompt
// ambigu remonter au LLM (PROMPT_FORMAT.md §"Special case").
//
// **Async** : l'extraction PSI peut prendre quelques centaines de ms sur du
// vrai code. On l'exécute via `Task.Backgroundable` pour ne pas geler le
// thread UI ; le dialog est ensuite affiché sur EDT.
class ExtractContextAction : AnAction() {

    private val log = logger<ExtractContextAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Visible uniquement quand on a un Project + un Editor + un VirtualFile.
        // Évite l'item de menu "mort" dans les contextes non-éditeur.
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            project != null && editor != null && virtualFile != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val offset = editor.caretModel.offset
        val service = project.service<ContextExtractorService>()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Extracting method context...", true) {
                private var result: ContextExtractorService.BuildResult? = null
                private var failure: Throwable? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    try {
                        result = service.extractAndBuildPrompt(virtualFile, offset)
                    } catch (t: Throwable) {
                        failure = t
                        log.warn("ContextCrawler extraction failed", t)
                    }
                }

                override fun onSuccess() {
                    val r = result
                    val f = failure
                    if (f != null) {
                        showError(project, f)
                        return
                    }
                    if (r == null) {
                        showError(project, IllegalStateException("Aucun résultat produit"))
                        return
                    }
                    showResult(project, r)
                }
            }
        )
    }

    private fun showResult(project: com.intellij.openapi.project.Project,
                           result: ContextExtractorService.BuildResult) {
        ApplicationManager.getApplication().invokeLater {
            // Verrou central : SUT non-testable → JAMAIS un PromptCopyDialog
            // (le LLM ne doit pas recevoir un prompt à sections vides).
            if (result.isUntestable) {
                UntestableDiagnosticDialog(project, result.prompt).show()
                return@invokeLater
            }
            // V1 : LLM_CALL et ASK retombent sur COPY (étape 8 implémentera
            // le routage complet — pour l'instant, GenerateTestAction est une
            // action séparée Alt+Shift+G, pas un routage via outputMode).
            // Documenté dans la doc de classe.
            when (result.outputMode) {
                ContextExtractorConfig.OutputMode.COPY,
                ContextExtractorConfig.OutputMode.LLM_CALL,
                ContextExtractorConfig.OutputMode.ASK ->
                    PromptCopyDialog(project, result.tree, result.prompt, result.tuningProfileId).show()
            }
        }
    }

    private fun showError(project: com.intellij.openapi.project.Project, t: Throwable) {
        ApplicationManager.getApplication().invokeLater {
            // Message en français côté utilisateur (CLAUDE.md convention),
            // détail technique dans le log.
            Messages.showErrorDialog(
                project,
                "Échec de l'extraction de contexte : ${t.message ?: t.javaClass.simpleName}",
                "ContextCrawler"
            )
        }
    }
}

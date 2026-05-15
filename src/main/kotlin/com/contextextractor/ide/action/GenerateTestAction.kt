package com.contextextractor.ide.action

import com.contextextractor.adapters.ide.ContextCrawlerSettings
import com.contextextractor.adapters.llm.ClaudeCodeClient
import com.contextextractor.core.llm.LlmClient
import com.contextextractor.core.llm.PromptRequest
import com.contextextractor.core.llm.PromptResponse
import com.contextextractor.ide.dialog.GeneratedTestDialog
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
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

// Action LLM_CALL — Tools → Generate Test with LLM (Alt+Shift+G).
//
// **Pipeline** :
//   1. Extraction PSI + build prompt (réutilise ContextExtractorService).
//   2. Si STOP-UNTESTABLE → UntestableDiagnosticDialog (verrou central).
//   3. Sinon → ClaudeCodeClient.complete(prompt) dans le même Task.Backgroundable
//      pour ne pas freezer l'UI pendant l'appel CLI (peut prendre 30-60s).
//   4. Si error sur la réponse → notification.
//   5. Sinon → GeneratedTestDialog avec preview + bouton "Write to file".
//
// **Choix du LlmClient** : la décision utilisateur étape 8 fige
// `ClaudeCodeClient` comme unique backend V1 (pas d'AnthropicApiClient). Le
// `LlmClient` est injecté via le constructeur — facilite les tests futurs et
// permet une bascule vers HTTP backend en V1.1 sans toucher l'action.
class GenerateTestAction(
    private val clientFactory: () -> LlmClient = { ClaudeCodeClient() }
) : AnAction() {

    private val log = logger<GenerateTestAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
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
            object : Task.Backgroundable(project, "Generating test via Claude...", true) {
                private var extraction: ContextExtractorService.BuildResult? = null
                private var llmResponse: PromptResponse? = null
                private var failure: Throwable? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    try {
                        indicator.text = "Extracting method context..."
                        val r = service.extractAndBuildPrompt(virtualFile, offset)
                        extraction = r
                        // Court-circuit : pas d'appel LLM si SUT non-testable.
                        // Géré dans onSuccess via le flag isUntestable.
                        if (r.isUntestable) return

                        indicator.text = "Calling Claude CLI (this can take a while)..."
                        val model = ContextCrawlerSettings.getInstance().state.llmModel
                        llmResponse = clientFactory().complete(
                            PromptRequest(prompt = r.prompt, model = model)
                        )
                    } catch (t: Throwable) {
                        failure = t
                        log.warn("ContextCrawler test generation failed", t)
                    }
                }

                override fun onSuccess() {
                    val f = failure
                    if (f != null) {
                        showError(project, f.message ?: f.javaClass.simpleName)
                        return
                    }
                    val r = extraction
                        ?: return showError(project, "Aucun résultat d'extraction produit")
                    if (r.isUntestable) {
                        ApplicationManager.getApplication().invokeLater {
                            UntestableDiagnosticDialog(project, r.prompt).show()
                        }
                        return
                    }
                    val response = llmResponse
                        ?: return showError(project, "Aucune réponse du LLM")
                    if (response.error != null) {
                        showError(project, response.error)
                        return
                    }
                    val sutFqn = r.tree.root.title.substringBefore('#')
                    val targetPath = computeTestFilePath(virtualFile, sutFqn)
                    ApplicationManager.getApplication().invokeLater {
                        GeneratedTestDialog(project, response.text, targetPath).show()
                    }
                }
            }
        )
    }

    // Déduit le chemin du fichier test à partir du path source du SUT.
    //
    // Convention Maven/Gradle (test-project/ et tous les projets réels V1) :
    //   `.../src/main/java/<pkg>/<Name>.java`
    //     → `.../src/test/java/<pkg>/<Name>Test.java`
    //
    // **Pourquoi pas d'introspection des `sourceRoots`** : l'API IntelliJ
    // `ProjectRootManager.getSourceRoots()` ne distingue pas trivialement
    // main/test sans heuristique sur les paths. La substitution textuelle
    // ci-dessous est volontairement simple : si la convention n'est pas
    // respectée, l'utilisateur voit le path dans le dialog et peut refuser.
    // V1.1 ajoutera un FileChooser si la demande remonte.
    private fun computeTestFilePath(sourceFile: VirtualFile, sutFqn: String): String {
        val testClassName = sutFqn.substringAfterLast('.') + "Test"
        val sourcePath = sourceFile.path
        val candidate = sourcePath
            .replace("/src/main/java/", "/src/test/java/")
            .replace("/src/main/kotlin/", "/src/test/java/")
        val parent = candidate.substringBeforeLast('/', missingDelimiterValue = candidate)
        return "$parent/$testClassName.java"
    }

    private fun showError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            // Message utilisateur en français (CLAUDE.md convention).
            Messages.showErrorDialog(
                project,
                "Échec de la génération du test : $message",
                "ContextCrawler"
            )
        }
    }
}

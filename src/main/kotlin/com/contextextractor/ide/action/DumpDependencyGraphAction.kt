package com.contextextractor.ide.action

import com.contextextractor.adapters.psi.JavaPsiIntrospector
import com.contextextractor.core.extractor.CursorLocation
import com.contextextractor.core.extractor.SourceFile
import com.contextextractor.core.model.depgraph.DependencyGraph
import com.contextextractor.strategies.depgraph.DependencyGraphBuilder
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable
import java.awt.datatransfer.StringSelection

// V1.3 Étape 1 — action de debugging.
//
// Construit un graphe de dépendances NOM-SEUL depuis la classe (SUT) au
// curseur et copie le rendu (JSON + DOT) au clipboard. Sert d'outil pour :
//   • inspecter ce que le pipeline « voit » dans son champ de vision
//   • diagnostiquer les cas où une référence est silencieusement perdue
//     (héritage profond, types non résolvables, etc.)
//   • orienter les futures décisions de design V1.3 (option B = fallback PSI)
//
// **Indépendant de l'extraction principale** : utilise les mêmes briques
// (JavaPsiIntrospector, CursorLocation) mais ne touche pas le pipeline
// `RecursiveDeepStrategy`. Aucun risque de régression sur le mode COPY/LLM.
//
// **Rendu** : 2 formats au clipboard :
//   1. Résumé textuel (DependencyGraph.toSummary())
//   2. JSON complet pour parsing externe (`jq`, scripts Python…)
//   3. DOT pour visualisation Graphviz
//      (cf https://dreampuf.github.io/GraphvizOnline)
class DumpDependencyGraphAction : AnAction() {

    private val log = logger<DumpDependencyGraphAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Visible uniquement avec un Project + Editor + VirtualFile actifs.
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

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Building dependency graph...", true) {
                private var graph: DependencyGraph? = null
                private var failure: Throwable? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    try {
                        val introspector = JavaPsiIntrospector(project)
                        val cursor = CursorLocation(
                            file = SourceFile(path = virtualFile.path, language = virtualFile.fileType.name),
                            offset = offset
                        )
                        // Résolution du SUT FQN au curseur — toute traversée PSI
                        // doit vivre dans une ReadAction (STRATEGIE.md §7.2).
                        val sutFqn = ApplicationManager.getApplication().runReadAction(
                            Computable {
                                val symbol = introspector.resolveSymbolAt(cursor.file, cursor.offset)
                                    ?: return@Computable null
                                // Le fqn d'un symbol method est "ownerFqn#methodName" — on garde le owner.
                                symbol.fqn.substringBefore('#')
                            }
                        ) ?: throw IllegalStateException(
                            "Aucune classe résolvable au curseur."
                        )

                        // Build du graphe — également sous ReadAction car le builder
                        // appelle introspector.resolveClass/listFields/listMethods.
                        graph = ApplicationManager.getApplication().runReadAction(
                            Computable {
                                DependencyGraphBuilder(
                                    introspector = introspector,
                                    frameworkPrefixes = DEFAULT_FRAMEWORK_PREFIXES,
                                    maxDepth = 10
                                ).build(sutFqn)
                            }
                        )
                    } catch (t: Throwable) {
                        failure = t
                        log.warn("Dependency graph build failed", t)
                    }
                }

                override fun onSuccess() {
                    val g = graph
                    val f = failure
                    if (f != null) {
                        showError(project, f)
                        return
                    }
                    if (g == null) {
                        showError(project, IllegalStateException("Aucun graphe produit"))
                        return
                    }
                    presentGraph(project, g)
                }
            }
        )
    }

    private fun presentGraph(project: Project, graph: DependencyGraph) {
        ApplicationManager.getApplication().invokeLater({
            // Concaténation des 3 formats — l'utilisateur peut séparer manuellement
            // ou utiliser directement la portion qu'il veut. C'est plus simple qu'un
            // dialogue avec onglets pour V1.3 étape 1.
            val payload = buildString {
                appendLine("=== DependencyGraph — résumé ===")
                appendLine(graph.toSummary())
                appendLine()
                appendLine("=== JSON ===")
                appendLine(graph.toJson())
                appendLine()
                appendLine("=== DOT (Graphviz — coller dans https://dreampuf.github.io/GraphvizOnline) ===")
                appendLine(graph.toDot())
            }
            CopyPasteManager.getInstance().setContents(StringSelection(payload))
            Messages.showInfoMessage(
                project,
                "Graphe copié dans le presse-papiers : ${graph.toSummary()}",
                "ContextCrawler — Dependency Graph"
            )
        }, ModalityState.NON_MODAL)
    }

    private fun showError(project: Project, t: Throwable) {
        ApplicationManager.getApplication().invokeLater({
            Messages.showErrorDialog(
                project,
                "Échec de la construction du graphe : ${t.message ?: t.javaClass.simpleName}",
                "ContextCrawler"
            )
        }, ModalityState.NON_MODAL)
    }

    companion object {
        // Préfixes framework par défaut — alignés sur les patterns observés
        // dans STRATEGIE §3.3 (frontières non explorées). L'utilisateur peut
        // étendre via une future option Settings si besoin.
        val DEFAULT_FRAMEWORK_PREFIXES: List<String> = listOf(
            "javax.faces.", "jakarta.faces.",
            "org.primefaces.", "jakarta.servlet.", "javax.servlet.",
            "org.springframework.web.", "org.springframework.boot."
        )
    }
}

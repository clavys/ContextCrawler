package com.contextextractor.ide.action

import com.contextextractor.adapters.psi.JavaPsiIntrospector
import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.CursorLocation
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.SourceFile
import com.contextextractor.core.extractor.SymbolKind
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
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

// V1.4 — Action diagnostique « Dump SUT internals » (Alt+Shift+X).
//
// **But** : quand le pipeline core fonctionne sur fakes mais qu'IHMDTO / un
// champ hérité n'apparaît pas côté Astrea réel (case 4.1), localiser
// précisément où PSI lâche en exposant l'état brut côté adapter PSI :
//
//   1. Hierarchy complète (listSuperClasses)
//   2. Pour CHAQUE classe de la hierarchy : listFields() + listMethods()
//      (noms + visibility + returnType) — vérifie que PSI voit bien les
//      membres déclarés
//   3. Pour le target method (méthode au curseur) :
//      - listMethodCalls (chaque call avec targetType)
//      - listFieldAccesses
//      - readMethodBody
//
// **Indépendant du pipeline principal** : utilise uniquement le port
// `CodeIntrospector`. Aucun risque de régression.
//
// **Rendu** : un seul payload texte lisible, copié au clipboard.
class DumpSutInternalsAction : AnAction() {

    private val log = logger<DumpSutInternalsAction>()

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

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Dumping SUT internals...", true) {
                private var payload: String? = null
                private var failure: Throwable? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    try {
                        val introspector = JavaPsiIntrospector(project)
                        val cursor = CursorLocation(
                            file = SourceFile(path = virtualFile.path, language = virtualFile.fileType.name),
                            offset = offset
                        )
                        payload = ApplicationManager.getApplication().runReadAction(
                            Computable { dumpInternals(introspector, cursor) }
                        )
                    } catch (t: Throwable) {
                        failure = t
                        log.warn("SUT internals dump failed", t)
                    }
                }

                override fun onSuccess() {
                    val text = payload
                    val f = failure
                    if (f != null) { showError(project, f); return }
                    if (text == null) {
                        showError(project, IllegalStateException("Aucun payload produit"))
                        return
                    }
                    presentPayload(project, text)
                }
            }
        )
    }

    private fun dumpInternals(
        introspector: CodeIntrospector,
        cursor: CursorLocation
    ): String {
        // Même résolution curseur → méthode cible que `RecursiveDeepStrategy.resolveCursor` :
        // le curseur DOIT être dans le corps d'une méthode. Sinon erreur explicite —
        // c'est la même contrainte que `Alt+G` (Extract Context).
        val symbol = introspector.resolveSymbolAt(cursor.file, cursor.offset)
            ?: error("Aucun symbole résolu au curseur (${cursor.file.path}:${cursor.offset})")
        if (symbol.kind != SymbolKind.METHOD) {
            error("Place le curseur DANS le corps de la méthode cible " +
                "(comme pour Alt+G). Symbole résolu : ${symbol.kind} ${symbol.fqn}")
        }
        val targetMethod = introspector.findEnclosingMethod(symbol)
            ?: error("Aucune méthode englobante trouvée pour le symbole ${symbol.id}")

        val ownerFqn = symbol.fqn.substringBefore('#')
        val sut = introspector.resolveClass(ownerFqn)
            ?: error("Classe non résolvable : $ownerFqn")

        val hierarchy = listOf(sut) + introspector.listSuperClasses(sut)
        val targetMethods: List<MethodSignature> = listOf(targetMethod)

        return buildString {
            appendLine("═══════════════════════════════════════════════════════")
            appendLine("  SUT INTERNALS DUMP")
            appendLine("  SUT     : $ownerFqn")
            appendLine("  Target  : ${targetMethod.canonical()}")
            appendLine("═══════════════════════════════════════════════════════")
            appendLine()

            appendLine("── HIERARCHY (${hierarchy.size} classes) ──")
            hierarchy.forEachIndexed { i, cls ->
                appendLine("  [$i] ${cls.fqn}")
            }
            appendLine()

            hierarchy.forEachIndexed { i, cls ->
                appendLine("── [$i] ${cls.fqn} ──")
                val fields = runCatching { introspector.listFields(cls) }.getOrDefault(emptyList())
                appendLine("  Fields (${fields.size}):")
                if (fields.isEmpty()) {
                    appendLine("    (none — POTENTIAL ISSUE if class should have fields)")
                }
                fields.forEach { f ->
                    appendLine("    - ${f.name} : ${f.type.fqName}" +
                        "  [vis=${f.visibility}, declaredIn=${f.declaredIn}]")
                }
                val methods = runCatching { introspector.listMethods(cls) }.getOrDefault(emptyList())
                val nonCtors = methods.filter { it.name != "<init>" }
                appendLine("  Methods (${nonCtors.size} non-ctor):")
                nonCtors.take(40).forEach { m ->
                    val params = m.parameters.joinToString(",") { it.type.fqName }
                    appendLine("    - ${m.visibility} ${m.returnType.fqName} ${m.name}($params)")
                }
                if (nonCtors.size > 40) appendLine("    ... (${nonCtors.size - 40} more)")
                appendLine()
            }

            if (targetMethods.isEmpty()) {
                appendLine("── NO TARGET METHODS ──")
                appendLine()
            } else {
                targetMethods.forEachIndexed { i, m ->
                    appendLine("── TARGET [$i] METHOD INTROSPECTION ──")
                    appendLine("  canonical : ${m.canonical()}")
                    appendLine("  visibility: ${m.visibility}")
                    appendLine("  returnType: ${m.returnType.fqName}")
                    appendLine()

                    val calls = runCatching { introspector.listMethodCalls(m) }.getOrDefault(emptyList())
                    appendLine("  listMethodCalls (${calls.size}):")
                    calls.forEach { c ->
                        val args = c.argTypes.joinToString(",")
                        val flag = if (c.isStatic) "[static]" else ""
                        appendLine("    - ${c.targetType}#${c.methodName}($args) $flag")
                    }
                    appendLine()

                    val accesses = runCatching { introspector.listFieldAccesses(m) }.getOrDefault(emptyList())
                    appendLine("  listFieldAccesses (${accesses.size}):")
                    accesses.forEach { a ->
                        val mode = if (a.write) "WRITE" else "READ"
                        appendLine("    - $mode ${a.ownerType}.${a.fieldName}")
                    }
                    appendLine()

                    val body = runCatching { introspector.readMethodBody(m) }.getOrDefault("")
                    appendLine("  readMethodBody (${body.length} chars):")
                    body.lines().take(30).forEach { appendLine("    | $it") }
                    if (body.lines().size > 30) appendLine("    ... (${body.lines().size - 30} more lines)")
                    appendLine()
                }
            }

            appendLine("── TRIVIAL GETTER ANALYSIS (target's intra-hierarchy calls) ──")
            val hierarchyFqnsSet = hierarchy.map { it.fqn }.toSet()
            val targetCalls = runCatching {
                introspector.listMethodCalls(targetMethod)
            }.getOrDefault(emptyList())
            val intraCalls = targetCalls.filter {
                !it.isStatic && it.targetType in hierarchyFqnsSet
            }
            if (intraCalls.isEmpty()) {
                appendLine("  (no intra-hierarchy calls — nothing to analyse)")
                appendLine()
            }
            intraCalls.forEach { call ->
                appendLine("  • ${call.targetType}#${call.methodName}(${call.argTypes.joinToString(",")})")
                val cls = introspector.resolveClass(call.targetType)
                if (cls == null) {
                    appendLine("    → resolveClass returned null")
                    return@forEach
                }
                val candidates = introspector.listMethods(cls).filter { it.name == call.methodName }
                appendLine("    candidates: ${candidates.size}")
                val method = candidates.firstOrNull { sig ->
                    sig.parameters.map { it.type.fqName } == call.argTypes
                } ?: candidates.firstOrNull()
                if (method == null) {
                    appendLine("    → no matching method")
                    return@forEach
                }
                // Introspection détaillée — refait les checks d'isTrivialGetter
                // inline pour exposer LEQUEL pose problème.
                val methodCalls = runCatching { introspector.listMethodCalls(method) }.getOrDefault(emptyList())
                val analysis = runCatching { introspector.analyzeMethodBody(method) }
                    .getOrDefault(com.contextextractor.core.extractor.MethodBodyAnalysis())
                val assignments = runCatching { introspector.listFieldAssignments(method) }.getOrDefault(emptyList())
                val accesses = runCatching { introspector.listFieldAccesses(method) }.getOrDefault(emptyList())
                val body = runCatching { introspector.readMethodBody(method) }.getOrDefault("")
                val bodyStripped = body.trim().removeSurrounding("{", "}").trim()
                appendLine("    name=<init>?              ${method.name == "<init>"}")
                appendLine("    has params?               ${method.parameters.isNotEmpty()}  (count=${method.parameters.size})")
                appendLine("    listMethodCalls.empty?    ${methodCalls.isEmpty()}  (count=${methodCalls.size})")
                appendLine("    thrownExceptions.empty?   ${analysis.thrownExceptions.isEmpty()}")
                appendLine("    caughtExceptions.empty?   ${analysis.caughtExceptions.isEmpty()}")
                appendLine("    conditionalBranches.empty? ${analysis.conditionalBranches.isEmpty()}")
                appendLine("    instantiations.empty?     ${analysis.instantiations.isEmpty()}")
                appendLine("    expectedLambdas.empty?    ${analysis.expectedLambdas.isEmpty()}")
                appendLine("    listFieldAssignments.empty? ${assignments.isEmpty()}  (count=${assignments.size})")
                appendLine("    listFieldAccesses.size==1? ${accesses.size == 1}  (count=${accesses.size})")
                if (accesses.isNotEmpty()) {
                    accesses.forEach { a ->
                        appendLine("        access: ${if (a.write) "WRITE" else "READ"} ${a.ownerType}.${a.fieldName}")
                    }
                }
                appendLine("    body stripped (${bodyStripped.length} chars): \"$bodyStripped\"")
                appendLine("    body matches `return X;` or `return this.X;`?  " +
                    if (accesses.size == 1)
                        (bodyStripped == "return ${accesses.single().fieldName};" ||
                            bodyStripped == "return this.${accesses.single().fieldName};")
                    else "n/a"
                )
                appendLine()
            }

            appendLine("── PIPELINE EXTRACTCORE OUTPUT ──")
            val result = runCatching {
                RecursiveDeepStrategy().extractCore(
                    introspector, DefaultClassifier(), StrategyConfig(), sut, targetMethod
                )
            }
            if (result.isFailure) {
                appendLine("  ERROR: ${result.exceptionOrNull()?.message}")
                appendLine()
            } else {
                val r = result.getOrThrow()
                appendLine("  fields (${r.fields.size}):")
                r.fields.forEach { f ->
                    val strat = r.initProtocol[f.name]?.recommendedStrategy?.let {
                        it::class.simpleName
                    } ?: "?"
                    appendLine("    - ${f.name} : ${f.type.fqName}  [strategy=$strat]")
                }
                appendLine()
                appendLine("  mocks (${r.mocks.size}):")
                r.mocks.keys.forEach { appendLine("    - $it") }
                appendLine()
                appendLine("  dataStructures (${r.dataStructures.size}):")
                r.dataStructures.keys.forEach { appendLine("    - $it") }
                appendLine()
                appendLine("  internalLogics (${r.internalLogics.size}):")
                r.internalLogics.keys.forEach { appendLine("    - $it") }
                appendLine()
            }

            appendLine("── KEY DIAGNOSTIC QUESTIONS ──")
            appendLine("  1. Does ANY class in the hierarchy have a field whose")
            appendLine("     type is the chained-call receiver you expected?")
            appendLine("  2. Does target.listMethodCalls show the call with the")
            appendLine("     EXPECTED targetType (= one of the hierarchy FQNs)?")
            appendLine("  3. Does target.readMethodBody match the source you see")
            appendLine("     in the editor? (if not, PSI/source-jar issue)")
            appendLine()
            appendLine("═══════════════════════════════════════════════════════")
        }
    }

    private fun presentPayload(project: Project, payload: String) {
        ApplicationManager.getApplication().invokeLater({
            CopyPasteManager.getInstance().setContents(StringSelection(payload))
            Messages.showInfoMessage(
                project,
                "SUT internals dumped to clipboard (${payload.length} chars).",
                "ContextCrawler — SUT Internals"
            )
        }, ModalityState.NON_MODAL)
    }

    private fun showError(project: Project, t: Throwable) {
        ApplicationManager.getApplication().invokeLater({
            Messages.showErrorDialog(
                project,
                "Échec du dump : ${t.message ?: t.javaClass.simpleName}",
                "ContextCrawler"
            )
        }, ModalityState.NON_MODAL)
    }
}

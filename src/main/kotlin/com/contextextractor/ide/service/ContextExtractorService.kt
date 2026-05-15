package com.contextextractor.ide.service

import com.contextextractor.adapters.config.YamlProjectConfigSource
import com.contextextractor.adapters.ide.ContextCrawlerSettings
import com.contextextractor.adapters.ide.IntellijSettingsSource
import com.contextextractor.adapters.psi.JavaPsiIntrospector
import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.config.ContextExtractorConfig
import com.contextextractor.core.config.ContextExtractorConfigBinder
import com.contextextractor.core.config.DefaultsConfigSource
import com.contextextractor.core.config.LayeredConfig
import com.contextextractor.core.extractor.CursorLocation
import com.contextextractor.core.extractor.SourceFile
import com.contextextractor.core.model.ContextTree
import com.contextextractor.core.prompt.PromptBuilder
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.core.strategy.StrategyInput
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

// Service IDE-couplé qui orchestre la chaîne complète :
//
//   Project + curseur
//     → LayeredConfig (Defaults + Settings + YAML)
//     → ContextExtractorConfig typé
//     → JavaPsiIntrospector + RecursiveDeepStrategy + DefaultClassifier
//     → ContextTree
//     → PromptBuilder.defaultPipeline()
//     → BuildResult (prompt + outputMode + tree)
//
// **Pourquoi un service** : 1 instance par Project (cohérent avec le scope
// de la config YAML qui vit à la racine projet). L'IDE crée et dispose le
// service avec le projet.
//
// **Pourquoi PAS testé directement en JUnit pur** : dépend de Project,
// VirtualFile, ReadAction, ContextCrawlerSettings.getInstance(). Tout cela
// requiert la plateforme IntelliJ. Validé au gate `runIde` étape 7 — comme
// JavaPsiIntrospector. Les briques internes (LayeredConfig, binder, strategy,
// prompt builder) ont déjà chacune leur couverture pure.
//
// **ReadAction obligatoire** : STRATEGIE.md §7.2 — toute lecture PSI doit
// vivre dans une ReadAction côté caller. C'est ICI que c'est appliqué (le
// JavaPsiIntrospector ne wrappe rien lui-même, par design).
@Service(Service.Level.PROJECT)
class ContextExtractorService(private val project: Project) {

    fun extractAndBuildPrompt(file: VirtualFile, offset: Int): BuildResult {
        val config = loadConfig()
        val strategy = RecursiveDeepStrategy()
        val classifier = DefaultClassifier()
        val introspector = JavaPsiIntrospector(project)

        val cursor = CursorLocation(
            file = SourceFile(path = file.path, language = file.fileType.name),
            offset = offset
        )
        val strategyConfig = StrategyConfig(
            budget = config.budget,
            mockSuffixes = config.classification.mockSuffixes,
            dataSuffixes = config.classification.dataSuffixes
        )
        val input = StrategyInput(introspector, classifier, cursor, strategyConfig)

        // Toute la traversée PSI vit dans une ReadAction unique — évite de
        // multiples enter/exit qui sérialisent inutilement contre le thread UI.
        // V1 : exécution synchrone bloquante côté action (acceptable pour le
        // mode COPY ; le mode cancellable via ReadAction.nonBlocking() est
        // repoussé à l'étape 8 si nécessaire pour les très gros SUT). On utilise
        // la forme `Application.runReadAction(Computable)` plutôt que l'extension
        // Kotlin top-level (deprecated en 2026.1+).
        val tree: ContextTree = ApplicationManager.getApplication()
            .runReadAction(Computable { strategy.extract(input) })

        val prompt = PromptBuilder.defaultPipeline().build(tree)
        return BuildResult(prompt = prompt, outputMode = config.outputMode, tree = tree)
    }

    // Construit le LayeredConfig depuis les 3 sources V1 + applique le binder.
    // Le YAML est lu à la racine du projet ; absent → simplement ignoré (cf
    // YamlProjectConfigSource cas dégradés).
    private fun loadConfig(): ContextExtractorConfig {
        val basePath = project.basePath
            ?: error("Le projet n'a pas de basePath — impossible de localiser .contextextractor.yml")
        val yamlPath: Path = Path.of(basePath, ".contextextractor.yml")
        val sources = listOf(
            DefaultsConfigSource(),
            IntellijSettingsSource(ContextCrawlerSettings.getInstance().state),
            YamlProjectConfigSource(yamlPath)
        )
        return ContextExtractorConfigBinder(LayeredConfig(sources)).bind()
    }

    // Sortie complète d'un build — l'action peut router selon outputMode et
    // afficher un dialog approprié (PromptCopyDialog, ou warning UNTESTABLE).
    data class BuildResult(
        val prompt: String,
        val outputMode: ContextExtractorConfig.OutputMode,
        val tree: ContextTree
    ) {
        // Détection du préfixe sentinelle posé par PromptBuilder quand le SUT
        // est non-testable au niveau global. L'action lit ce flag pour router
        // vers un dialog d'alerte au lieu du dialog "copier ce prompt".
        val isUntestable: Boolean
            get() = prompt.startsWith("STOP-UNTESTABLE")
    }
}

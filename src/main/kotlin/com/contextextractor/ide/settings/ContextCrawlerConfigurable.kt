package com.contextextractor.ide.settings

import com.contextextractor.adapters.ide.ContextCrawlerSettings
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

// UI Settings minimale — Settings → Tools → ContextCrawler.
//
// **Périmètre V1** (CLAUDE.md étape 6) :
//   • strategy   — texte libre (un seul id supporté V1, mais champ exposé
//     pour autoriser la saisie si l'utilisateur ajoute une stratégie custom).
//   • outputMode — combobox COPY / LLM_CALL / ASK (défaut COPY).
//   • llm.provider + llm.model — texte libre (V1 : claude / claude-sonnet-4-6).
//
// **Tests déférés au gate `runIde`** (cohérent avec le report 4-α du wiring
// testFramework Platform pour JavaPsiIntrospector) : Swing + cycle de vie
// `Configurable` ne peuvent pas être instanciés sans la plateforme. La
// logique pure (mapping State → Map) reste 100% testable via
// `IntellijSettingsSource` côté `adapters/ide/`.
//
// **Validation manuelle attendue** au gate runIde :
//   1. Ouvrir Settings → Tools → ContextCrawler
//   2. Vérifier que les valeurs par défaut s'affichent (COPY, claude, ...)
//   3. Modifier outputMode → LLM_CALL, Apply, fermer Settings, rouvrir
//   4. La valeur LLM_CALL doit persister (PersistentStateComponent OK)
class ContextCrawlerConfigurable : Configurable {

    private val settings = ContextCrawlerSettings.getInstance()

    private val strategyField = JBTextField()
    private val outputModeBox = ComboBox(arrayOf("COPY", "LLM_CALL", "ASK"))
    private val providerField = JBTextField()
    private val modelField = JBTextField()
    // Phase 5 — sélection du profil de tuning des CONSTRAINTS du prompt.
    private val tuningProfileBox = ComboBox(arrayOf("qwen", "none"))
    // V1.4 — strictness Mockito injectée dans CONSTRAINTS du prompt.
    private val mockitoStrictnessBox = ComboBox(arrayOf("STRICT_STUBS", "WARN", "LENIENT"))

    private var rootPanel: JPanel? = null

    override fun getDisplayName(): String = "ContextCrawler"

    override fun createComponent(): JComponent {
        // FormBuilder = layout standard plateforme (label aligné à gauche,
        // champs à droite, espacement homogène avec les autres Settings).
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Strategy:"), strategyField, 1, false)
            .addLabeledComponent(JBLabel("Output mode:"), outputModeBox, 1, false)
            .addLabeledComponent(JBLabel("LLM provider:"), providerField, 1, false)
            .addLabeledComponent(JBLabel("LLM model:"), modelField, 1, false)
            .addLabeledComponent(JBLabel("Prompt tuning profile:"), tuningProfileBox, 1, false)
            .addLabeledComponent(JBLabel("Mockito strictness:"), mockitoStrictnessBox, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        rootPanel = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return strategyField.text != s.strategy ||
            outputModeBox.selectedItem as String? != s.outputMode ||
            providerField.text != s.llmProvider ||
            modelField.text != s.llmModel ||
            tuningProfileBox.selectedItem as String? != s.llmTuningProfile ||
            mockitoStrictnessBox.selectedItem as String? != s.mockitoStrictness
    }

    override fun apply() {
        // Mutation directe sur la state — XmlSerializerUtil persiste à la
        // prochaine sauvegarde de l'application (gérée par la plateforme).
        val s = settings.state
        s.strategy = strategyField.text.ifBlank { "recursive-deep" }
        s.outputMode = (outputModeBox.selectedItem as? String) ?: "COPY"
        s.llmProvider = providerField.text.ifBlank { "claude" }
        s.llmModel = modelField.text.ifBlank { "claude-sonnet-4-6" }
        s.llmTuningProfile = (tuningProfileBox.selectedItem as? String) ?: "qwen"
        s.mockitoStrictness = (mockitoStrictnessBox.selectedItem as? String) ?: "STRICT_STUBS"
    }

    override fun reset() {
        val s = settings.state
        strategyField.text = s.strategy
        outputModeBox.selectedItem = s.outputMode
        providerField.text = s.llmProvider
        modelField.text = s.llmModel
        tuningProfileBox.selectedItem = s.llmTuningProfile
        mockitoStrictnessBox.selectedItem = s.mockitoStrictness
    }

    override fun disposeUIResources() {
        rootPanel = null
    }
}

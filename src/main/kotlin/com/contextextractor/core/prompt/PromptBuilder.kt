package com.contextextractor.core.prompt

import com.contextextractor.core.model.ContextTree
import com.contextextractor.core.model.MetaKeys
import com.contextextractor.core.prompt.meta.LayerKind
import com.contextextractor.core.prompt.constraints.ConstraintsProfile
import com.contextextractor.core.prompt.constraints.Qwen36b35bProfile
import com.contextextractor.core.prompt.constraints.resolveConstraintsProfile
import com.contextextractor.core.prompt.stages.CleanupStage
import com.contextextractor.core.prompt.stages.ContextRenderStage
import com.contextextractor.core.prompt.stages.LayerCompositionStage
import com.contextextractor.core.prompt.stages.MetaPromptComposeStage

// Orchestrateur du pipeline — ARCHITECTURE.md §7.
//
// **Contrat** : la chaîne `stages` est appliquée dans l'ordre fourni ; le
// dernier stage doit avoir peuplé `PromptContext.finalText` (sinon `build()`
// jette). Aucun stage n'est obligatoire individuellement — la composition
// par défaut (cf [defaultPipeline]) en livre 4, mais un test ou un override
// utilisateur peut en injecter d'autres.
//
// **Court-circuit testabilité** (PROMPT_FORMAT.md §"Special case —
// UNTESTABLE_AS_IS at SUT level") : si `tree.root.metadata[TESTABILITY]`
// vaut "false", la méthode cible n'est pas testable automatiquement. On
// retourne directement un bloc d'alerte humain (PAS un prompt LLM avec des
// sections vides) — l'IDE doit le présenter dans un dialog/Tool Window au
// lieu d'envoyer quoi que ce soit au LLM. Le préfixe `STOP-UNTESTABLE\n`
// permet à l'appelant de détecter le cas sans parser le contenu.
//
// **Pas de couplage IDE/PSI ici** : `core/` reste pur. Les stages travaillent
// sur le `ContextTree` déjà produit par la stratégie.
class PromptBuilder(private val stages: List<PromptStage>) {

    fun build(tree: ContextTree, userEnrichment: String? = null): String {
        if (isUntestableAtSutLevel(tree)) {
            return renderUntestableDiagnostic(tree)
        }

        val ctx = PromptContext(tree = tree)
        if (!userEnrichment.isNullOrBlank()) {
            ctx.layers[LayerKind.USER_ENRICHMENT] = userEnrichment
        }
        var current = ctx
        for (stage in stages) {
            current = stage.apply(current)
        }
        return current.finalText
            ?: error("PromptBuilder: aucun stage n'a peuplé finalText (dernier stage = ${stages.last().id})")
    }

    private fun isUntestableAtSutLevel(tree: ContextTree): Boolean =
        tree.root.metadata[MetaKeys.TESTABILITY] == "false"

    // Format aligné PROMPT_FORMAT.md §"Special case — UNTESTABLE_AS_IS at SUT
    // level". Le préfixe `STOP-UNTESTABLE` est un sentinelle parsable :
    // l'IDE peut router vers un dialog plutôt que vers le LLM sans inspecter
    // le contenu. Pas de section `=== CONTEXT ===` etc. — c'est volontaire :
    // on doit voir au premier coup d'œil que ce n'est PAS un prompt à envoyer.
    private fun renderUntestableDiagnostic(tree: ContextTree): String {
        val meta = tree.root.metadata
        val blocking = meta[MetaKeys.TESTABILITY_BLOCKING_FIELDS].orEmpty()
        val reasons = meta[MetaKeys.TESTABILITY_REASONS].orEmpty()
        val hints = meta[MetaKeys.TESTABILITY_REFACTOR_HINTS].orEmpty()
        return buildString {
            appendLine("STOP-UNTESTABLE")
            appendLine("⚠️ Cette méthode ne peut pas être testée automatiquement.")
            appendLine()
            if (blocking.isNotEmpty()) {
                appendLine("Champs bloquants : $blocking")
                appendLine()
            }
            if (reasons.isNotEmpty()) {
                appendLine("Raisons :")
                reasons.split('\n').forEach { appendLine("  - $it") }
                appendLine()
            }
            if (hints.isNotEmpty()) {
                appendLine("Pistes de refactoring :")
                hints.split('\n').forEach { appendLine("  - $it") }
            }
        }.trimEnd() + "\n"
    }

    companion object {
        // Pipeline par défaut V1 : ContextRender → MetaPromptCompose →
        // LayerComposition → Cleanup. C'est l'ordre exact d'ARCHITECTURE.md §7.
        // Step 6 brachera un override (templates depuis YAML) en passant un
        // `LayerCompositionStage(templates = ...)` custom.
        //
        // Phase 5 — overload qui accepte un `ConstraintsProfile`. Le défaut
        // sans argument utilise [Qwen36b35bProfile] pour préserver le
        // comportement V1.1 (rétro-compat des tests existants).
        fun defaultPipeline(): PromptBuilder = defaultPipeline(Qwen36b35bProfile)

        fun defaultPipeline(profile: ConstraintsProfile): PromptBuilder = PromptBuilder(
            listOf(
                ContextRenderStage(),
                MetaPromptComposeStage(),
                LayerCompositionStage(LayerCompositionStage.Templates.defaults(profile)),
                CleanupStage()
            )
        )

        // Helper résolvant un profil depuis une string config — pour
        // appel depuis l'IDE qui n'a accès qu'à la valeur YAML/Settings.
        fun defaultPipelineForProfileId(profileId: String?): PromptBuilder =
            defaultPipeline(resolveConstraintsProfile(profileId))
    }
}

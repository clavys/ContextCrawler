package com.contextextractor.core.prompt

// Maillon du pipeline de construction du prompt. Les 4 implémentations
// (ContextRenderStage, MetaPromptComposeStage, LayerCompositionStage,
// CleanupStage) arrivent à l'étape 5. Voir ARCHITECTURE.md §7.
interface PromptStage {
    val id: String

    fun apply(ctx: PromptContext): PromptContext
}

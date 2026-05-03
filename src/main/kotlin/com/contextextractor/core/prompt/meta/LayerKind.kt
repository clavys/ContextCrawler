package com.contextextractor.core.prompt.meta

// Layers du prompt final — voir ARCHITECTURE.md §7 et PROMPT_FORMAT.md.
// L'ordre d'assemblage est défini par LayerCompositionStage à l'étape 5.
enum class LayerKind {
    SYSTEM,
    CONTEXT,
    USER_ENRICHMENT,
    CONSTRAINTS,
    INSTRUCTION,
    ROLE,
    TASK,
    CUSTOM
}

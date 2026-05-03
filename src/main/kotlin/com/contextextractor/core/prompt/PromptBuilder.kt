package com.contextextractor.core.prompt

import com.contextextractor.core.model.ContextTree

// Orchestrateur du pipeline — voir ARCHITECTURE.md §7.
// Les stages sont appliqués dans l'ordre fourni; le dernier doit avoir
// peuplé PromptContext.finalText.
class PromptBuilder(private val stages: List<PromptStage>) {

    fun build(tree: ContextTree): String {
        // Pipeline opérationnel à l'étape 5 (les 4 stages concrets seront
        // implémentés à ce moment).
        throw NotImplementedError("Implemented at step 5")
    }
}

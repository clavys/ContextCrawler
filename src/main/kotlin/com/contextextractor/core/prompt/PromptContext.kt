package com.contextextractor.core.prompt

import com.contextextractor.core.model.ContextTree
import com.contextextractor.core.prompt.meta.LayerKind

// État partagé entre les PromptStage du pipeline — voir ARCHITECTURE.md §7.
data class PromptContext(
    val tree: ContextTree,
    val layers: MutableMap<LayerKind, String> = mutableMapOf(),
    val metaSlots: MutableMap<String, String> = mutableMapOf(),
    var finalText: String? = null
)

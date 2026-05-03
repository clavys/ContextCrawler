package com.contextextractor.core.model

// Nœud du ContextTree. Hiérarchie sealed pour permettre des sous-types
// spécialisés (TargetMethod, Mock, DataStruct, etc.) ajoutés ultérieurement.
sealed interface ContextNode {
    val id: String
    val kind: NodeKind
    val title: String
    val children: List<ContextNode>
    val metadata: Map<String, String>
}

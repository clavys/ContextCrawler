package com.contextextractor.core.model

// Étiquette stable d'un nœud du ContextTree (vs Mode qui est contextuel).
// Voir ARCHITECTURE.md §4 — note "NodeKind vs Mode".
enum class NodeKind {
    HIERARCHY,
    TARGET_METHOD,
    FIELD,
    CONSTRUCTOR,
    SETTER,
    INTERNAL_METHOD,
    MOCK,
    DATA_STRUCTURE,
    EXCEPTION,
    DOCUMENTATION,
    GIT_DIFF,
    CUSTOM
}

package com.contextextractor.core.model

// Modèle de contexte unifié — voir ARCHITECTURE.md §4.
// Un seul arbre partagé entre toutes les stratégies d'extraction et tous
// les renderers de prompt. Pas de mapper intermédiaire.
data class ContextTree(
    val root: ContextNode,
    private val index: Map<String, ContextNode>,
    private val byKind: Map<NodeKind, List<ContextNode>>,
    val truncated: Boolean = false,
    val truncationReasons: List<String> = emptyList()
) {
    fun byId(id: String): ContextNode? = index[id]

    fun ofKind(kind: NodeKind): List<ContextNode> = byKind[kind].orEmpty()

    fun walk(): Sequence<ContextNode> = sequence {
        // Parcours DFS — implémentation à l'étape 4 quand le modèle sera peuplé.
        throw NotImplementedError("Implemented at step 4")
    }
}

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

    // DFS pré-ordre itératif : root puis descendance. Utile aux renderers qui
    // veulent visiter chaque nœud sans recourir à `byKind` (utilité quand
    // l'ordre structurel doit être préservé — ex : empiler les FIELDs dans
    // l'ordre de découverte plutôt que groupés par stratégie).
    //
    // Implémentation itérative car `sequence { … }` n'autorise pas de récursion
    // directe via fonctions imbriquées (SequenceScope.yield est `suspend` mais
    // on ne peut pas définir de `suspend fun` locale dans le builder).
    fun walk(): Sequence<ContextNode> = sequence {
        val stack = ArrayDeque<ContextNode>()
        stack.addFirst(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            yield(node)
            // Pousse en ordre inverse pour que les enfants sortent dans l'ordre
            // déclaratif (head-first DFS).
            for (i in node.children.indices.reversed()) {
                stack.addFirst(node.children[i])
            }
        }
    }
}

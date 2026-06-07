package com.contextextractor.core.model.depgraph

// Graphe de dépendances NOM-SEUL construit depuis une SUT — V1.3 §10.6 recommandation #1.
//
// **Sémantique** : à partir d'une classe racine (la SUT), énumérer TOUTES les
// classes atteignables via :
//   • Type des champs (et type-args des génériques)
//   • Type de retour des méthodes + type des params
//   • Superclasse + interfaces
//
// La profondeur est bornée par la **logique des tests unitaires** :
//   • Frontières framework (`javax.faces.*`, `org.primefaces.*`, …) →
//     on note le nom mais on n'explore pas l'intérieur
//   • Types système (`java.*`, `kotlin.*`, …) → exclus (bruit)
//   • Types résolus mais à grande profondeur → tronqués via `maxDepth`
//
// **Usages prévus** (§10.6) :
//   (a) Fallback PSI quand `ReferenceGraphBuilder.findMethodIn` retourne null
//       sur un call intra-SUT (Bug #C bis)
//   (b) Commande de debugging IDE (dump JSON/DOT)
//   (c) Property test : "toute classe classifiée MOCK doit avoir un edge ici"
//
// **Coût** : ~300-500 nœuds sur un controleur typique (Astrea), ~50-100 KB
// mémoire, BFS PSI ~1-2 s. Indépendant du pipeline existant → pas de risque
// de régression sur les 444 tests V1.2.
data class DependencyGraph(
    val rootFqn: String,
    val nodes: Set<String>,
    // Arêtes sortantes : pour chaque classe, l'ensemble des classes qu'elle
    // référence. Une arête manquante = la classe n'a pas été explorée (raison
    // dans `truncatedAt`) ou est une feuille.
    val edges: Map<String, Set<String>>,
    // Distance BFS depuis `rootFqn`. La racine a depth=0.
    val depthOf: Map<String, Int>,
    // Liste des FQN dont l'exploration a été coupée + raison.
    // Format : `"$fqn ($reason)"` — ex : `"javax.faces.context.FacesContext (framework)"`.
    val truncatedAt: List<String>
) {

    val nodeCount: Int get() = nodes.size

    val edgeCount: Int get() = edges.values.sumOf { it.size }

    // Profondeur max effectivement atteinte par le BFS.
    val maxDepthReached: Int get() = depthOf.values.maxOrNull() ?: 0

    // Renvoie un sous-graphe ne contenant que les classes dont le nom matche
    // un prefix. Utile pour filtrer le bruit dans le dump (ex : ne garder que
    // `fr.gouv.justice.astrea.*`).
    fun filteredByPrefix(prefix: String): DependencyGraph {
        val keptNodes = nodes.filter { it.startsWith(prefix) }.toSet()
        val keptEdges = edges
            .filterKeys { it in keptNodes }
            .mapValues { (_, v) -> v.filter { it in keptNodes }.toSet() }
        val keptDepth = depthOf.filterKeys { it in keptNodes }
        return DependencyGraph(rootFqn, keptNodes, keptEdges, keptDepth, truncatedAt)
    }

    // ── Rendus utilisateur ──────────────────────────────────────────────────

    // Format JSON minimaliste, parsable par jq/Python. Pas d'indentation pour
    // garder léger (les graphes peuvent compter des centaines de nœuds).
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"rootFqn\":\"$rootFqn\",")
        sb.append("\"nodeCount\":$nodeCount,")
        sb.append("\"edgeCount\":$edgeCount,")
        sb.append("\"maxDepthReached\":$maxDepthReached,")
        sb.append("\"nodes\":[")
        sb.append(nodes.sorted().joinToString(",") { "\"$it\"" })
        sb.append("],\"edges\":{")
        sb.append(edges.toSortedMap().entries.joinToString(",") { (from, to) ->
            "\"$from\":[${to.sorted().joinToString(",") { "\"$it\"" }}]"
        })
        sb.append("},\"depthOf\":{")
        sb.append(depthOf.toSortedMap().entries.joinToString(",") { (fqn, d) ->
            "\"$fqn\":$d"
        })
        sb.append("},\"truncatedAt\":[")
        sb.append(truncatedAt.joinToString(",") { "\"$it\"" })
        sb.append("]}")
        return sb.toString()
    }

    // Format Graphviz DOT, copiable dans <https://dreampuf.github.io/GraphvizOnline>
    // pour visualisation interactive. Les nœuds tronqués sont en pointillés.
    fun toDot(): String {
        val sb = StringBuilder()
        sb.append("digraph DependencyGraph {\n")
        sb.append("  rankdir=LR;\n")
        sb.append("  node [shape=box, fontsize=10];\n")
        // Nœud racine en double-octogone pour identification visuelle.
        sb.append("  \"$rootFqn\" [shape=doubleoctagon, color=blue, fontcolor=blue];\n")
        // Nœuds tronqués : on extrait juste le FQN avant la parenthèse.
        val truncatedFqns = truncatedAt.map { it.substringBefore(" (") }.toSet()
        truncatedFqns.forEach { fqn ->
            if (fqn != rootFqn) {
                sb.append("  \"$fqn\" [style=dashed, color=gray];\n")
            }
        }
        // Arêtes.
        edges.toSortedMap().forEach { (from, tos) ->
            tos.sorted().forEach { to ->
                sb.append("  \"$from\" -> \"$to\";\n")
            }
        }
        sb.append("}\n")
        return sb.toString()
    }

    // Résumé textuel court pour notification IDE.
    fun toSummary(): String =
        "DependencyGraph[root=$rootFqn, nodes=$nodeCount, edges=$edgeCount, " +
        "maxDepth=$maxDepthReached, truncated=${truncatedAt.size}]"
}

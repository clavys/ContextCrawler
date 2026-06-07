package com.contextextractor.strategies.depgraph

import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.model.depgraph.DependencyGraph

// Builder du graphe de dépendances NOM-SEUL — V1.3 §10.6 recommandation #1.
//
// **Algorithme** : BFS plat depuis `sutFqn` :
//   1. Résoudre la classe via le port
//   2. Énumérer ses arêtes sortantes (champs + méthodes + super/interfaces)
//   3. Pour chaque enfant : énoncer comme nœud, enregistrer l'arête,
//      énqueuer à profondeur+1 (si pas déjà visité)
//
// **Bornes** :
//   • `frameworkPrefixes` : classes dont on garde le nom mais qu'on n'explore
//     pas l'intérieur (ex : `javax.faces.*` pour Astrea)
//   • `systemPrefixes` : classes qu'on n'enregistre même pas (bruit JDK)
//   • `maxDepth` : garde-fou anti-divergence
//
// **Indépendance du pipeline existant** : ce builder ne touche ni
// `ReferenceGraphBuilder`, ni le classifier, ni le renderer. Il est consommé
// uniquement par l'action IDE `DumpDependencyGraphAction` en V1.3 étape 1, et
// pourra servir de fallback PSI en étape 2 (cf §10.6).
class DependencyGraphBuilder(
    private val introspector: CodeIntrospector,
    private val frameworkPrefixes: List<String> = emptyList(),
    private val systemPrefixes: List<String> = DEFAULT_SYSTEM_PREFIXES,
    private val maxDepth: Int = 10
) {

    fun build(sutFqn: String): DependencyGraph {
        val rootNormalized = normalizeFqn(sutFqn)
            ?: throw IllegalArgumentException("Invalid root FQN: '$sutFqn'")

        val visited = mutableSetOf<String>()
        val edges = mutableMapOf<String, MutableSet<String>>()
        val depthOf = mutableMapOf(rootNormalized to 0)
        val truncatedAt = mutableListOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootNormalized to 0)

        while (queue.isNotEmpty()) {
            val (fqn, depth) = queue.removeFirst()
            if (!visited.add(fqn)) continue

            // ── Ordre des checks (post-cleanup V1.3 step 1) ────────────────
            // Système et primitives EN PREMIER pour éviter qu'ils soient
            // tagués `(maxDepth)` quand ils sont atteints à profondeur=cap.
            // (case 4.1 Astrea avait `java.lang.String (maxDepth)` parasite
            // avant ce fix.)
            if (systemPrefixes.any { fqn.startsWith(it) }) {
                visited.remove(fqn)
                continue
            }
            if (fqn in PRIMITIVE_FQNS || fqn == "void" || fqn.isEmpty()) {
                visited.remove(fqn)
                continue
            }
            if (depth >= maxDepth) {
                truncatedAt.add("$fqn (maxDepth)")
                continue
            }
            if (frameworkPrefixes.any { fqn.startsWith(it) }) {
                // Cas spécial : on garde le nom (pour les lookups) mais on
                // n'énumère pas l'intérieur. Cohérent avec STRATEGIE §3.3
                // "ne jamais lire le corps des méthodes externes".
                truncatedAt.add("$fqn (framework)")
                continue
            }

            val cls = introspector.resolveClass(fqn)
            if (cls == null) {
                // Classe non résolvable (import cassé, type généré non présent).
                // On garde le nom (utile pour le diagnostic) mais sans descendre.
                truncatedAt.add("$fqn (unresolved)")
                continue
            }

            val children = collectChildren(cls)
            if (children.isNotEmpty()) {
                edges[fqn] = children.toMutableSet()
            }
            children.forEach { childFqn ->
                if (childFqn !in depthOf) {
                    depthOf[childFqn] = depth + 1
                }
                queue.add(childFqn to depth + 1)
            }
        }

        return DependencyGraph(
            rootFqn = rootNormalized,
            nodes = visited.toSet(),
            edges = edges.mapValues { it.value.toSet() },
            depthOf = depthOf.toMap(),
            truncatedAt = truncatedAt.toList()
        )
    }

    // Normalise une référence de type avant insertion dans le graphe.
    // Retourne null si la référence n'est pas une vraie classe (cleanup post
    // case 4.1 Astrea — 24 entrées parasites « unresolved » nettoyées).
    //
    // Stratégie :
    //   • `Foo[]` / `Foo[][]` → `Foo` (strip des suffixes arrays)
    //   • Primitives (`int`, `long`, etc.) → null (filtré comme bruit)
    //   • Type variables (`T`, `E`, `K`, `V`, etc.) → null (pas une vraie FQN)
    //     Heuristique : une vraie FQN contient au moins un point. Une variable
    //     de type Java standard ne sera jamais qualifiée par un package.
    private fun normalizeFqn(raw: String?): String? {
        if (raw == null) return null
        var s = raw.trim()
        if (s.isEmpty()) return null
        // Strip arrays récursivement : `Foo[][]` → `Foo`.
        while (s.endsWith("[]")) s = s.removeSuffix("[]").trim()
        if (s.isEmpty()) return null
        if (s in PRIMITIVE_FQNS || s == "void") return null
        // Variable de type (T, E, K, V, ou T1, T2...) → pas une vraie classe.
        if ('.' !in s) return null
        return s
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun collectChildren(cls: ClassDescriptor): Set<String> {
        val out = LinkedHashSet<String>()

        // Helper local : normalise et ajoute SI valide. Centralise le strip
        // des arrays + filtre primitives/type-vars en un point.
        fun addNormalized(raw: String?) {
            normalizeFqn(raw)?.let { out.add(it) }
        }

        // 1. Hiérarchie : superclasse + interfaces.
        addNormalized(cls.superFqn)
        cls.interfaces.forEach { addNormalized(it) }

        // 2. Champs : type du champ + type-args.
        runCatching { introspector.listFields(cls) }.getOrNull()?.forEach { field ->
            addNormalized(field.type.fqName)
            field.type.typeArgs.forEach { arg -> addNormalized(arg.fqName) }
        }

        // 3. Méthodes : returnType + type des params + leurs type-args.
        runCatching { introspector.listMethods(cls) }.getOrNull()?.forEach { method ->
            addNormalized(method.returnType.fqName)
            method.returnType.typeArgs.forEach { arg -> addNormalized(arg.fqName) }
            method.parameters.forEach { param ->
                addNormalized(param.type.fqName)
                param.type.typeArgs.forEach { arg -> addNormalized(arg.fqName) }
            }
        }

        return out.toSet()
    }

    companion object {
        // Préfixes exclus complètement (pas même le nom, pour limiter la
        // taille du graphe au pertinent). Bypassable via construction custom.
        val DEFAULT_SYSTEM_PREFIXES: List<String> = listOf(
            "java.", "javax.lang.", "javax.annotation.", "javax.inject.",
            "kotlin.", "scala.", "sun.", "com.sun."
        )

        private val PRIMITIVE_FQNS: Set<String> = setOf(
            "boolean", "byte", "char", "short", "int", "long", "float", "double"
        )
    }
}

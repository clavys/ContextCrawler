package com.contextextractor.core.config

// Merge de plusieurs ConfigSource par priorité — voir ARCHITECTURE.md §8.
// Sources livrées : DefaultsConfigSource (0), IntellijSettingsSource (10),
// YamlProjectConfigSource (20).
//
// **Sémantique de merge** (deep-merge récursif sur Map<String, Any>) :
//   • base = priorité la plus basse, overlay = priorité la plus haute.
//   • Clé présente dans overlay seulement → ajoutée au résultat.
//   • Clé présente dans base seulement → conservée.
//   • Clé présente dans les deux, valeurs Map → fusionnées récursivement.
//   • Clé présente dans les deux, scalaires/listes → overlay écrase
//     (override total — pas de concaténation de listes en V1).
//   • **Clé présente dans overlay avec valeur null → supprimée du résultat**
//     (mécanisme de désactivation : YAML peut neutraliser une clé du défaut).
//
// **Lookup** : [get] accepte une clé pointée (`"budget.maxDepth"`) et traverse
// les Map imbriquées. Retourne `null` si la clé n'existe pas ou si le type
// ne matche pas — pas d'exception silencieuse, l'appelant décide du fallback.
class LayeredConfig(private val sources: List<ConfigSource>) {

    // Lazy + immutable : le merge n'est calculé qu'une fois, puis figé.
    // Coût raisonnable car les sources sont peu nombreuses (3 V1).
    // Le résultat est un `Map<String, Any>` non-null car deepMerge consomme
    // les `null` overlays (= suppression) sans les réintroduire.
    private val merged: Map<String, Any> by lazy {
        sources
            .sortedBy { it.priority }
            .map { it.load() }
            .fold(emptyMap()) { acc, overlay -> deepMerge(acc, overlay) }
    }

    // Vue brute du merge — utile aux tests et au binder typesafe.
    fun raw(): Map<String, Any> = merged

    // Lookup pointé. `key` peut être "a.b.c" : on traverse les Map intermédiaires.
    // Retourne `null` si le chemin n'existe pas ou si la valeur finale n'est
    // pas du type demandé (pas de cast forcé).
    fun <T : Any> get(key: String, type: Class<T>): T? {
        val value = lookup(merged, key.split('.')) ?: return null
        return if (type.isInstance(value)) type.cast(value) else null
    }

    // Surcharge inline pour confort Kotlin : `cfg.get<Int>("budget.maxDepth")`.
    inline fun <reified T : Any> get(key: String): T? = get(key, T::class.java)

    private fun lookup(node: Map<String, Any>, path: List<String>): Any? {
        if (path.isEmpty()) return null
        val head = path.first()
        val tail = path.drop(1)
        val v = node[head] ?: return null
        return when {
            tail.isEmpty() -> v
            v is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                lookup(v as Map<String, Any>, tail)
            }
            else -> null  // chemin trop long pour un scalaire/liste : pas un cas d'erreur, juste null
        }
    }

    companion object {
        // Deep-merge public car appelé par tests + binders. La signature accepte
        // `Map<String, *>` côté overlay pour tolérer la valeur `null` qui
        // signifie « supprimer cette clé ».
        @Suppress("UNCHECKED_CAST")
        fun deepMerge(base: Map<String, Any>, overlay: Map<String, *>): Map<String, Any> {
            val out = base.toMutableMap()
            for ((k, v) in overlay) {
                when {
                    // null = suppression (mécanisme de désactivation côté overlay).
                    v == null -> out.remove(k)
                    // Deux Maps → fusion récursive.
                    v is Map<*, *> && out[k] is Map<*, *> -> {
                        out[k] = deepMerge(
                            out[k] as Map<String, Any>,
                            v as Map<String, *>
                        )
                    }
                    // Tout le reste : overlay écrase (scalaire, liste, ou nouvelle clé).
                    else -> out[k] = v
                }
            }
            return out
        }
    }
}

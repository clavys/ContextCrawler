package com.contextextractor.core.config

// Merge de plusieurs ConfigSource par priorité — voir ARCHITECTURE.md §8.
// Sources livrées : DefaultsConfigSource (0), IntellijSettingsSource (10),
// YamlProjectConfigSource (20).
class LayeredConfig(private val sources: List<ConfigSource>) {

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, type: Class<T>): T? {
        // Merge par priorité (deep merge) implémenté à l'étape 6.
        throw NotImplementedError("Implemented at step 6")
    }
}

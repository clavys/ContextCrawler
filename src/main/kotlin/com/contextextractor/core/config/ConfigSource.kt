package com.contextextractor.core.config

// Source de configuration. Plus la priorité est élevée, plus la source écrase
// les autres lors du merge. Voir ARCHITECTURE.md §8.
interface ConfigSource {
    val priority: Int

    fun load(): Map<String, Any>
}

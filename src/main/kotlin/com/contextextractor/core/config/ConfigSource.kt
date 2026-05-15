package com.contextextractor.core.config

// Source de configuration. Plus la priorité est élevée, plus la source écrase
// les autres lors du merge. Voir ARCHITECTURE.md §8.
//
// **Type de valeur = `Any?`** : une valeur `null` en sortie d'une source signifie
// « supprimer cette clé » lors du deep-merge — c'est le mécanisme par lequel
// un YAML peut désactiver une option par défaut. Sans ça, un YAML vide ne
// pourrait jamais retirer une entrée fournie par DefaultsConfigSource.
interface ConfigSource {
    val priority: Int

    fun load(): Map<String, Any?>
}

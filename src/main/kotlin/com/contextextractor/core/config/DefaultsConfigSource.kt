package com.contextextractor.core.config

// Source de config priorité 0 — fournit les défauts compilés.
//
// **Source de vérité** : les défauts sont alignés sur les data classes
// `ContextExtractorConfig` et `Budget` pour garantir qu'un YAML vide produit
// le même résultat que `ContextExtractorConfig()` direct. Si un défaut change
// dans une data class (ex: `Budget.maxDepth=8` un jour), il faut le refléter
// ici — ce verrou est testé par `DefaultsConfigSourceTest`.
//
// **Pourquoi le dupliquer** : LayeredConfig manipule des `Map<String, Any>`
// (format générique pour merger n'importe quelle source YAML/IDE). Le binder
// typesafe lira ensuite ce merge pour produire un `ContextExtractorConfig`.
// La source des défauts doit donc exposer le format Map, pas le data class.
class DefaultsConfigSource : ConfigSource {

    override val priority: Int = 0

    override fun load(): Map<String, Any> = mapOf(
        "strategy" to "recursive-deep",
        "outputMode" to "COPY",
        "templates" to mapOf(
            "dir" to "",                          // pas de répertoire custom V1
            "default" to "deep-unit-test"
        ),
        "classification" to mapOf(
            "mockSuffixes" to listOf("Service", "Repository", "Gateway"),
            "dataSuffixes" to listOf("DTO", "Entity", "Request", "Response", "Command")
        ),
        "llm" to mapOf(
            "provider" to "claude",
            "model" to "claude-sonnet-4-6",
            "temperature" to 0.2
        ),
        "prompt" to mapOf(
            "templates" to emptyMap<String, String>(),
            "layers" to emptyMap<String, String>()
        ),
        "budget" to mapOf(
            "maxDepth" to 6,
            "maxInitDepth" to 2,
            "maxGraphDepth" to 4,
            "maxDtoCount" to 15,
            "maxMockCount" to 10,
            "maxInternalLogicCount" to 12,
            "maxEstimatedTokens" to 8000
        )
    )
}

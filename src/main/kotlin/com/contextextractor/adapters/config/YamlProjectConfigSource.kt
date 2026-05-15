package com.contextextractor.adapters.config

import com.contextextractor.core.config.ConfigSource
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

// Source de config priorité 20 — `.contextextractor.yml` à la racine projet.
// Voir ARCHITECTURE.md §8 et CLAUDE.md étape 6.
//
// **Hexagonal** : vit dans `adapters/` car couplée à SnakeYAML. Le `core/`
// ne dépend que de `ConfigSource` (interface) — un autre adapter pourrait un
// jour produire la config depuis JSON ou une UI sans toucher au moteur.
//
// **Robustesse aux cas dégradés** :
//   • fichier absent          → load() retourne emptyMap (pas d'erreur).
//   • fichier vide / commenté → emptyMap (Yaml.load() retourne null).
//   • YAML mal formé          → IllegalStateException avec le chemin du fichier
//     (un fail silencieux serait pire qu'une erreur explicite au démarrage).
//   • clé inconnue            → ignorée par le binder, pas par cette source
//     (la source recopie tout ce qui est dans le fichier).
//
// **Normalisation des nombres** — verrou pivot 6-β :
//   SnakeYAML 2.x parse les entiers YAML comme `Integer` quand ils tiennent
//   dans la plage Int, et comme `Long`/`BigInteger` au-delà. Si l'utilisateur
//   écrit `maxEstimatedTokens: 200000` et que SnakeYAML retourne un `Long`,
//   `LayeredConfig.get<Int>(...)` retournerait null silencieusement.
//   On NORMALISE ici : tout `Long` qui tient dans Int est rabaissé en Int.
//   Les `BigInteger` qui tiennent dans Long sont rabaissés en Long. Au-delà,
//   la valeur reste large (le binder validé Budget jettera lors de validated()).
class YamlProjectConfigSource(
    private val configFile: Path
) : ConfigSource {

    override val priority: Int = 20

    override fun load(): Map<String, Any?> {
        if (!Files.exists(configFile)) return emptyMap()
        val text = try {
            Files.readString(configFile)
        } catch (e: Exception) {
            // Lecture qui échoue (permissions, encodage exotique) → message clair.
            // Pas de fallback silencieux : l'utilisateur voit son fichier — mieux
            // vaut un démarrage en erreur qu'une config par défaut sournoise.
            throw IllegalStateException(
                "Impossible de lire $configFile : ${e.message}", e
            )
        }
        return parse(text, configFile.toString())
    }

    companion object {
        // Exposé pour les tests : permet d'injecter le YAML brut sans
        // matérialiser un fichier sur disque.
        @Suppress("UNCHECKED_CAST")
        fun parse(yamlText: String, sourceLabel: String): Map<String, Any?> {
            if (yamlText.isBlank()) return emptyMap()
            val raw = try {
                Yaml().load<Any?>(yamlText)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "YAML invalide dans $sourceLabel : ${e.message}", e
                )
            }
            if (raw == null) return emptyMap()
            require(raw is Map<*, *>) {
                "$sourceLabel : la racine doit être un mapping YAML " +
                    "(reçu: ${raw::class.simpleName})"
            }
            // SnakeYAML peut renvoyer des clés non-String dans certains cas
            // (ex: `42: foo`). Le binder ne saura pas quoi en faire — on
            // exige donc des clés String à la racine et récursivement.
            return normalizeMap(raw as Map<Any?, Any?>, sourceLabel)
        }

        // Normalisation récursive :
        //   • clés non-String → IllegalStateException (la convention CLAUDE.md
        //     est camelCase string).
        //   • Long → Int si la valeur tient dans Int (verrou pivot 6-β).
        //   • Map<*, *> → récursion.
        //   • Liste → normalisation élément par élément (peut contenir des Map
        //     imbriquées avec des nombres).
        //   • Reste (String, Boolean, Double, BigInteger trop large) → laissé tel quel.
        private fun normalizeMap(map: Map<Any?, Any?>, sourceLabel: String): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>(map.size)
            for ((rawKey, rawValue) in map) {
                require(rawKey is String) {
                    "$sourceLabel : clé non-String détectée ('$rawKey') — " +
                        "les clés YAML doivent être des chaînes camelCase"
                }
                out[rawKey] = normalizeValue(rawValue, sourceLabel)
            }
            return out
        }

        private fun normalizeValue(value: Any?, sourceLabel: String): Any? = when (value) {
            null -> null
            is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                value.toInt() else value
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                normalizeMap(value as Map<Any?, Any?>, sourceLabel)
            }
            is List<*> -> value.map { normalizeValue(it, sourceLabel) }
            else -> value
        }
    }
}

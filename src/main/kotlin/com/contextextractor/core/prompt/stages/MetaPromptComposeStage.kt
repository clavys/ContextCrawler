package com.contextextractor.core.prompt.stages

import com.contextextractor.core.prompt.PromptContext
import com.contextextractor.core.prompt.PromptStage
import com.contextextractor.core.prompt.meta.LayerKind

// Deuxième stage du pipeline — ARCHITECTURE.md §7 (option 1 : templates
// imbriqués). Résout les inclusions `{{>name}}` dans CHAQUE layer en
// remplaçant le marqueur par le contenu du partial correspondant.
//
// **V1 = passthrough fonctionnel** : aucun partial n'est livré dans les
// templates par défaut, donc le stage ne réécrit rien. Le seam reste utile :
//   * il fixe l'ordre du pipeline (avant LayerCompositionStage qui assemble) ;
//   * il sert de point d'extension pour l'étape 6 (config YAML), où l'utilisateur
//     pourra définir `prompt.templates.partials.X = path/to/fragment.md` et
//     injecter `{{>X}}` dans n'importe quelle layer.
//
// **Idempotence** : appliquer le stage deux fois donne le même résultat dès
// lors qu'un partial ne contient pas lui-même de `{{>autre}}` non-résolu.
// On limite donc à UNE passe — les inclusions transitives sont volontairement
// hors V1 (cf §14 « Pipeline LLM avancé »).
class MetaPromptComposeStage(
    private val partials: Map<String, String> = emptyMap()
) : PromptStage {

    override val id: String = "meta-prompt-compose"

    override fun apply(ctx: PromptContext): PromptContext {
        if (partials.isEmpty()) return ctx
        // Une passe sur chaque layer — pas de récursion. Une inclusion qui
        // référence elle-même un autre partial reste littérale (volontaire V1).
        for (kind in LayerKind.entries) {
            val text = ctx.layers[kind] ?: continue
            val resolved = resolvePartials(text)
            if (resolved !== text) ctx.layers[kind] = resolved
        }
        return ctx
    }

    private fun resolvePartials(text: String): String {
        // Format strict du marqueur : `{{>name}}` (pas d'espace, pas de slash).
        // Les espaces autour du nom sont tolérés pour confort éditorial.
        if (!text.contains("{{>")) return text
        return PARTIAL_REGEX.replace(text) { match ->
            val name = match.groupValues[1].trim()
            // Inconnu → garde le marqueur pour que l'utilisateur le voie dans
            // le prompt et corrige son template (mieux qu'un trou silencieux).
            partials[name] ?: match.value
        }
    }

    companion object {
        private val PARTIAL_REGEX = Regex("""\{\{>\s*([^}\s]+)\s*}}""")
    }
}

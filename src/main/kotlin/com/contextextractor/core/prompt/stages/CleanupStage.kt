package com.contextextractor.core.prompt.stages

import com.contextextractor.core.prompt.PromptContext
import com.contextextractor.core.prompt.PromptStage

// Quatrième stage du pipeline — ARCHITECTURE.md §7. Dernier maillon : il
// nettoie `ctx.finalText` produit par LayerCompositionStage avant restitution.
//
// **Ce qu'il fait** :
//   * compacte 3+ sauts de ligne consécutifs en exactement 2 (paragraphes
//     séparés par UNE ligne vide, jamais plus) ;
//   * strip le whitespace en fin de chaque ligne (les éditeurs râlent) ;
//   * normalise les fins de ligne à `\n` (Windows + CRLF accidentels) ;
//   * trim global + un unique `\n` final.
//
// **Ce qu'il NE fait PAS** :
//   * il ne supprime PAS les blocs `{{#hasX}}…` mentionnés dans
//     ARCHITECTURE.md §7. Ces blocs n'existent pas dans les templates V1 et
//     leur résolution est différée à l'étape 6 (templates utilisateur). Le
//     stage est volontairement minimal — c'est un nettoyage cosmétique, pas
//     un moteur de templating.
//
// **Sortie garantie** : `ctx.finalText` est non-null et ne contient ni `\r`,
// ni double saut de ligne (compactés à 2), ni espaces traînants en fin de
// ligne.
class CleanupStage : PromptStage {

    override val id: String = "cleanup"

    override fun apply(ctx: PromptContext): PromptContext {
        val raw = ctx.finalText
            ?: error("CleanupStage: finalText absent. LayerCompositionStage doit s'exécuter avant.")
        val cleaned = raw
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .let { MULTI_BLANK_LINES.replace(it, "\n\n") }
            .trim()
        ctx.finalText = cleaned + "\n"
        return ctx
    }

    companion object {
        // 3+ retours à la ligne consécutifs → 2 (= une ligne vide entre
        // paragraphes). On évite ainsi les trous laissés par
        // expandTruncationBlock() ou les layers vides en suspens.
        private val MULTI_BLANK_LINES = Regex("""\n{3,}""")
    }
}

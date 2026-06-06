package com.contextextractor.core.prompt.constraints

// Profil de tuning des CONSTRAINTS du prompt — Phase 5 du refactor V1.2.
//
// **Motivation** : la version V1.1 mélangeait dans `LayerCompositionStage`
// deux familles de règles dans la section `CONSTRAINTS` :
//
//   • Des règles UNIVERSELLES Java/JUnit/Mockito (`@Test` requis, pas de
//     `public`, AssertJ pour les assertions, etc.) — applicables quel que
//     soit le LLM cible.
//   • Des PATCHES SPÉCIFIQUES OBSERVÉS sur Qwen 3.6 35B (Bug Y/Z/AA/DD)
//     — hand-holding pour des modes d'échec récurrents de ce modèle
//     précis sur des cas Mockito subtils (exceptions checked, génériques
//     en `thenReturn`, `verify` avec `new`, construction d'éléments pour
//     un container stub).
//
// Mélanger les deux pousse à allonger le prompt même quand le LLM cible
// n'a pas ces modes d'échec — gaspillage de tokens et signal noyé.
//
// **Contrat** : un `ConstraintsProfile` expose `tuningText` — un bloc
// markdown qui sera CONCATÉNÉ à la fin du `BaseConstraints.TEXT`. L'ordre
// est important : Base d'abord (règles structurelles), tuning ensuite
// (patches LLM-specific).
//
// **Profils livrés V1.2** :
//   • [Qwen36b35bProfile] — patches Y/Z/AA/DD. Default — comportement
//     historique préservé pour rétro-compat.
//   • [NoTuningProfile]   — aucun patch. Utile pour LLM puissants
//     (Claude, GPT-4) qui n'ont pas besoin de hand-holding sur ces cas.
sealed interface ConstraintsProfile {
    val id: String
    val displayName: String
    val tuningText: String
}

// Profil par défaut — patches Qwen 3.6 35B (Bug Y/Z/AA/DD). Cf
// RAPPORT_CONTEXT §9.9.
object Qwen36b35bProfile : ConstraintsProfile {
    override val id: String = "qwen-3.6-35b"
    override val displayName: String = "Qwen 3.6 35B (patches Y/Z/AA/DD)"
    override val tuningText: String = QwenTuningConstraints.TEXT
}

// Profil sans tuning — règles universelles seulement.
object NoTuningProfile : ConstraintsProfile {
    override val id: String = "none"
    override val displayName: String = "No tuning (base rules only)"
    override val tuningText: String = ""
}

// Résolution d'un profil depuis sa string id. Default = Qwen (rétro-compat).
fun resolveConstraintsProfile(id: String?): ConstraintsProfile = when (id?.lowercase()) {
    null, "", "qwen", "qwen-3.6-35b" -> Qwen36b35bProfile
    "none", "no-tuning", "baseline" -> NoTuningProfile
    else -> Qwen36b35bProfile // Fallback safe : id inconnu → Qwen (jamais de prompt sans hand-holding par accident).
}

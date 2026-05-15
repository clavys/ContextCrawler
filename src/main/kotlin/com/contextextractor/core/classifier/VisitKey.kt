package com.contextextractor.core.classifier

// Clé du registre anti-cycle — STRATEGIE.md §2.2 (CleVisite → VisitKey).
// Le tuple (mode, classe, méthode?) garantit qu'un même type visité dans deux
// modes distincts (ex. CALL_TARGET puis MOCK_EXTERNAL) compte pour deux entrées.
//
// Convention pour `methodCanonical` selon le mode (cf. §3.1-§3.4) :
//   SUT_BOOTSTRAP   → nom simple de methodeCible
//   INTERNAL_LOGIC  → MethodSignature.canonical()
//   MOCK_EXTERNAL   → MethodSignature.canonical() ou null si entrée sans méthode
//   DATA_STRUCTURE  → null
data class VisitKey(
    val mode: ExtractionMode,
    val classFqn: String,
    val methodCanonical: String? = null
)

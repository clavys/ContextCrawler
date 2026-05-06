package com.contextextractor.core.extractor

// Descripteur de classe — vue côté core/, indépendant de PSI.
//
// `permittedSubclasses` est non-vide uniquement si `isSealed=true` (Java 17+).
// `enumValues` est non-vide uniquement si `isEnum=true` ; l'ordre est l'ordre
// de déclaration. Ces deux listes sont consommées par recurseDataStructure
// (§3.4 Phase 2) : SEALED y trouve les sous-classes à récurer, ENUM la liste
// exhaustive des constantes (le générateur de tests s'en sert pour les
// branches `switch`).
data class ClassDescriptor(
    val fqn: String,
    val simpleName: String,
    val superFqn: String?,
    val interfaces: List<String> = emptyList(),
    val isAbstract: Boolean = false,
    val isInterface: Boolean = false,
    val isRecord: Boolean = false,
    val isSealed: Boolean = false,
    val isEnum: Boolean = false,
    val annotations: List<String> = emptyList(),
    val visibility: String = "public",
    val packageName: String = "",
    val permittedSubclasses: List<String> = emptyList(),
    val enumValues: List<String> = emptyList()
)

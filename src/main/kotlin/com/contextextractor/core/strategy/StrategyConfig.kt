package com.contextextractor.core.strategy

// Config par stratégie. Le détail (mockSuffixes, dataSuffixes, etc.) est
// défini par chaque stratégie. À l'étape 1, on ne porte que le budget et
// la classification minimale.
data class StrategyConfig(
    val budget: Budget = Budget(),
    val mockSuffixes: List<String> = emptyList(),
    val dataSuffixes: List<String> = emptyList(),
    val excludedPackages: List<String> = emptyList()
)

package com.contextextractor.core.strategy

// Config par stratégie. Le détail (mockSuffixes, dataSuffixes, etc.) est
// défini par chaque stratégie. À l'étape 1, on ne porte que le budget et
// la classification minimale.
data class StrategyConfig(
    val budget: Budget = Budget(),
    val mockSuffixes: List<String> = emptyList(),
    val dataSuffixes: List<String> = emptyList(),
    val excludedPackages: List<String> = emptyList(),
    // Défaut #1 (§3.2bis). Préfixes FQN considérés comme cadre framework
    // non-mockable raisonnablement. Une méthode intra-SUT qui appelle
    // directement une classe matchant un de ces préfixes est marquée
    // STUB_VIA_SPY : son corps n'est pas exploré et elle apparaît dans une
    // section dédiée du prompt avec un pattern `spy(sut) + doAnswer(...)`.
    val frameworkPackagePrefixes: List<String> = listOf(
        "javax.faces.",
        "jakarta.faces.",
        "org.primefaces.",
        "javax.servlet.",
        "jakarta.servlet.",
        "java.io.",
        "java.net.",
        "java.nio."
    )
)

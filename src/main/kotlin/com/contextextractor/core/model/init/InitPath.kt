package com.contextextractor.core.model.init

import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.Parameter

// Catégorisation d'un point d'entrée trouvé par EntryPointFinder — STRATEGIE.md §4.4.
// Un seul kind par chemin :
//   • IMPLICIT_VIA_CONSTRUCTOR : le ctor de la SUT initialise le champ ; aucun
//     appel utilisateur supplémentaire n'est requis dans le test généré.
//   • PUBLIC_POST_CONSTRUCT   : la méthode d'entrée porte @PostConstruct ;
//     le test devra l'appeler explicitement après instanciation.
//   • PUBLIC_TRANSITIF        : méthode publique « ordinaire » qui (directement
//     ou via une chaîne d'appels privés) atteint la méthode assignatrice.
enum class InitPathKind {
    IMPLICIT_VIA_CONSTRUCTOR,
    PUBLIC_POST_CONSTRUCT,
    PUBLIC_TRANSITIF
}

// Chemin d'initialisation candidat retourné par le BFS — STRATEGIE.md §4.4
// (CheminInitialisation). Le chemin élu (score minimal) sera consommé par
// StrategySelector pour choisir une StrategieInit concrète.
//
// **Convention d'ordre dans `chain`** : ordre du parcours BFS, donc
// `[methodeAssignatrice, parentDansLeGrapheInverse, ..., entryPoint]`. Le
// dernier élément est le point d'entrée publiquement appelable. C'est l'inverse
// de l'ordre d'invocation à l'exécution — le test généré appelle d'abord
// `chain.last()` qui propage l'effet jusqu'à `chain.first()`.
data class InitPath(
    val kind: InitPathKind,
    // Pour IMPLICIT_VIA_CONSTRUCTOR : le ctor lui-même (utile pour StrategySelector).
    // Pour PUBLIC_*                  : la méthode publique à appeler.
    val entryPoint: MethodKey,
    val chain: List<MethodKey>,
    val depth: Int,
    // Paramètres du point d'entrée — vide pour IMPLICIT_VIA_CONSTRUCTOR (le
    // ctor reste géré par BLOC 4, pas réinjecté ici).
    val parametersRequired: List<Parameter>,
    // Appels vers des classes hors hiérarchie SUT, agrégés sur l'ENSEMBLE de
    // la chaîne (§4.4 : `collecterAppelsExternes(current.chaine, callGraph)`).
    val externalCallsToStub: List<MethodCall>,
    // Champs assignés sur la chaîne, autres que le champ cible (§4.4 :
    // `collecterChampsAssignes(current.chaine) - {champ.nom}`). Représente
    // les effets de bord d'un appel au point d'entrée — coût de testabilité.
    val sideEffects: Set<String>,
    val score: Int
)

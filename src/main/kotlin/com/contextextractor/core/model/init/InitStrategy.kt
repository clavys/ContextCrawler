package com.contextextractor.core.model.init

import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter

// Décision d'initialisation pour un champ — STRATEGIE.md §4.5 sortie
// (`StrategieInit`). 12 sous-types : 4 objets pour les stratégies sans
// paramètre + 8 data classes pour celles qui portent un payload (méthode,
// chaîne d'appels, args, stubs, raison…).
//
// Une instance par champ ; agrégées en `FieldInitProtocol` côté ContextResult.
// Le rendu en CONTEXT layer (PromptStage) est sous la responsabilité du
// pipeline §6 — pas de logique de prompt ici.
//
// Localisation : `core/model/init/` plutôt que `strategies/recursive/initbloc/`
// car ce type apparaît dans `ContextResult.FieldInitProtocol` (donc côté model)
// et toute stratégie future (Shallow, GitDiff…) qui produit un protocole
// d'init le réutilisera. Voir ARCHITECTURE.md §3 (arrows core ← strategies).
sealed class InitStrategy {

    // 1 — Le champ est passé au constructeur de la SUT (BLOC 4 le gère déjà).
    object CONSTRUCTOR : InitStrategy()

    // 5 / 10 — Le champ est implicitement initialisé (FieldInitializer sûr ou
    // auto-init dans methodeCible). Aucun appel utilisateur requis.
    object IMPLICIT : InitStrategy()

    // 7a — Le ctor de la SUT initialise le champ via une chaîne d'appels
    // privés (le BFS a remonté jusqu'à un ctor public).
    object IMPLICIT_VIA_CONSTRUCTOR : InitStrategy()

    // 2 — Champ @Autowired/@Inject : Mockito.@InjectMocks fait le travail.
    object MOCKITO_INJECT_MOCKS : InitStrategy()

    // 3 — Setter public direct.
    data class SETTER(val methodName: String) : InitStrategy()

    // 4 / 7b — Méthode @PostConstruct (directe ou trouvée via BFS).
    data class CALL_POST_CONSTRUCT(val method: MethodSignature) : InitStrategy()

    // 6a — Méthode publique sans param sans appel externe.
    data class CALL_PUBLIC(val method: MethodSignature) : InitStrategy()

    // 6b — Méthode publique sans param mais qui appelle des dépendances externes
    // qu'il faudra mocker via Mockito.when(...).
    data class CALL_PUBLIC_WITH_STUBS(
        val method: MethodSignature,
        val stubsRequired: List<MethodCall>
    ) : InitStrategy()

    // 6c — Méthode publique avec arguments à fournir.
    data class CALL_PUBLIC_WITH_ARGS(
        val method: MethodSignature,
        val args: List<Parameter>
    ) : InitStrategy()

    // 7c — Point d'entrée transitif trouvé via BFS sur le graphe inverse.
    data class CALL_PUBLIC_TRANSITIVE(
        val entryPoint: MethodSignature,
        val callChain: List<String>,        // noms de méthodes ordre BFS
        val args: List<Parameter>,
        val stubsRequired: List<MethodCall>,
        val sideEffects: List<String>,
        // Étape 7 #3 — callees intra-SUT atteints en aval depuis l'assignment
        // site, format `name(fqn1,fqn2,...)` (canonical). Vide si l'assignment
        // site n'appelle rien d'intra-SUT. Ordre BFS forward depuis le seed.
        val downstreamChain: List<String> = emptyList()
    ) : InitStrategy()

    // 8 / 9 — Setter ou méthode initialisatrice protected/package — accessibles
    // si le test est placé dans le même package que la SUT.
    data class CALL_SAME_PACKAGE(
        val method: MethodSignature,
        val callChain: List<String>
    ) : InitStrategy()

    // 11 — Aucun chemin trouvé. raison = explication courte pour humain ;
    // pistesRefacto = suggestions concrètes d'évolution du code (cf §4.6).
    data class UNTESTABLE_AS_IS(
        val reason: String,
        val refactorHints: List<String>
    ) : InitStrategy()
}

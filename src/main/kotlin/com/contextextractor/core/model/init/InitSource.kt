package com.contextextractor.core.model.init

import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.extractor.ResolvedType

// Source d'initialisation possible pour un champ donné — STRATEGIE.md §4.1.
//
// Mirroir de `sealed class SourceInit` (§ ARCHITECTURE.md §10), traduit en
// nommage anglais. Une exécution de SourceCollector retourne une `List<InitSource>`
// pour CHAQUE champ analysé ; StrategySelector y appliquera l'arbre de décision
// à 11 branches.
//
// Localisation : `core/model/init/` car ce type apparaît dans
// `ContextResult.FieldInitProtocol.sources` (modèle, pas algorithme).
//
// V1 : InitializerBlock non implémenté (réserve C1 — aucun cas test 91-95
// ne contient de bloc `{ … }` d'instance).
sealed class InitSource {

    // §4.1 CONSTRUCTOR — le ctor sélectionné a un paramètre dont le nom et le
    // FQN du type matchent ceux du champ analysé. V1 : matching strict (pas
    // d'assignabilité par héritage).
    data class Constructor(val parameterName: String) : InitSource()

    // §4.1 FIELD_INITIALIZER — `private final Logger log = LoggerFactory.…;`.
    // V1 : `initType` = type DÉCLARÉ du champ. Pas d'analyse de l'expression
    // pour inférer un sous-type concret — le port n'expose pas le type résolu
    // de l'initializer. Le classifier décidera (SYSTEM_IGNORE / ENUM / etc.).
    data class FieldInitializer(val expression: String, val initType: ResolvedType) : InitSource()

    // §4.1 SETTER — méthode publique `setX(T)` où la conversion `setX → x`
    // matche le nom du champ ET param.type.fqName == champ.type.fqName.
    // V1 : strict FQN, public uniquement.
    data class Setter(val methodName: String, val parameterType: ResolvedType) : InitSource()

    // §4.1 METHOD_INITIALIZER — toute méthode (autre que le ctor sélectionné)
    // qui assigne `this.champ = …`.
    //   • kind                  : POST_CONSTRUCT si annotée @PostConstruct, sinon ORDINARY
    //   • parametersRequired    : params formels de la méthode (utilisés par scorer §4.4)
    //   • hasNullGuard          : true si l'assignation est gardée par `if (x == null)` (§4.2 conditionEstNullCheck)
    //   • externalCalls         : appels vers classes hors hiérarchie SUT (filtre statique exclu)
    //   • assignsAlso           : noms des AUTRES champs assignés par cette méthode
    //
    // **Important pour le scorer §4.4** : `assignsAlso` est cross-champ — il
    // est calculé sur l'ensemble des assignments de la méthode, pas seulement
    // sur le champ analysé. Une fixture qui ne déclare que l'assignment du
    // champ cible verra `assignsAlso = []` artificiellement, donc des effets
    // de bord sous-estimés. Tester ce comportement (test verrou correspondant).
    data class MethodInitializer(
        val kind: MethodInitKind,
        val method: MethodSignature,
        val visibility: String,
        val parametersRequired: List<Parameter>,
        val hasNullGuard: Boolean,
        val externalCalls: List<MethodCall>,
        val assignsAlso: List<String>,
        // R3-B (Phase 2) — Calls in the body whose receiver is one of the params
        // (i.e. `paramName.getter()`). Empty if no params, or params not used.
        // Quand la stratégie choisie est CALL_PUBLIC_WITH_ARGS, ces calls
        // documentent les getters que le LLM DOIT stuber sur le mock du param
        // pour éviter une NPE runtime (cas Astrea 4.1 `calculerPremierDernierElementsPage`).
        val paramCallsToStub: List<MethodCall> = emptyList()
    ) : InitSource()
}

enum class MethodInitKind { POST_CONSTRUCT, ORDINARY }

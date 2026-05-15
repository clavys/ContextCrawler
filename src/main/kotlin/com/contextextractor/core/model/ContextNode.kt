package com.contextextractor.core.model

// Nœud du ContextTree. Hiérarchie sealed pour permettre des sous-types
// spécialisés (TargetMethodNode, MockNode, etc.) ajoutés ultérieurement.
//
// V1 utilise uniquement [BasicContextNode] — les sous-types spécialisés
// arriveront si la complexité du rendu le justifie. Toute info structurée
// supplémentaire passe par `metadata` (Map<String, String>).
sealed interface ContextNode {
    val id: String
    val kind: NodeKind
    val title: String
    val children: List<ContextNode>
    val metadata: Map<String, String>
}

// Implémentation de référence — couvre les 12 NodeKind sans spécialisation.
// Le rendu (ContextRenderStage) lit `kind` pour brancher le template, puis
// pioche dans `metadata` pour les détails (visibility, type FQN, raison
// UNTESTABLE, etc.). Voir [MetaKeys] pour les clés de metadata réservées.
data class BasicContextNode(
    override val id: String,
    override val kind: NodeKind,
    override val title: String,
    override val children: List<ContextNode> = emptyList(),
    override val metadata: Map<String, String> = emptyMap()
) : ContextNode

// Clés réservées dans `ContextNode.metadata`. Centralisé pour la même raison
// que [NodeIds] : éviter qu'un renderer cherche "fieldType" pendant qu'un
// mapper écrit "field_type". Toute lecture / écriture passe par cette table.
object MetaKeys {
    // Champ : type FQN, visibilité, annotations sérialisées (séparées par ',').
    const val FIELD_TYPE_FQN = "fieldTypeFqn"
    const val FIELD_VISIBILITY = "fieldVisibility"
    const val FIELD_ANNOTATIONS = "fieldAnnotations"
    const val FIELD_DECLARING_CLASS = "fieldDeclaringClass"

    // BLOC 7 — stratégie d'init élue. Le rendu lit `INIT_STRATEGY_KIND` pour
    // brancher le template §6 ; les autres clés portent les payloads spécifiques.
    const val INIT_STRATEGY_KIND = "initStrategyKind"
    const val INIT_REASON = "initReason"
    const val INIT_REFACTOR_HINTS = "initRefactorHints"   // séparées par '\n'
    const val INIT_METHOD_NAME = "initMethodName"
    const val INIT_CALL_CHAIN = "initCallChain"           // séparées par ' → '
    const val INIT_ARGS = "initArgs"                      // 'name:typeFqn' séparés par ', '
    const val INIT_STUBS = "initStubs"                    // 'classFqn#method(args)' séparés par ', '
    const val INIT_SIDE_EFFECTS = "initSideEffects"       // séparées par ', '

    // Mock : type concret + déclaré + signatures à stubber + flag retour-mock.
    const val MOCK_DECLARED_TYPE = "mockDeclaredType"
    const val MOCK_CONCRETE_TYPE = "mockConcreteType"
    const val MOCK_SIGNATURES = "mockSignatures"          // 'name(types):returnType' séparés par '\n'
    const val MOCK_RETURN_NESTED = "mockReturnNested"     // "true"/"false"

    // DTO : pattern + champs + builder/factory si applicable.
    const val DTO_PATTERN = "dtoPattern"
    const val DTO_FIELDS = "dtoFields"                    // 'name:typeFqn' séparés par ', '

    // Méthode cible / interne : signature complète canonique.
    const val METHOD_CANONICAL = "methodCanonical"
    const val METHOD_RETURN_TYPE = "methodReturnType"
    const val METHOD_PARAMS = "methodParams"              // 'name:typeFqn' séparés par ', '

    // Corps source des méthodes intra-SUT — STRATEGIE.md §3.1 (SUT_BOOTSTRAP)
    // et §3.2 (INTERNAL_LOGIC). Frontière fermée pour MOCK_EXTERNAL (§3.3 « STOP :
    // ne jamais lire le corps des méthodes externes »). Vide si non capturé.
    const val METHOD_BODY = "methodBody"                  // root node — corps de methodeCible
    const val INTERNAL_METHOD_BODY = "internalMethodBody" // nœuds INTERNAL_METHOD

    // Hiérarchie : niveaux séparés par ' → ' (du SUT à la super-classe la plus haute).
    const val HIERARCHY_LEVELS = "hierarchyLevels"

    // Diagnostic global de testabilité (porté sur le nœud root TARGET_METHOD).
    // PromptBuilder court-circuite à un bloc d'alerte si TESTABILITY="false".
    const val TESTABILITY = "testability"                 // "true"/"false"
    const val TESTABILITY_BLOCKING_FIELDS = "testabilityBlockingFields"
    const val TESTABILITY_REASONS = "testabilityReasons"           // séparées par '\n'
    const val TESTABILITY_REFACTOR_HINTS = "testabilityRefactorHints" // séparées par '\n'
}

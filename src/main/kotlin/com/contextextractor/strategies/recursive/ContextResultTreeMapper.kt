package com.contextextractor.strategies.recursive

import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.model.BasicContextNode
import com.contextextractor.core.model.ContextNode
import com.contextextractor.core.model.ContextResult
import com.contextextractor.core.model.ContextTree
import com.contextextractor.core.model.DataStructureInfo
import com.contextextractor.core.model.FieldInitProtocol
import com.contextextractor.core.model.InternalLogic
import com.contextextractor.core.model.MetaKeys
import com.contextextractor.core.model.MockInfo
import com.contextextractor.core.model.NodeIds
import com.contextextractor.core.model.NodeKind
import com.contextextractor.core.model.StaticCallInfo
import com.contextextractor.core.model.init.InitStrategy

// Mappe le `ContextResult` produit par RecursiveDeepStrategy vers le modèle
// unifié `ContextTree` consommé par les PromptStage et les renderers.
//
// **Forme de l'arbre produit** (pré-ordre DFS) :
//
//   TARGET_METHOD (root)
//     ├── HIERARCHY              [un seul nœud avec hierarchyLevels en metadata]
//     ├── FIELD x N              [un par champ utile, dans l'ordre §4.7
//     │                           via `initOrder` du ContextResult]
//     ├── MOCK x N               [un par classe externe à mocker, ordre LinkedHashMap]
//     ├── INTERNAL_METHOD x N    [un par méthode SUT-hiérarchie visitée]
//     ├── DATA_STRUCTURE x N     [un par DTO]
//     └── (autres : static calls, exceptions… selon disponibilité)
//
// **Convention d'id** : voir [NodeIds]. Toute lecture downstream (renderer,
// tests, IDE highlight) PASSE PAR `tree.byId(NodeIds.xxx(...))`. Aucun renderer
// n'a le droit de fabriquer une chaîne d'id à la main.
//
// **Pas de logique métier** : le mapper recopie/sérialise des champs déjà
// résolus en BLOC 1-7. Aucune décision de stratégie ici. Si une donnée manque
// du résultat, c'est l'extracteur qui doit être enrichi, pas le mapper.
class ContextResultTreeMapper {

    fun map(result: ContextResult): ContextTree {
        val children = mutableListOf<ContextNode>()
        children += hierarchyNode(result)
        children += fieldNodes(result)
        children += mockNodes(result.mocks)
        children += internalMethodNodes(result.internalLogics)
        children += dtoNodes(result.dataStructures)
        children += staticCallNodes(result.staticCalls)

        val root = BasicContextNode(
            id = NodeIds.target(result.sutFqName, result.targetMethod.signature),
            kind = NodeKind.TARGET_METHOD,
            title = "${result.sutFqName}#${result.targetMethod.signature.canonical()}",
            children = children,
            metadata = targetMethodMetadata(result)
        )

        // Pré-construit les index — évite à ContextTree.byId / ofKind de
        // re-walker à chaque appel. La structure est immutable.
        val flat = walkFlat(root)
        val byId = flat.associateBy { it.id }
        val byKind = flat.groupBy { it.kind }

        return ContextTree(
            root = root,
            index = byId,
            byKind = byKind,
            truncated = result.truncated,
            truncationReasons = result.truncationReasons
        )
    }

    // ── TARGET_METHOD root metadata ──────────────────────────────────────────

    private fun targetMethodMetadata(result: ContextResult): Map<String, String> {
        val sig = result.targetMethod.signature
        val out = mutableMapOf(
            MetaKeys.METHOD_CANONICAL to sig.canonical(),
            MetaKeys.METHOD_RETURN_TYPE to sig.returnType.fqName,
            MetaKeys.METHOD_PARAMS to sig.parameters.joinToString(", ") {
                "${it.name}:${it.type.fqName}"
            },
            MetaKeys.TESTABILITY to result.testabilityDiagnostic.testable.toString()
        )
        val diag = result.testabilityDiagnostic
        if (diag.blockingFields.isNotEmpty()) {
            out[MetaKeys.TESTABILITY_BLOCKING_FIELDS] = diag.blockingFields.joinToString(", ")
        }
        // Reasons + refactor hints sérialisées sur le ROOT pour que
        // PromptBuilder puisse court-circuiter sans re-walker l'arbre. Format
        // un-par-ligne ('\n') — cohérent avec INIT_REFACTOR_HINTS.
        if (diag.reasons.isNotEmpty()) {
            out[MetaKeys.TESTABILITY_REASONS] = diag.reasons.joinToString("\n")
        }
        if (diag.refactorHints.isNotEmpty()) {
            out[MetaKeys.TESTABILITY_REFACTOR_HINTS] = diag.refactorHints.joinToString("\n")
        }
        // Corps source de la méthode cible — STRATEGIE.md §3.1. Sérialisé en
        // metadata pour transit jusqu'au renderer ; vide si le port n'a pas
        // exposé de body (cas dégradé §8bis).
        if (result.targetMethod.body.isNotEmpty()) {
            out[MetaKeys.METHOD_BODY] = result.targetMethod.body
        }
        return out
    }

    // ── HIERARCHY ────────────────────────────────────────────────────────────

    private fun hierarchyNode(result: ContextResult): ContextNode {
        val levelsStr = result.hierarchy.joinToString(" → ") { it.classFqn }
        return BasicContextNode(
            id = NodeIds.hierarchy(result.sutFqName),
            kind = NodeKind.HIERARCHY,
            title = "Hiérarchie de ${result.sutFqName}",
            metadata = mapOf(MetaKeys.HIERARCHY_LEVELS to levelsStr)
        )
    }

    // ── FIELDs : ordre §4.7 via initOrder ────────────────────────────────────

    private fun fieldNodes(result: ContextResult): List<ContextNode> {
        // Si initOrder est vide (dégénéré : aucun champ utile), on retombe sur
        // l'ordre de découverte de `fields`. En pratique, dès lors que BLOC 7
        // a tourné, initOrder est rempli.
        val nameToProtocol = result.initProtocol
        val orderedNames = if (result.initOrder.isNotEmpty()) result.initOrder
        else result.fields.map { it.name }
        return orderedNames.mapNotNull { fname ->
            val field = result.fields.firstOrNull { it.name == fname } ?: return@mapNotNull null
            fieldNode(field, nameToProtocol[fname])
        }
    }

    private fun fieldNode(field: ClassField, protocol: FieldInitProtocol?): ContextNode {
        val meta = mutableMapOf(
            MetaKeys.FIELD_TYPE_FQN to field.type.fqName,
            MetaKeys.FIELD_VISIBILITY to field.visibility,
            MetaKeys.FIELD_DECLARING_CLASS to field.declaredIn
        )
        if (field.annotations.isNotEmpty()) {
            meta[MetaKeys.FIELD_ANNOTATIONS] = field.annotations.joinToString(",")
        }
        if (protocol != null) {
            populateInitMetadata(meta, protocol.recommendedStrategy)
        }
        return BasicContextNode(
            id = NodeIds.field(field.declaredIn, field.name),
            kind = NodeKind.FIELD,
            title = field.name,
            metadata = meta
        )
    }

    // §4.5 sortie → metadata pour brancher le rendu §6 par template.
    private fun populateInitMetadata(meta: MutableMap<String, String>, strategy: InitStrategy) {
        meta[MetaKeys.INIT_STRATEGY_KIND] = strategyKindLabel(strategy)
        when (strategy) {
            is InitStrategy.SETTER -> meta[MetaKeys.INIT_METHOD_NAME] = strategy.methodName
            is InitStrategy.CALL_POST_CONSTRUCT -> meta[MetaKeys.INIT_METHOD_NAME] = strategy.method.name
            is InitStrategy.CALL_PUBLIC -> meta[MetaKeys.INIT_METHOD_NAME] = strategy.method.name
            is InitStrategy.CALL_PUBLIC_WITH_STUBS -> {
                meta[MetaKeys.INIT_METHOD_NAME] = strategy.method.name
                meta[MetaKeys.INIT_STUBS] = strategy.stubsRequired.joinToString(", ") {
                    "${it.targetType}#${it.methodName}(${it.argTypes.joinToString(",")})"
                }
            }
            is InitStrategy.CALL_PUBLIC_WITH_ARGS -> {
                meta[MetaKeys.INIT_METHOD_NAME] = strategy.method.name
                meta[MetaKeys.INIT_ARGS] = strategy.args.joinToString(", ") {
                    "${it.name}:${it.type.fqName}"
                }
            }
            is InitStrategy.CALL_PUBLIC_TRANSITIVE -> {
                meta[MetaKeys.INIT_METHOD_NAME] = strategy.entryPoint.name
                // `callChain` est stocké dans l'ordre BFS canonique
                // `[methodeAssignatrice(args), ..., entryPoint(args)]` (chaque
                // élément est `MethodSignature.canonical()` = `name(fqn,...)`).
                // `downstreamChain` est stocké dans l'ordre BFS forward depuis
                // le seed `[appelleeDirect, ..., feuille]` (étape 7 #3).
                // Le rendu utilisateur concatène les deux pour produire l'ordre
                // RUNTIME complet :
                //   reverse(callChain) ++ downstreamChain
                //   = [entryPoint, ..., assignmentSite, callee1, callee2, ...]
                // EXPECTED_PROMPTS.md case93 verrouille
                // `start → startInternal → warmup → buildCache`.
                // La data class reste source de vérité BFS — on transforme ICI.
                val runtimeChain = strategy.callChain.reversed() + strategy.downstreamChain
                meta[MetaKeys.INIT_CALL_CHAIN] = runtimeChain
                    .map { it.substringBefore('(') }
                    .joinToString(" → ")
                if (strategy.args.isNotEmpty()) {
                    meta[MetaKeys.INIT_ARGS] = strategy.args.joinToString(", ") {
                        "${it.name}:${it.type.fqName}"
                    }
                }
                if (strategy.stubsRequired.isNotEmpty()) {
                    meta[MetaKeys.INIT_STUBS] = strategy.stubsRequired.joinToString(", ") {
                        "${it.targetType}#${it.methodName}(${it.argTypes.joinToString(",")})"
                    }
                }
                if (strategy.sideEffects.isNotEmpty()) {
                    meta[MetaKeys.INIT_SIDE_EFFECTS] = strategy.sideEffects.joinToString(", ")
                }
            }
            is InitStrategy.CALL_SAME_PACKAGE -> {
                meta[MetaKeys.INIT_METHOD_NAME] = strategy.method.name
                // Même contrat BFS que CALL_PUBLIC_TRANSITIVE — reverse + strip
                // signature pour l'ordre runtime / nom propre côté rendu.
                meta[MetaKeys.INIT_CALL_CHAIN] = strategy.callChain
                    .reversed()
                    .map { it.substringBefore('(') }
                    .joinToString(" → ")
            }
            is InitStrategy.UNTESTABLE_AS_IS -> {
                meta[MetaKeys.INIT_REASON] = strategy.reason
                meta[MetaKeys.INIT_REFACTOR_HINTS] = strategy.refactorHints.joinToString("\n")
            }
            // Branches sans payload — `INIT_STRATEGY_KIND` suffit au renderer.
            InitStrategy.CONSTRUCTOR,
            InitStrategy.IMPLICIT,
            InitStrategy.IMPLICIT_VIA_CONSTRUCTOR,
            InitStrategy.MOCKITO_INJECT_MOCKS -> Unit
        }
    }

    // Label stable pour le renderer — découplé de `InitStrategy::class.simpleName`
    // qui est fragile (renommage Kotlin = nouveau label silencieux).
    private fun strategyKindLabel(strategy: InitStrategy): String = when (strategy) {
        InitStrategy.CONSTRUCTOR -> "CONSTRUCTOR"
        InitStrategy.IMPLICIT -> "IMPLICIT"
        InitStrategy.IMPLICIT_VIA_CONSTRUCTOR -> "IMPLICIT_VIA_CONSTRUCTOR"
        InitStrategy.MOCKITO_INJECT_MOCKS -> "MOCKITO_INJECT_MOCKS"
        is InitStrategy.SETTER -> "SETTER"
        is InitStrategy.CALL_POST_CONSTRUCT -> "CALL_POST_CONSTRUCT"
        is InitStrategy.CALL_PUBLIC -> "CALL_PUBLIC"
        is InitStrategy.CALL_PUBLIC_WITH_STUBS -> "CALL_PUBLIC_WITH_STUBS"
        is InitStrategy.CALL_PUBLIC_WITH_ARGS -> "CALL_PUBLIC_WITH_ARGS"
        is InitStrategy.CALL_PUBLIC_TRANSITIVE -> "CALL_PUBLIC_TRANSITIVE"
        is InitStrategy.CALL_SAME_PACKAGE -> "CALL_SAME_PACKAGE"
        is InitStrategy.UNTESTABLE_AS_IS -> "UNTESTABLE_AS_IS"
    }

    // ── MOCKs ────────────────────────────────────────────────────────────────

    private fun mockNodes(mocks: Map<String, MockInfo>): List<ContextNode> =
        mocks.values.map { mock ->
            val sigStr = mock.requiredSignatures.joinToString("\n") { signatureToOneLine(it) }
            BasicContextNode(
                id = NodeIds.mock(mock.concreteClass),
                kind = NodeKind.MOCK,
                title = mock.declaredType,
                metadata = buildMap {
                    put(MetaKeys.MOCK_DECLARED_TYPE, mock.declaredType)
                    put(MetaKeys.MOCK_CONCRETE_TYPE, mock.concreteClass)
                    if (sigStr.isNotEmpty()) put(MetaKeys.MOCK_SIGNATURES, sigStr)
                    put(MetaKeys.MOCK_RETURN_NESTED, mock.returnIsNestedMock.toString())
                }
            )
        }

    // ── INTERNAL_METHODs ─────────────────────────────────────────────────────

    private fun internalMethodNodes(logics: Map<String, InternalLogic>): List<ContextNode> =
        logics.entries.map { (key, logic) ->
            // `key` est déjà au format "$classFqn#${canonical}" (cf
            // RecursiveDeepStrategy.recurseInternalLogic.logicKey) — réutilisé tel
            // quel par NodeIds pour garantir la cohérence avec callGraph.
            val classFqn = key.substringBefore('#')
            BasicContextNode(
                id = NodeIds.internalMethod(classFqn, logic.signature.canonical()),
                kind = NodeKind.INTERNAL_METHOD,
                title = "$classFqn#${logic.signature.canonical()}",
                metadata = buildMap {
                    put(MetaKeys.METHOD_CANONICAL, logic.signature.canonical())
                    put(MetaKeys.METHOD_RETURN_TYPE, logic.signature.returnType.fqName)
                    if (logic.callSummaries.isNotEmpty()) {
                        put("internalCallSummaries", logic.callSummaries.joinToString("\n"))
                    }
                    // Corps source — STRATEGIE.md §3.2. Frontière intra-SUT
                    // ouverte (cf §3.3 « STOP » qui ne s'applique qu'aux mocks).
                    if (logic.body.isNotEmpty()) {
                        put(MetaKeys.INTERNAL_METHOD_BODY, logic.body)
                    }
                }
            )
        }

    // ── DATA_STRUCTUREs ──────────────────────────────────────────────────────

    private fun dtoNodes(structures: Map<String, DataStructureInfo>): List<ContextNode> =
        structures.values.map { dto ->
            BasicContextNode(
                id = NodeIds.dto(dto.fqName),
                kind = NodeKind.DATA_STRUCTURE,
                title = dto.fqName,
                metadata = buildMap {
                    put(MetaKeys.DTO_PATTERN, dto.pattern.name)
                    if (dto.fields.isNotEmpty()) {
                        put(MetaKeys.DTO_FIELDS, dto.fields.joinToString(", ") {
                            "${it.name}:${it.type.fqName}"
                        })
                    }
                }
            )
        }

    // ── STATIC CALLS ─────────────────────────────────────────────────────────

    private fun staticCallNodes(staticCalls: List<StaticCallInfo>): List<ContextNode> =
        staticCalls.map { sc ->
            BasicContextNode(
                id = NodeIds.staticCall(sc.classFqn, sc.methodName),
                kind = NodeKind.CUSTOM,  // pas de NodeKind dédié en V1 — `metadata` porte le détail
                title = "${sc.classFqn}#${sc.methodName}",
                metadata = mapOf(
                    "staticClass" to sc.classFqn,
                    "staticMethod" to sc.methodName,
                    MetaKeys.METHOD_CANONICAL to sc.signature.canonical()
                )
            )
        }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun signatureToOneLine(sig: MethodSignature): String {
        val params = sig.parameters.joinToString(",") { it.type.fqName }
        return "${sig.name}($params):${sig.returnType.fqName}"
    }

    // Walker DFS local — utilisé pour pré-construire l'index sans dépendre de
    // `ContextTree.walk()` (qui n'est disponible qu'une fois l'arbre construit).
    private fun walkFlat(root: ContextNode): List<ContextNode> {
        val out = mutableListOf<ContextNode>()
        val stack = ArrayDeque<ContextNode>()
        stack.addFirst(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            out += n
            for (i in n.children.indices.reversed()) stack.addFirst(n.children[i])
        }
        return out
    }

}

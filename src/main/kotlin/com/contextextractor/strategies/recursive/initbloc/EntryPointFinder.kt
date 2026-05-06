package com.contextextractor.strategies.recursive.initbloc

import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.model.init.InitPath
import com.contextextractor.core.model.init.InitPathKind
import com.contextextractor.core.model.init.MethodKey
import com.contextextractor.core.model.init.POST_CONSTRUCT_FQNS
import java.util.ArrayDeque

// BFS sur le graphe d'appels INVERSE — STRATEGIE.md §4.4 (`trouverPointEntreePublic`).
//
// Donnée une `assigningMethod` (méthode qui écrit le champ recherché), remonte
// la chaîne de ses appelants jusqu'à trouver une méthode publiquement
// invocable depuis un test. Un point d'entrée peut être :
//   • la méthode assignatrice elle-même (depth=0) si elle est déjà publique
//   • un ctor (kind=IMPLICIT_VIA_CONSTRUCTOR)
//   • une méthode @PostConstruct (kind=PUBLIC_POST_CONSTRUCT)
//   • toute autre méthode publique (kind=PUBLIC_TRANSITIF)
//
// Plusieurs candidats sont collectés ; le retour est celui de score MINIMAL
// (cf EntryPointScorer). En cas d'égalité, l'ordre BFS départage.
//
// **Garantie d'agrégation chaîne** : `appelsExternesAStubber` et `effetsDeBord`
// agrègent sur la TOTALITÉ de la chaîne `[seed, …, entryPoint]` — pas seulement
// sur le point d'entrée. Cohérent avec la spec ; le coût est `O(chain.size *
// listMethodCalls + listFieldAssignments)` par candidat.
//
// **Pas de cas spécial pour le seed** : le BFS dépile le seed (depth=0),
// teste son accessibilité, génère le candidat si applicable, PUIS enfile ses
// appelants. Si la méthode assignatrice est déjà publique, un candidat
// depth=0 est produit immédiatement (test verrou correspondant).
class EntryPointFinder(
    private val introspector: CodeIntrospector,
    private val hierarchyFqns: Set<String>,
    private val maxGraphDepth: Int,
    // §4.4 — `acceptePackageTest` : autorise les méthodes protected/package
    // comme entrées si le test est dans le même package. V1 default false :
    // on n'émet pas de tests dans le package interne par défaut.
    private val acceptsPackageTest: Boolean = false
) {

    // Index `MethodKey → MethodSignature` construit une fois pour la durée
    // de l'instance. Évite de re-itérer toute la hiérarchie à chaque BFS.
    private val signatureLookup: Map<MethodKey, MethodSignature> = buildSignatureLookup()

    fun find(
        fieldName: String,
        assigningMethod: MethodKey,
        targetMethod: MethodKey,
        callGraph: Map<MethodKey, Set<MethodKey>>
    ): InitPath? {
        val candidates = mutableListOf<InitPath>()
        val visited = HashSet<MethodKey>()
        val queue: ArrayDeque<BfsNode> = ArrayDeque()
        queue.add(BfsNode(assigningMethod, listOf(assigningMethod), 0))

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (current.depth > maxGraphDepth) break
            if (!visited.add(current.node)) continue

            val sig = signatureLookup[current.node]
            // Si l'introspector n'a pas la méthode (ex. seed pointant vers une
            // méthode hors hiérarchie), on n'émet pas de candidat — mais on
            // CONTINUE quand même à enfiler les appelants connus du graphe,
            // ils peuvent eux être résolvables.
            if (sig != null && current.node != targetMethod) {
                buildCandidate(fieldName, sig, current)?.let(candidates::add)
            }

            for (caller in callGraph[current.node].orEmpty()) {
                if (caller in visited) continue
                if (current.depth + 1 > maxGraphDepth) continue
                queue.add(BfsNode(caller, current.chain + caller, current.depth + 1))
            }
        }

        return candidates.minByOrNull { it.score }
    }

    private fun buildCandidate(fieldName: String, sig: MethodSignature, current: BfsNode): InitPath? {
        val accessible = isAccessible(sig)
        if (!accessible) return null

        // Branche ctor (§4.4) : score figé à 0, pas d'agrégation chaîne.
        if (sig.name == "<init>") {
            return InitPath(
                kind = InitPathKind.IMPLICIT_VIA_CONSTRUCTOR,
                entryPoint = current.node,
                chain = current.chain,
                depth = current.depth,
                parametersRequired = emptyList(),
                externalCallsToStub = emptyList(),
                sideEffects = emptySet(),
                score = 0
            )
        }

        val isPostConstruct = sig.annotations.any { it in POST_CONSTRUCT_FQNS }
        val kind = if (isPostConstruct) InitPathKind.PUBLIC_POST_CONSTRUCT
                   else InitPathKind.PUBLIC_TRANSITIF

        val externals = collectExternalCalls(current.chain)
        val sideEffects = collectSideEffects(current.chain, fieldName)
        val returnsVoid = sig.returnType.fqName == "void"

        val score = EntryPointScorer.score(
            depth = current.depth,
            parametersRequired = sig.parameters.size,
            externalCalls = externals.size,
            sideEffects = sideEffects.size,
            returnsVoid = returnsVoid,
            kind = kind
        )

        return InitPath(
            kind = kind,
            entryPoint = current.node,
            chain = current.chain,
            depth = current.depth,
            parametersRequired = sig.parameters,
            externalCallsToStub = externals,
            sideEffects = sideEffects,
            score = score
        )
    }

    private fun isAccessible(sig: MethodSignature): Boolean = when (sig.visibility) {
        "public" -> true
        "protected", "package-private" -> acceptsPackageTest
        else -> false
    }

    // §4.4 collecterAppelsExternes : agrégation sur la chaîne entière. Filtre :
    // hors hiérarchie ET non statique (les statics utilitaires ne sont pas
    // « stubbables » via Mockito sans MockedStatic ; on les laisse hors compteur).
    private fun collectExternalCalls(chain: List<MethodKey>): List<MethodCall> {
        val out = mutableListOf<MethodCall>()
        for (link in chain) {
            val sig = signatureLookup[link] ?: continue
            for (call in introspector.listMethodCalls(sig)) {
                if (call.isStatic) continue
                if (call.targetType in hierarchyFqns) continue
                out.add(call)
            }
        }
        return out
    }

    // §4.4 collecterChampsAssignes(chain) - {champ.nom} : noms uniques des
    // champs assignés sur la chaîne, hors le champ cible.
    private fun collectSideEffects(chain: List<MethodKey>, fieldName: String): Set<String> {
        val names = LinkedHashSet<String>()
        for (link in chain) {
            val sig = signatureLookup[link] ?: continue
            for (a in introspector.listFieldAssignments(sig)) {
                names.add(a.fieldName)
            }
        }
        names.remove(fieldName)
        return names
    }

    private fun buildSignatureLookup(): Map<MethodKey, MethodSignature> {
        val lookup = HashMap<MethodKey, MethodSignature>()
        for (classFqn in hierarchyFqns) {
            val cls = introspector.resolveClass(classFqn) ?: continue
            for (m in introspector.listMethods(cls)) {
                lookup[MethodKey.of(classFqn, m)] = m
            }
        }
        return lookup
    }

    private data class BfsNode(
        val node: MethodKey,
        val chain: List<MethodKey>,
        val depth: Int
    )
}

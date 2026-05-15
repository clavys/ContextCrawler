package com.contextextractor.strategies.recursive.initbloc

import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.model.init.MethodKey

// Construit le graphe d'appels INVERSE intra-SUT — STRATEGIE.md §4.3.
//
// ⚠ CONVENTION INVERSE — à lire absolument avant de toucher cette classe.
//
//     graph[X] = méthodes qui APPELLENT X
//                (et NON les méthodes que X appelle)
//
// C'est le sens dont a besoin le BFS de §4.4 : on part d'une méthode privée
// `methodeAssignatrice` et on remonte la chaîne de ses appelants jusqu'à
// trouver un point d'entrée public. Construire le graphe dans le sens direct
// (X → ses appelés) obligerait à parcourir TOUT le graphe à chaque champ.
//
// **Format des clés** : voir [MethodKey]. `canonical` = MethodSignature.canonical().
// Identique au format de VisitKey.methodCanonical (mode INTERNAL_LOGIC) — c'est
// indispensable pour que EntryPointFinder (4e-γ) puisse lookup sans aliasing.
//
// **Constructeurs** : ils sont des sommets normaux, à la fois comme appelants
// (un ctor qui appelle `init()` privée alimente `graph[init].add(<init>)`) et
// comme appelés (rare en pratique sauf delegation `this(...)`). L'adapter PSI
// normalise leur `name` en "<init>" — donc leur `canonical` ressemble à
// "<init>(java.lang.String)".
//
// **Limites V1 connues** :
//   • Les blocs initialiseurs d'instance (`{ … }` non statiques) ne sont pas
//     visités — port `listInitializerBlocks` non ajouté (réserve C1 de 4e-α).
//     Aucun cas test 91-95 n'en contient.
//   • Les `super(...)` et `this(...)` constructor delegation sont considérés
//     comme des appels normaux par PSI — donc capturés ici si la cible est
//     dans la hiérarchie. Cohérent avec §4.3.
//   • Les lambdas et method references ne sont pas explicitement résolus :
//     PSI peut les voir comme `PsiMethodCallExpression` ou non selon les cas ;
//     V1 accepte cette zone grise.
class CallGraphBuilder(private val introspector: CodeIntrospector) {

    // Construit le graphe inverse pour la hiérarchie complète d'un SUT.
    // [hierarchyFqns] = SUT + chaîne de super-classes utilisateur (sans Object).
    // Sert à la fois pour énumérer les appelants potentiels (méthodes/ctors de
    // chaque classe) et pour filtrer « call.targetType ∈ hierarchie ».
    fun build(hierarchyFqns: Set<String>): Map<MethodKey, Set<MethodKey>> {
        val graph = LinkedHashMap<MethodKey, MutableSet<MethodKey>>()

        for (callerClassFqn in hierarchyFqns) {
            val callerClass = introspector.resolveClass(callerClassFqn) ?: continue
            val callerMethods = introspector.listMethods(callerClass)
            for (caller in callerMethods) {
                val callerKey = MethodKey.of(callerClassFqn, caller)
                val calls = introspector.listMethodCalls(caller)
                for (call in calls) {
                    // Filtre intra-SUT — §4.3 : `cible.containingClass ∈ hiérarchie`.
                    // L'adapter PSI renvoie déjà `targetType = resolved.containingClass.qualifiedName`,
                    // donc l'appel hérité (super.foo()) est bien attribué à la
                    // super-classe propriétaire de la méthode résolue, pas au receveur.
                    if (call.targetType !in hierarchyFqns) continue

                    val calleeClass = introspector.resolveClass(call.targetType) ?: continue
                    val callee = findCalleeMethod(calleeClass, call.methodName, call.argTypes)
                        ?: continue
                    val calleeKey = MethodKey.of(call.targetType, callee)

                    graph.getOrPut(calleeKey) { LinkedHashSet() }.add(callerKey)
                }
            }
        }
        return graph
    }

    // Match strict sur (nom, signature des params en FQN) ; fallback premier
    // match si aucun overload exact (cohérent avec RecursiveDeepStrategy.findMethodIn).
    private fun findCalleeMethod(
        calleeClass: com.contextextractor.core.extractor.ClassDescriptor,
        methodName: String,
        argTypeFqns: List<String>
    ): MethodSignature? {
        val candidates = introspector.listMethods(calleeClass).filter { it.name == methodName }
        if (candidates.isEmpty()) return null
        val exact = candidates.firstOrNull { sig ->
            sig.parameters.map { it.type.fqName } == argTypeFqns
        }
        return exact ?: candidates.first()
    }
}

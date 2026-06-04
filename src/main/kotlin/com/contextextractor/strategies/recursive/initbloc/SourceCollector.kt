package com.contextextractor.strategies.recursive.initbloc

import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.model.SelectedConstructor
import com.contextextractor.core.model.init.InitSource
import com.contextextractor.core.model.init.MethodInitKind
import com.contextextractor.core.model.init.POST_CONSTRUCT_FQNS

// Collecte toutes les sources d'initialisation possibles d'un champ donné —
// STRATEGIE.md §4.1 + §4.2.
//
// Le retour est une liste BRUTE — pas de scoring, pas de filtrage par
// pertinence. C'est StrategySelector (4e-ε) qui appliquera l'arbre de décision
// à 11 branches sur cette liste.
//
// Hypothèses V1 (documentées dans le code et verrouillées par les tests) :
//   • Setter compatibility = FQN strictement identique. Pas d'assignabilité par
//     héritage (le port ne le supporte pas en V1).
//   • Constructor compatibility = nom du paramètre identique au nom du champ
//     ET FQN du type identique. Idem strict.
//   • InitializerBlock non collecté (réserve C1).
//   • Le ctor sélectionné est exclu de la branche MethodInitializer (§4.2 :
//     `Si Methode == ConstructeurChoisi → continuer`). Les autres ctors le
//     sont aussi en V1 — ils ne sont pas appelables depuis le test puisqu'on
//     a déjà fixé le ctor à utiliser via BLOC 4.
class SourceCollector(
    private val introspector: CodeIntrospector,
    private val hierarchyFqns: Set<String>
) {

    fun collect(
        field: ClassField,
        selectedConstructor: SelectedConstructor,
        targetMethod: MethodSignature? = null
    ): List<InitSource> {
        val sources = mutableListOf<InitSource>()

        // 1) FieldInitializer — `private final Logger log = LoggerFactory.…;`
        field.initializerExpression?.let { expr ->
            sources += InitSource.FieldInitializer(expression = expr, initType = field.type)
        }

        // 2) Constructor — paramètre du ctor sélectionné qui matche nom + FQN.
        selectedConstructor.parameters
            .firstOrNull { it.name == field.name && it.type.fqName == field.type.fqName }
            ?.let { sources += InitSource.Constructor(parameterName = it.name) }

        // 3) Setter / MethodInitializer — parcours hiérarchie.
        // Bug CC — exclure targetMethod des MethodInitializer candidates. Sinon
        // un champ écrit (mais jamais lu) par target — typiquement un OUTPUT du
        // target (`this.total = ...`, `this.rowCount = ...`) — verrait target
        // élu en branche 6 comme `CALL_PUBLIC_WITH_ARGS`. Le LLM appellerait
        // alors target dans `@BeforeEach`, avec toutes ses dépendances mockées,
        // ce qui produit une cascade fragile : appel target → besoin de stubber
        // `getStructurePage()` sur un @InjectMocks (pas un spy) → NotAMockException.
        // En excluant target, ces fields tombent en branche 10 (auto-init), qui
        // retourne `IMPLICIT` → le renderer les ignore proprement.
        val targetCanonical = targetMethod?.canonical()
        for (classFqn in hierarchyFqns) {
            val cls = introspector.resolveClass(classFqn) ?: continue
            for (m in introspector.listMethods(cls)) {
                if (m.name == "<init>") continue // §4.2 + V1 simplification
                trySetter(field, m)?.let { sources += it }
                if (targetCanonical != null && m.canonical() == targetCanonical) continue
                tryMethodInitializer(field, m)?.let { sources += it }
            }
        }
        return sources
    }

    // §4.1 SETTER — `setX(T)` où la conversion `setX → x` matche le nom du
    // champ ET param.type.fqName == champ.type.fqName. Public uniquement (V1).
    private fun trySetter(field: ClassField, m: MethodSignature): InitSource.Setter? {
        if (m.visibility != "public") return null
        if (!m.name.startsWith("set") || m.name.length <= 3) return null
        if (m.parameters.size != 1) return null
        val derivedFieldName = decapitalize(m.name.removePrefix("set"))
        if (derivedFieldName != field.name) return null
        val param = m.parameters.single()
        if (param.type.fqName != field.type.fqName) return null
        return InitSource.Setter(methodName = m.name, parameterType = param.type)
    }

    // §4.2 — méthode qui assigne `this.champ = …`. On utilise listFieldAssignments
    // (port ajouté en 4e-α) qui garantit que toute assignation, même conditionnelle,
    // est listée avec son contexte enrichi.
    private fun tryMethodInitializer(field: ClassField, m: MethodSignature): InitSource.MethodInitializer? {
        val allAssignments = introspector.listFieldAssignments(m)
        // L'assignment doit cibler le champ ET son ownerType doit être dans la
        // hiérarchie SUT (sinon c'est un champ d'une autre classe).
        val matching = allAssignments.filter {
            it.fieldName == field.name && it.ownerType in hierarchyFqns
        }
        if (matching.isEmpty()) return null

        // §4.2 conditionEstNullCheck — true s'il existe AU MOINS UNE assignation
        // au champ qui est conditionnée par un null-check sur ce champ.
        val hasNullGuard = matching.any { it.isConditional && it.conditionIsNullCheck }

        val kind = if (m.annotations.any { it in POST_CONSTRUCT_FQNS })
            MethodInitKind.POST_CONSTRUCT else MethodInitKind.ORDINARY

        // §4.2 detecteAppelsHorsSUT : appels vers classes hors hiérarchie SUT.
        // Filtre statiques (cohérent avec EntryPointFinder.collectExternalCalls).
        val externalCalls = introspector.listMethodCalls(m).filter {
            !it.isStatic && it.targetType !in hierarchyFqns
        }

        // §4.2 detecteAutresAssignations : noms uniques des AUTRES champs
        // assignés par cette méthode. Important : utilise allAssignments (vue
        // complète de la méthode), pas matching — c'est une requête cross-champ.
        val assignsAlso = allAssignments
            .map { it.fieldName }
            .filter { it != field.name }
            .distinct()

        return InitSource.MethodInitializer(
            kind = kind,
            method = m,
            visibility = m.visibility,
            parametersRequired = m.parameters,
            hasNullGuard = hasNullGuard,
            externalCalls = externalCalls,
            assignsAlso = assignsAlso
        )
    }

    private fun decapitalize(s: String): String =
        if (s.isEmpty()) s else s[0].lowercaseChar() + s.substring(1)
}

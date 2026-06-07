package com.contextextractor.core.classifier

import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.model.refs.ClassReference

// Implémentation V1.2 du `ContextAwareClassifier`.
//
// **Stratégie** : règles en priorité décroissante, le premier `return` gagne.
// Chaque règle consulte les `UsageSite` agrégés de la `ClassReference` plutôt
// qu'un contexte d'appel ponctuel. Ça permet de prendre la décision FINALE
// en une passe — sans cascade de promotions / filtres / réconciliations.
//
// **Mapping V1.1 → V1.2 (élimination des patches structurels)** :
//
//   Bug U (promotion DATA→MOCK pour modele appelé)
//     → Règle 6 (isCalledAsInstance → MOCK) déclenche AVANT la règle DTO.
//
//   Bug B (filter DTOs non transitivement atteints)
//     → Le builder ne registe pas ces DTOs. Si un DTO entre dans le graph,
//        il est légitime. Pas de filtre rétroactif.
//
//   Bug I (essential mock types pré-amorcés)
//     → `isOnTargetSurface` + `isStubReturnType` sont des UsageSite first-class.
//        Pas d'amorçage hors-classifier nécessaire.
//
//   Bug S (filteredFields garde-fou essential)
//     → Pas de filteredFields ici. Tout ce qui est dans le graph est gardé.
//
//   Bug T (scan textuel body pour fields)
//     → Le builder en Phase 1 utilise le même scan (héritage tactique).
//        Mais le résultat alimente naturellement `AsFieldOfSut`.
//
// **Ordre des règles V1.2 — révisé Astrea R1 (Bug #A + R3-2)** :
//   1. Système (java.*, primitifs)              → SYSTEM_IGNORE
//   2. Framework (préfixe configuré)             → SYSTEM_IGNORE
//   3. Container (Optional, Future, Mono...)     → CONTAINER
//   4. Collection (List, Map, Set...)            → COLLECTION
//   5. SAM java.util.function.*                  → FUNCTIONAL_LAMBDA
//   6. Hiérarchie SUT                            → INTERNAL_LOGIC (root = SUT_BOOTSTRAP géré par caller)
//   7. Appelé comme STATIC uniquement            → STATIC_UTILITY
//   7bis. Instancié dans le body (new Type)      → DATA_STRUCTURE (force)
//        ← déplacé AVANT R8 pour fix Astrea R3-2 (case 4.3 ReferenceModele) :
//        un type créé via `new X()` dans target body ne PEUT PAS être mocké,
//        même s'il porte un setter appelé après — l'instance utilisée est
//        celle locale, pas le mock injecté.
//   8. Appelé comme INSTANCE (mutator OR SUT field) → MOCK_EXTERNAL
//        ← extension `|| ref.isSutField` pour fix Astrea R1-1/R2-1 :
//        un field injecté DOIT être mockable même si on n'en lit que des
//        pur-getters (sinon NPE runtime du test).
//   9. ENUM / SEALED / RECORD                    → DATA_STRUCTURE
//   10. Lombok @Data/@Value/@Builder             → DATA_STRUCTURE
//   11. (vide — ancien isInstantiatedInBody, déplacé en R7bis)
//   11bis. Stub return EXCLUSIF (pas d'autre usage) → MOCK_EXTERNAL
//        ← fix Astrea R3-1 (case 4.3 PageDataDTO) : un type utilisé seulement
//        comme return value d'un stub mock doit être mockable (le LLM ne
//        peut pas `new` un `[CONSTRUCTOR]` sans connaître ses args).
//        Position : après R9/R10 (ENUM/Lombok restent prioritaires) mais
//        avant R12-R14 (PageDataDTO POJO concret tomberait sinon dans R14
//        all-trivial-methods → DATA_STRUCTURE).
//   12. Interface / abstract                     → MOCK_EXTERNAL
//   13. Spring @Service/@Repository/etc.         → MOCK_EXTERNAL
//   14. Toutes méthodes triviales (rule 10 V1.1) → DATA_STRUCTURE
//   15. Sur la surface de target (param/return)  → MOCK_EXTERNAL (par sécurité)
//   16. Default                                  → DATA_STRUCTURE (changement V1.2 !
//                                                    V1.1 = MOCK_EXTERNAL par défaut)
//
// **Changement de défaut** : V1.1 défautait à MOCK_EXTERNAL (sécuritaire pour
// éviter d'oublier de mocker). V1.2 défaute à DATA_STRUCTURE : si une classe
// n'est ni appelée comme instance, ni Spring, ni interface, ni Lombok, ni
// instanciée, c'est probablement une simple data class. Le risque "MOCK
// inutile" disparaît.
class DefaultContextAwareClassifier : ContextAwareClassifier {

    override fun classify(
        ref: ClassReference,
        hierarchyFqns: Set<String>,
        frameworkPrefixes: List<String>,
        descriptorMethods: List<MethodSignature>
    ): ExtractionMode {

        val fqn = ref.fqn
        val descriptor = ref.descriptor

        // Règle 1 — système (java.*, primitifs).
        if (isSystemType(fqn)) return ExtractionMode.SYSTEM_IGNORE

        // Règle 2 — framework configuré (javax.faces, org.primefaces...).
        if (frameworkPrefixes.any { fqn.startsWith(it) }) return ExtractionMode.SYSTEM_IGNORE

        // Règle 3 — containers async/optional.
        if (fqn in CONTAINER_FQNS) return ExtractionMode.CONTAINER

        // Règle 4 — collections.
        if (fqn in COLLECTION_FQNS) return ExtractionMode.COLLECTION

        // Règle 5 — SAM standards (java.util.function.*).
        if (fqn.startsWith("java.util.function.")) return ExtractionMode.FUNCTIONAL_LAMBDA

        // Règle 6 — hiérarchie SUT. Le caller distingue le root_SUT (= SUT_BOOTSTRAP)
        // des autres niveaux ; ici on retourne INTERNAL_LOGIC qui signifie
        // « ne pas matérialiser comme mock/DTO — méthodes visitées via BLOC 6/§3.2 ».
        if (fqn in hierarchyFqns) return ExtractionMode.INTERNAL_LOGIC

        // Règle 7 — STATIC_UTILITY (uniquement static, jamais d'instance).
        if (ref.isCalledAsStatic && !ref.isCalledAsInstance) {
            return ExtractionMode.STATIC_UTILITY
        }

        // Règle 7bis — `new Type()` détecté dans le body → DTO forcé.
        //
        // Astrea R3-2 (case 4.3) : `new ReferenceModele() + setReference()` ;
        // R8 V1.1 promouvait en MOCK (setter = mutator) alors que l'instance
        // utilisée par le code de prod est celle locale, jamais le mock.
        // L'instanciation est un signal STRUCTUREL plus fort qu'un call ;
        // elle doit primer sur les règles d'usage.
        //
        // Aussi prioritaire sur "interface" (anciennement R12) : un
        // `new SomeIface() {...}` anonyme ne peut PAS devenir @Mock — le
        // code de prod construit l'instance.
        if (ref.isInstantiatedInBody) return ExtractionMode.DATA_STRUCTURE

        // Règle 8 — appel d'instance MUTATEUR/BUSINESS, ou field of SUT → MOCK_EXTERNAL.
        //
        // Affinage post-tests V1.2 : déclencher MOCK si au moins un
        // call site est :
        //   • un appel avec arguments (typiquement setter `setX(value)`,
        //     méthode business `doSomething(arg)`), OU
        //   • une méthode dont le nom n'est PAS un pur accesseur, OU
        //   • le type est un FIELD INJECTÉ DE LA SUT (Astrea R1-1/R2-1 :
        //     un champ @Autowired/@Inject DOIT être mockable même si on
        //     n'en lit que des getters purs, sinon NPE runtime du test).
        //
        // Si TOUS les appels sont des pur getters ET le type n'est pas
        // un field of SUT, la sémantique est « lecture pure de données » →
        // DTO préférable : le test construit une instance avec les valeurs
        // voulues et le SUT y accède naturellement. Cas concret case00
        // (OrderEntity.getId() retourné par repo) — non-régression validée
        // par AstreaRoundR1RegressionTest.
        //
        // Bug U préservé : `modele.setX(true)` a 1 arg → MOCK. ✓
        // Bug DD préservé : les services concernés sont interfaces ou Spring
        // → rule 12/13 les attrape même si rule 8 ne fire pas.
        if (ref.isCalledAsInstance && (hasMutatorOrBusinessCall(ref) || ref.isSutField)) {
            return ExtractionMode.MOCK_EXTERNAL
        }

        // Règles 9-16 — patterns structurels.
        // ORDRE V1.2 (révisé post-tests) : on regarde d'abord les patterns DTO
        // explicites (Enum/Sealed/Record/Lombok), puis l'instantiation forcée,
        // PUIS les indicateurs MOCK structurels (interface, Spring), PUIS la
        // règle « all-trivial » (cas POJO/entity).
        //
        // Justification de l'ordre interface AVANT all-trivial : une interface
        // dont toutes les méthodes sont des getters (ex : TableauModele avec
        // `getCriteresRecherche()`) est par définition un contrat à mocker, pas
        // un DTO concret. Le test BB de la prod le valide.
        if (descriptor != null) {
            // Règle 9 — ENUM / SEALED / RECORD natifs.
            if (descriptor.isEnum || descriptor.isSealed || descriptor.isRecord) {
                return ExtractionMode.DATA_STRUCTURE
            }
            // Règle 10 — Lombok value classes.
            if (descriptor.annotations.any { it in LOMBOK_DATA_ANNOTATIONS }) {
                return ExtractionMode.DATA_STRUCTURE
            }
        }

        // Règle 11 — vide (anciennement `isInstantiatedInBody`, déplacé en R7bis
        // pour fix Astrea R3-2). Conservé comme repère numérique.

        // Règle 11bis — stub return EXCLUSIF + AUCUN ctor public no-arg → MOCK_EXTERNAL.
        //
        // Astrea R3-1 (case 4.3) : `PageDataDTO` est le retour de
        // `sessionAstreaModele.getPageData(NavigationEnum)`. Son seul usage agrégé
        // est `AsStubReturn` — il n'est ni field SUT, ni instancié, ni appelé,
        // ni sur la surface du target. ET il N'A PAS de ctor public no-arg.
        //
        // V1.1/R1 le classait DATA_STRUCTURE via R14 (toutes méthodes triviales)
        // → le LLM tentait `new PageDataDTO()` pour produire la return-value
        // du stub → no-arg ctor inexistant → ne compile pas.
        //
        // V1.2/R2-B : on classe MOCK_EXTERNAL. Le LLM déclare `@Mock PageDataDTO`
        // et l'utilise dans `when(...).thenReturn(pageDataDTO)`. Le mock est
        // valide pour tout type concret (même sans ctor public no-arg).
        //
        // Position après R9 (ENUM) et R10 (Lombok) — ces patterns DTO restent
        // prioritaires.
        //
        // **Distinction avec Bug DD natif** : un POJO avec ctor no-arg
        // (visible dans `descriptorMethods`) RESTE DATA_STRUCTURE — le LLM peut
        // construire via `new Foo()` ou via Lombok `Foo.builder()`. La règle
        // ne fire QUE quand on a la preuve qu'aucun no-arg ctor n'existe :
        // `descriptorMethods` est non-vide ET ne contient pas `<init>()` no-arg.
        // Cas `descriptorMethods` vide (pas d'info) → fall-through pour
        // préserver le comportement legacy (test "Bug DD natif").
        //
        // Garde-fou : NE PAS fire si déjà géré (instancié dans body, surface
        // target, field SUT, appelé). Ces cas ont une sémantique plus forte.
        if (ref.isStubReturnType &&
            !ref.isInstantiatedInBody &&
            !ref.isSutField &&
            !ref.isCalledAsInstance &&
            !ref.isOnTargetSurface &&
            descriptorMethods.isNotEmpty() &&
            descriptorMethods.none { it.name == "<init>" && it.parameters.isEmpty() }) {
            return ExtractionMode.MOCK_EXTERNAL
        }

        if (descriptor != null) {
            // Règle 12 — interface ou abstract → MOCK_EXTERNAL.
            // Déplacée AVANT rule "all trivial" : une interface getter-only
            // (ex : TableauModele#getCriteresRecherche) reste un contrat
            // mockable, pas un DTO.
            if (descriptor.isInterface || descriptor.isAbstract) {
                return ExtractionMode.MOCK_EXTERNAL
            }
            // Règle 13 — Spring service annotations.
            // Avant rule "all trivial" : un @Service avec uniquement des
            // getters reste un service à mocker (cas rare mais correct).
            if (descriptor.annotations.any { it in SPRING_SERVICE_ANNOTATIONS }) {
                return ExtractionMode.MOCK_EXTERNAL
            }
            // Règle 14 — Toutes méthodes triviales (V1.1 rule 10 — préservé).
            // S'applique aux POJO / @Entity / record-like concrets : OrderEntity
            // (case00), OrderRequest (case92).
            if (descriptorMethods.isNotEmpty() &&
                descriptorMethods.all { isAccessorOrTrivial(it) }) {
                return ExtractionMode.DATA_STRUCTURE
            }
        }

        // Règle 15 — Sur la surface de target (param/return) sans déclencheur
        // autre + descriptor introuvable → MOCK par sécurité.
        if (descriptor == null && ref.isOnTargetSurface) {
            return ExtractionMode.MOCK_EXTERNAL
        }

        // Règle 16 — Default DTO. V1.2 inverse le défaut V1.1.
        return ExtractionMode.DATA_STRUCTURE
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    // Refinement V1.2 rule 8 — vrai dès qu'au moins une signature appelée
    // est un mutateur (a des args) ou une méthode business (ne commence pas
    // par get/is/equals/hashCode/toString). Sinon, tous les appels sont des
    // getters purs → on laisse fall-through pour permettre le classement DTO
    // (cas concret : entity.getId() lu par target → on construit l'entity).
    private fun hasMutatorOrBusinessCall(ref: ClassReference): Boolean {
        return ref.instanceCallSites.any { site ->
            val name = site.call.methodName
            val hasArgs = site.call.argTypes.isNotEmpty()
            val isPureGetter = !hasArgs && (
                name.startsWith("get") ||
                    name.startsWith("is") ||
                    name == "equals" || name == "hashCode" || name == "toString"
                )
            !isPureGetter
        }
    }

    private fun isSystemType(fqn: String): Boolean {
        if (fqn in PRIMITIVES_AND_VOID) return true
        // Exemption : containers (Optional, CompletableFuture…), collections,
        // SAM — leur sémantique est traitée par les règles 3/4/5.
        if (fqn.startsWith("java.util.function.")) return false
        if (fqn in CONTAINER_FQNS || fqn in COLLECTION_FQNS) return false
        return SYSTEM_PACKAGE_PREFIXES.any { fqn.startsWith(it) }
    }

    // Méthode triviale = accesseur JavaBean, equals/hashCode/toString,
    // constructeur, ou factory statique conventionnelle. Préservé verbatim
    // de V1.1 DefaultClassifier pour parité comportementale.
    private fun isAccessorOrTrivial(method: MethodSignature): Boolean {
        val name = method.name
        if (name == "<init>") return true
        if (name == "equals" || name == "hashCode" || name == "toString") return true
        if (name.startsWith("get") && method.parameters.isEmpty()) return true
        if (name.startsWith("is") && method.parameters.isEmpty()) return true
        if (name.startsWith("set") && method.parameters.size == 1) return true
        if (method.isStatic && name in CONSTRUCTION_HELPER_NAMES) return true
        return false
    }

    companion object {
        private val SYSTEM_PACKAGE_PREFIXES = listOf(
            "java.", "javax.", "jakarta.", "kotlin.", "scala.", "sun.", "com.sun."
        )

        private val PRIMITIVES_AND_VOID = setOf(
            "int", "long", "short", "byte", "float", "double", "boolean", "char",
            "void", "Void", "Object"
        )

        private val CONTAINER_FQNS = setOf(
            "java.util.Optional",
            "java.util.concurrent.CompletableFuture",
            "reactor.core.publisher.Mono",
            "reactor.core.publisher.Flux",
            "io.reactivex.Single",
            "io.reactivex.Observable",
            "io.reactivex.rxjava3.core.Single",
            "io.reactivex.rxjava3.core.Observable"
        )

        private val COLLECTION_FQNS = setOf(
            "java.util.List",
            "java.util.Set",
            "java.util.Map",
            "java.util.Collection",
            "java.lang.Iterable",
            "java.util.Queue",
            "java.util.Deque"
        )

        private val SPRING_SERVICE_ANNOTATIONS = setOf(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.cloud.openfeign.FeignClient"
        )

        private val LOMBOK_DATA_ANNOTATIONS = setOf(
            "lombok.Data",
            "lombok.Value",
            "lombok.Builder"
        )

        private val CONSTRUCTION_HELPER_NAMES = setOf(
            "builder", "of", "from", "valueOf"
        )
    }
}

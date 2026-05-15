package com.contextextractor.core.classifier

import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.ResolvedType

// Implémentation des règles 1 → 12 de STRATEGIE.md §2.1.
//
// L'ordre des règles est SIGNIFICATIF — le premier `return` gagne. Toute
// modification doit conserver cet ordre, sinon la sémantique change.
//
// Règle 7 (STATIC_UTILITY) est volontairement absente : la détection requiert
// d'inspecter la visibilité du constructeur et la liste complète des méthodes,
// or la stratégie capture déjà les appels statiques utilisateur dans BLOC 6c-bis
// (resultat.staticCalls), avant que classify() ne soit appelé. Si un type
// 100% static apparaît malgré tout en classify(), il tombera dans le défaut
// sécuritaire (rule 12 → MOCK_EXTERNAL) — ce qui produit un mock inutile mais
// pas de régression fonctionnelle.
class DefaultClassifier : ClassClassifier {

    override fun classify(
        type: ResolvedType,
        descriptor: ClassDescriptor?,
        context: CallerContext,
        sutHierarchyFqns: Set<String>,
        descriptorMethods: List<MethodSignature>
    ): ExtractionMode {

        // Règle 1 — cas d'arrêt système (packages JDK / Kotlin / primitifs / Object).
        if (isSystemType(type)) return ExtractionMode.SYSTEM_IGNORE

        // Règle 2 — la racine SUT court-circuite tout le reste.
        if (context == CallerContext.ROOT_SUT) return ExtractionMode.SUT_BOOTSTRAP

        // Règle 3 — SAM dans java.util.function.* (Function, Consumer, Supplier, …).
        if (type.fqName.startsWith("java.util.function.")) return ExtractionMode.FUNCTIONAL_LAMBDA

        // Règle 4 — conteneurs async / optional.
        if (type.isContainer || type.fqName in CONTAINER_FQNS) return ExtractionMode.CONTAINER

        // Règle 5 — collections utilisateur.
        if (type.isCollection || type.fqName in COLLECTION_FQNS) return ExtractionMode.COLLECTION

        // À partir d'ici, on a besoin du descripteur pour les règles structurelles.
        if (descriptor != null) {

            // Règle 6 — enum ou sealed.
            if (descriptor.isEnum || descriptor.isSealed) return ExtractionMode.DATA_STRUCTURE

            // (Règle 7 — STATIC_UTILITY — voir commentaire de classe.)

            // Règle 8 — interface ou classe abstraite (hors SAM déjà capté en règle 3).
            if (descriptor.isInterface || descriptor.isAbstract) return ExtractionMode.MOCK_EXTERNAL

            // Règle 9 — annotations Spring de service (sauf SUT racine déjà sorti en règle 2).
            if (descriptor.annotations.any { it in SPRING_SERVICE_ANNOTATIONS }) {
                return ExtractionMode.MOCK_EXTERNAL
            }

            // Règle 10 — DTO / POJO / Record / Lombok value-class / classe sans
            // méthode métier. La 3e branche n'est testable que si la stratégie
            // a fourni la liste des méthodes du descripteur.
            if (descriptor.isRecord ||
                descriptor.annotations.any { it in LOMBOK_DATA_ANNOTATIONS } ||
                (descriptorMethods.isNotEmpty() && descriptorMethods.all { isAccessorOrTrivial(it) })
            ) {
                return ExtractionMode.DATA_STRUCTURE
            }
        }

        // Règle 11 — type intra-hiérarchie SUT appelé en CALL_TARGET → on creuse la logique.
        if (context == CallerContext.CALL_TARGET && type.fqName in sutHierarchyFqns) {
            return ExtractionMode.INTERNAL_LOGIC
        }

        // Règle 12 — défaut sécuritaire.
        return ExtractionMode.MOCK_EXTERNAL
    }

    // « Méthode triviale » = un constructeur, un accesseur (get*, set*, is*), une
    // méthode `equals` / `hashCode` / `toString`, ou une fabrique statique
    // conventionnelle (builder/of/from/valueOf). Si TOUTES les méthodes du type
    // sont triviales, le type est un POJO et on le traite en DATA_STRUCTURE
    // (règle 10, branche « no business method »).
    //
    // Les fabriques statiques sont incluses parce que ce sont des proxies de
    // construction, pas de la logique métier — sans cette exception, une classe
    // type `Page.of(int, int)` chuterait en MOCK_EXTERNAL via rule 12 et la
    // détection du pattern STATIC_FACTORY (§3.4) serait inatteignable.
    private fun isAccessorOrTrivial(method: MethodSignature): Boolean {
        val name = method.name
        if (name == "<init>") return true
        if (name == "equals" || name == "hashCode" || name == "toString") return true
        // Conventions JavaBean strictes : getX(), isX(), setX(value).
        if (name.startsWith("get") && method.parameters.isEmpty()) return true
        if (name.startsWith("is") && method.parameters.isEmpty()) return true
        if (name.startsWith("set") && method.parameters.size == 1) return true
        // Fabriques statiques conventionnelles — voir §3.4.
        if (method.isStatic && name in CONSTRUCTION_HELPER_NAMES) return true
        return false
    }

    private fun isSystemType(type: ResolvedType): Boolean {
        if (type.fqName in PRIMITIVES_AND_VOID) return true
        // Exemption : containers (Optional, CompletableFuture…), collections (List, Map…)
        // et SAM java.util.function.* vivent dans `java.*` mais sont explicitement traités
        // par les règles 3, 4 et 5. Sans cette exemption, java.util.Optional serait
        // SYSTEM_IGNORE et on perdrait la récursion sur ses type-args. Spec §2.1 :
        // les règles 3, 4, 5 décrivent leur propre sémantique, donc l'« arrêt système »
        // de la règle 1 ne s'applique pas à elles.
        if (type.fqName.startsWith("java.util.function.")) return false
        if (type.fqName in CONTAINER_FQNS || type.fqName in COLLECTION_FQNS) return false
        if (type.isContainer || type.isCollection) return false
        return SYSTEM_PACKAGE_PREFIXES.any { type.fqName.startsWith(it) }
    }

    companion object {
        private val SYSTEM_PACKAGE_PREFIXES = listOf(
            "java.", "javax.", "jakarta.", "kotlin.", "scala.", "sun.", "com.sun."
        )

        // Primitifs Java + void/Void/Object — règle 1 explicite de §2.1.
        // Note : les wrappers (Integer, Long, …) sont déjà couverts via le préfixe `java.`.
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

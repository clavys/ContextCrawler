package com.contextextractor.core.classifier

import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.ResolvedType

// Mode contextuel — décision prise pendant la récursion. Voir STRATEGIE.md §2.
// Distinct de NodeKind (étiquette stable du nœud final). Voir ARCHITECTURE.md §4.
enum class ExtractionMode {
    SUT_BOOTSTRAP,
    INTERNAL_LOGIC,
    MOCK_EXTERNAL,
    DATA_STRUCTURE,
    SYSTEM_IGNORE,
    FUNCTIONAL_LAMBDA,
    CONTAINER,
    COLLECTION,
    STATIC_UTILITY
}

// Contexte d'appel passé au Classifier — voir STRATEGIE.md §2.1.
enum class CallerContext {
    ROOT_SUT,
    FIELD_OF_SUT,
    PARAM_OF_METHOD,
    RETURN_OF_METHOD,
    FIELD_OF_DTO,
    GENERIC_ARG,
    CALL_TARGET,
    INSTANTIATION
}

// Classifie un type selon son contexte d'appel. Le même type peut être
// MOCK_EXTERNAL à un endroit et DATA_STRUCTURE à un autre.
//
// `descriptorMethods` est utilisé par la règle 10 (DATA_STRUCTURE par
// défaut quand la classe n'expose que des accesseurs). La stratégie passe
// `introspector.listMethods(descriptor)` ; les appels qui n'ont pas accès
// au port peuvent omettre ce paramètre — la règle 10 ne se déclenchera
// alors que sur Record + Lombok.
interface ClassClassifier {
    fun classify(
        type: ResolvedType,
        descriptor: ClassDescriptor?,
        context: CallerContext,
        sutHierarchyFqns: Set<String> = emptySet(),
        descriptorMethods: List<MethodSignature> = emptyList()
    ): ExtractionMode
}

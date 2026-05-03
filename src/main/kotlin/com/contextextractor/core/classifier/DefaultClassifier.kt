package com.contextextractor.core.classifier

import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.ResolvedType

// Stub — l'implémentation complète des règles 1→12 de STRATEGIE.md §2.1
// est livrée à l'étape 4.
class DefaultClassifier : ClassClassifier {
    override fun classify(
        type: ResolvedType,
        descriptor: ClassDescriptor?,
        context: CallerContext
    ): ExtractionMode {
        throw NotImplementedError("Implemented at step 4")
    }
}

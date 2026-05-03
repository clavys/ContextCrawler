package com.contextextractor.core.strategy

import com.contextextractor.core.classifier.ClassClassifier
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.CursorLocation

// Entrée d'une stratégie d'extraction — voir ARCHITECTURE.md §6.
data class StrategyInput(
    val introspector: CodeIntrospector,
    val classifier: ClassClassifier,
    val cursor: CursorLocation,
    val config: StrategyConfig
)

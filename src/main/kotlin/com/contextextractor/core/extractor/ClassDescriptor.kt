package com.contextextractor.core.extractor

// Descripteur de classe — vue côté core/, indépendant de PSI.
data class ClassDescriptor(
    val fqn: String,
    val simpleName: String,
    val superFqn: String?,
    val interfaces: List<String> = emptyList(),
    val isAbstract: Boolean = false,
    val isInterface: Boolean = false,
    val isRecord: Boolean = false,
    val isSealed: Boolean = false,
    val isEnum: Boolean = false,
    val annotations: List<String> = emptyList(),
    val visibility: String = "public",
    val packageName: String = ""
)

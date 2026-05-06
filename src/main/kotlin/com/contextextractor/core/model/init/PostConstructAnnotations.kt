package com.contextextractor.core.model.init

// FQN des annotations équivalentes à @PostConstruct, partagées par tous les
// consommateurs du pipeline (RecursiveDeepStrategy, EntryPointFinder,
// SourceCollector). Une seule source de vérité — ajouter ici si un nouveau
// framework standardise une variante (Spring `@EventListener` n'est PAS
// équivalent et reste hors liste).
val POST_CONSTRUCT_FQNS: Set<String> = setOf(
    "jakarta.annotation.PostConstruct",
    "javax.annotation.PostConstruct"
)

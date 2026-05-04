package com.contextextractor.adapters.psi

import com.contextextractor.core.extractor.ResolvedType
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiWildcardType

// Mappe un PsiType vers ResolvedType. Couvre les wildcards (§8bis.1), les
// type parameters non résolus (§8bis.5) et les conteneurs/collections.
//
// Règle d'or — STRATEGIE.md §7.2 : toujours `getCanonicalText()`, jamais `getText()`.
object PsiTypeMapper {

    private val COLLECTION_FQNS = setOf(
        "java.util.List",
        "java.util.Set",
        "java.util.Collection",
        "java.lang.Iterable",
        "java.util.Queue",
        "java.util.Deque"
    )

    private val CONTAINER_FQNS = setOf(
        "java.util.Map",
        "java.util.Optional",
        "java.util.concurrent.CompletableFuture"
    )

    fun toResolved(type: PsiType): ResolvedType = when (type) {
        is PsiWildcardType -> wildcard()
        is PsiArrayType -> array(type)
        is PsiClassType -> classType(type)
        is PsiPrimitiveType -> primitive(type)
        else -> opaque(type)
    }

    // Wildcard `?` ou `? extends Foo` / `? super Foo` — fallback Object.
    private fun wildcard(): ResolvedType = ResolvedType(
        rawType = "?",
        fqName = "java.lang.Object",
        isWildcard = true
    )

    private fun array(type: PsiArrayType): ResolvedType {
        val component = toResolved(type.componentType)
        return ResolvedType(
            rawType = "${component.rawType}[]",
            fqName = "${component.fqName}[]",
            typeArgs = listOf(component)
        )
    }

    private fun classType(type: PsiClassType): ResolvedType {
        val resolved = type.resolve()
        // Type parameter non résolu (T, E, K, V…) — §8bis.5.
        if (resolved is PsiTypeParameter) {
            val name = resolved.name ?: type.canonicalText
            return ResolvedType(
                rawType = name,
                fqName = name,
                isUnresolvedTypeParameter = true
            )
        }
        val canonical = type.rawType().canonicalText
        val typeArgs = type.parameters.map { toResolved(it) }
        return ResolvedType(
            rawType = canonical.substringAfterLast('.'),
            fqName = canonical,
            typeArgs = typeArgs,
            isCollection = canonical in COLLECTION_FQNS,
            isContainer = canonical in CONTAINER_FQNS
        )
    }

    private fun primitive(type: PsiPrimitiveType): ResolvedType {
        val name = type.canonicalText
        return ResolvedType(rawType = name, fqName = name)
    }

    // Cas d'échec — type non résolvable (§8bis.1) : on garde la trace textuelle
    // et on remonte un Object pour laisser la stratégie gérer la dégradation.
    private fun opaque(type: PsiType): ResolvedType {
        val canonical = type.canonicalText
        return ResolvedType(
            rawType = canonical.substringAfterLast('.'),
            fqName = canonical
        )
    }
}

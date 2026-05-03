package com.contextextractor.fakes

import com.contextextractor.core.extractor.ResolvedType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// case96_degraded — STRATEGIE.md §8bis : cas dégradés.
// Trois invariants à valider sur le ResolvedType :
//  - wildcard `?` → isWildcard=true (§8bis.1)
//  - paramètre générique `T` non résolu → isUnresolvedTypeParameter=true (§8bis.5)
//  - cycle NodeA<T> ↔ NodeB<T> représentable sans boucle infinie côté model.
class Case96DegradedTest {

    private val pkg = "com.testproject.case96_degraded"
    private val fake = Fixtures.case96Degraded()

    @Test
    fun `LegacyRepository loadRaw returns Map with two wildcard arguments`() {
        val loadRaw = fake.listMethodsOf("$pkg.LegacyRepository").single { it.name == "loadRaw" }
        val ret = loadRaw.returnType
        assertEquals("java.util.Map", ret.fqName)
        assertTrue(ret.isContainer)
        assertEquals(2, ret.typeArgs.size)
        ret.typeArgs.forEach { arg ->
            assertTrue(arg.isWildcard, "wildcard `?` doit être marqué isWildcard")
            assertEquals("java.lang.Object", arg.fqName, "fallback wildcard → Object §8bis.1")
        }
    }

    @Test
    fun `NodeA and NodeB use unresolved type parameter T and reference each other`() {
        val nodeA = fake.resolveClass("$pkg.NodeA")
        val nodeB = fake.resolveClass("$pkg.NodeB")
        assertNotNull(nodeA)
        assertNotNull(nodeB)

        val peerA = fake.listFieldsOf("$pkg.NodeA").single { it.name == "peer" }
        assertEquals("$pkg.NodeB", peerA.type.fqName)
        assertEquals(1, peerA.type.typeArgs.size)
        assertTrue(peerA.type.typeArgs[0].isUnresolvedTypeParameter)
        assertEquals("T", peerA.type.typeArgs[0].rawType)

        val peerB = fake.listFieldsOf("$pkg.NodeB").single { it.name == "peer" }
        assertEquals("$pkg.NodeA", peerB.type.fqName)
    }

    @Test
    fun `flatten on cyclic generic type does not loop forever and lists peer types`() {
        val rootNode = fake.listFieldsOf("$pkg.LegacyService").single { it.name == "rootNode" }
        val flat = rootNode.type.flatten()
        // NodeA<String>.flatten() = ["NodeA", "String"] — pas de récursion sur NodeB
        // car le ContextTree gère le cycle au niveau de la stratégie, pas du type.
        assertEquals(listOf("NodeA", "String"), flat)
    }

    @Test
    fun `LegacyService uses NodeA String specifically (not generic T)`() {
        val rootNode = fake.listFieldsOf("$pkg.LegacyService").single { it.name == "rootNode" }
        assertEquals("$pkg.NodeA", rootNode.type.fqName)
        assertEquals(1, rootNode.type.typeArgs.size)
        assertEquals("java.lang.String", rootNode.type.typeArgs[0].fqName)
        assertFalse(rootNode.type.typeArgs[0].isUnresolvedTypeParameter)
    }

    @Test
    fun `process target method calls into both repository and rootNode`() {
        val process = fake.listMethodsOf("$pkg.LegacyService").single { it.name == "process" }
        assertEquals(1, process.parameters.size)
        assertEquals("tag", process.parameters[0].name)

        val calls = fake.listMethodCalls(process)
        assertTrue(calls.any { it.targetType == "$pkg.LegacyRepository" && it.methodName == "loadRaw" })
        assertTrue(calls.any { it.targetType == "$pkg.NodeA" && it.methodName == "getPeer" })
        assertTrue(calls.any { it.targetType == "$pkg.NodeA" && it.methodName == "getPayload" })
    }

    @Test
    fun `wildcard ResolvedType is consistent across uses`() {
        // Le wildcard est identifié par son flag isWildcard et un fallback
        // sur Object (§8bis.1). Les comparaisons doivent passer par les data
        // class equals — peu importe que ce soit le même objet ou non.
        val raw = fake.listMethodsOf("$pkg.LegacyRepository").single().returnType.typeArgs[0]
        val dtoArg = fake.listFieldsOf("$pkg.LegacyDTO").single { it.name == "rawData" }
            .type.typeArgs[0]
        val wildcardSentinel: ResolvedType = Wildcard

        assertTrue(raw.isWildcard && dtoArg.isWildcard && wildcardSentinel.isWildcard)
        // Toutes les variantes doivent être equals (data class).
        assertEquals(raw, dtoArg)
        assertEquals(raw, wildcardSentinel)
    }
}

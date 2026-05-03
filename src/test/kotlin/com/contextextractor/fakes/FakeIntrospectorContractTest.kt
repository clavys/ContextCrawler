package com.contextextractor.fakes

import com.contextextractor.core.extractor.AnnotatedTarget
import com.contextextractor.core.extractor.SourceFile
import com.contextextractor.core.extractor.Symbol
import com.contextextractor.core.extractor.SymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Vérifie le contrat de FakeIntrospector — les 8 méthodes du port répondent
// de façon cohérente. Test indépendant des fixtures de cas (case00, case91, …).
class FakeIntrospectorContractTest {

    @Test
    fun `empty fixture returns null and empty lists for unknown queries`() {
        val fake = fixture { }

        assertNull(fake.resolveClass("com.unknown.Type"))
        assertNull(fake.resolveSymbolAt(SourceFile("ghost.java", "JAVA"), 0))
        assertTrue(fake.listMethodsOf("com.unknown.Type").isEmpty())
        assertTrue(fake.listFieldsOf("com.unknown.Type").isEmpty())
    }

    @Test
    fun `klass registers descriptor with annotations and package`() {
        val fake = fixture {
            klass(
                "com.example.OrderService",
                annotations = listOf("org.springframework.stereotype.Service")
            )
        }

        val descriptor = fake.resolveClass("com.example.OrderService")
        assertNotNull(descriptor)
        assertEquals("OrderService", descriptor!!.simpleName)
        assertEquals("com.example", descriptor.packageName)
        assertEquals(listOf("org.springframework.stereotype.Service"), descriptor.annotations)

        val onClassAnnotations = fake.listAnnotations(AnnotatedTarget.OnClass("com.example.OrderService"))
        assertEquals(1, onClassAnnotations.size)
        assertEquals("org.springframework.stereotype.Service", onClassAnnotations[0].fqn)
    }

    @Test
    fun `method records params calls accesses and body`() {
        val fake = fixture {
            klass("com.example.Svc") {
                field("repo", T("com.example.Repo"))
                method(
                    "findOrder",
                    returns = T("com.example.OrderDTO"),
                    body = "return repo.findById(id);"
                ) {
                    param("id", T("java.lang.Long"))
                    reads("com.example.Svc", "repo")
                    calls("com.example.Repo", "findById", "java.lang.Long")
                }
            }
        }

        val methods = fake.listMethodsOf("com.example.Svc")
        assertEquals(1, methods.size)
        val findOrder = methods[0]
        assertEquals("findOrder", findOrder.name)
        assertEquals(1, findOrder.parameters.size)
        assertEquals("id", findOrder.parameters[0].name)

        val calls = fake.listMethodCalls(findOrder)
        assertEquals(1, calls.size)
        assertEquals("findById", calls[0].methodName)
        assertEquals(listOf("java.lang.Long"), calls[0].argTypes)

        val accesses = fake.listFieldAccesses(findOrder)
        assertEquals(1, accesses.size)
        assertEquals("repo", accesses[0].fieldName)
        assertEquals(false, accesses[0].write)

        assertEquals("return repo.findById(id);", fake.readMethodBody(findOrder))
    }

    @Test
    fun `method-level annotation lookup returns the annotation refs`() {
        val fake = fixture {
            klass("com.example.Svc") {
                method("init", annotations = listOf("jakarta.annotation.PostConstruct"))
            }
        }

        val init = fake.listMethodsOf("com.example.Svc").single()
        val target = AnnotatedTarget.OnMethod("com.example.Svc", init.canonical())
        val annotations = fake.listAnnotations(target)

        assertEquals(1, annotations.size)
        assertEquals("jakarta.annotation.PostConstruct", annotations[0].fqn)
    }

    @Test
    fun `super chain is reported in declared order`() {
        val fake = fixture {
            klass("com.example.Concrete", superFqn = "com.example.Mid")
            klass("com.example.Mid", superFqn = "com.example.Top", isAbstract = true)
            klass("com.example.Top", isAbstract = true)
            superChain("com.example.Concrete", "com.example.Mid", "com.example.Top")
        }

        val concrete = fake.resolveClass("com.example.Concrete")!!
        val chain = fake.listSuperClasses(concrete).map { it.fqn }
        assertEquals(listOf("com.example.Mid", "com.example.Top"), chain)
    }

    @Test
    fun `resolveSymbolAt and findEnclosingMethod link cursor to method`() {
        val file = SourceFile("Svc.java", "JAVA")
        val cursorOffset = 42
        val symbol = Symbol(id = "sym-1", fqn = "com.example.Svc#findOrder", kind = SymbolKind.METHOD)

        val fake = fixture {
            klass("com.example.Svc") {
                method("findOrder", returns = T("com.example.OrderDTO"))
            }
        }
        val signature = fake.listMethodsOf("com.example.Svc").single()
        // Le DSL ne couvre pas encore symbol/enclosing — on passe par les API
        // internes du fake. C'est intentionnel : ces appels sont rares (seul
        // resolveSymbolAt en a besoin) et le DSL reste centré sur le code.
        fake.putSymbolAt(file, cursorOffset, symbol)
        fake.putEnclosing(symbol, signature)

        val resolved = fake.resolveSymbolAt(file, cursorOffset)
        assertNotNull(resolved)
        assertEquals(symbol, resolved)
        val enclosing = fake.findEnclosingMethod(symbol)
        assertNotNull(enclosing)
        assertEquals("findOrder", enclosing!!.name)
        assertEquals(signature, enclosing)
    }
}

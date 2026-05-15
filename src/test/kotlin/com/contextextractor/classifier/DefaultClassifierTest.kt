package com.contextextractor.classifier

import com.contextextractor.core.classifier.CallerContext
import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.classifier.ExtractionMode
import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.extractor.ResolvedType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Vérifie les règles 1 → 12 de STRATEGIE.md §2.1. Chaque test cible UNE règle ;
// les tests de priorité couvrent les chevauchements (SYSTEM_IGNORE > tout,
// ROOT_SUT > règles structurelles).
class DefaultClassifierTest {

    private val classifier = DefaultClassifier()

    private fun type(fqn: String, isCollection: Boolean = false, isContainer: Boolean = false) =
        ResolvedType(rawType = fqn.substringAfterLast('.'), fqName = fqn,
            isCollection = isCollection, isContainer = isContainer)

    private fun descriptor(
        fqn: String,
        isInterface: Boolean = false,
        isAbstract: Boolean = false,
        isRecord: Boolean = false,
        isSealed: Boolean = false,
        isEnum: Boolean = false,
        annotations: List<String> = emptyList()
    ) = ClassDescriptor(
        fqn = fqn,
        simpleName = fqn.substringAfterLast('.'),
        superFqn = null,
        isInterface = isInterface,
        isAbstract = isAbstract,
        isRecord = isRecord,
        isSealed = isSealed,
        isEnum = isEnum,
        annotations = annotations
    )

    // ── Règle 1 : SYSTEM_IGNORE ────────────────────────────────────────────────

    @Test
    fun `primitive types are system-ignored`() {
        listOf("int", "long", "boolean", "char", "double", "void").forEach { p ->
            assertEquals(
                ExtractionMode.SYSTEM_IGNORE,
                classifier.classify(type(p), null, CallerContext.PARAM_OF_METHOD),
                "primitive $p should be SYSTEM_IGNORE"
            )
        }
    }

    @Test
    fun `JDK types are system-ignored`() {
        listOf(
            "java.lang.String",
            "java.lang.Integer",
            "java.time.LocalDate",
            "javax.sql.DataSource",
            "kotlin.Unit",
            "sun.misc.Unsafe"
        ).forEach { fqn ->
            assertEquals(
                ExtractionMode.SYSTEM_IGNORE,
                classifier.classify(type(fqn), null, CallerContext.PARAM_OF_METHOD),
                "JDK type $fqn should be SYSTEM_IGNORE"
            )
        }
    }

    @Test
    fun `Object Void and void are system-ignored`() {
        listOf("Object", "Void", "void").forEach {
            assertEquals(
                ExtractionMode.SYSTEM_IGNORE,
                classifier.classify(type(it), null, CallerContext.FIELD_OF_SUT)
            )
        }
    }

    // ── Règle 2 : ROOT_SUT → SUT_BOOTSTRAP ─────────────────────────────────────

    @Test
    fun `ROOT_SUT context always yields SUT_BOOTSTRAP`() {
        // Même un type qui matcherait ailleurs (interface) reste SUT_BOOTSTRAP en ROOT_SUT.
        val desc = descriptor("com.demo.OrderService", isInterface = true,
            annotations = listOf("org.springframework.stereotype.Service"))
        assertEquals(
            ExtractionMode.SUT_BOOTSTRAP,
            classifier.classify(type("com.demo.OrderService"), desc, CallerContext.ROOT_SUT)
        )
    }

    // ── Règle 3 : FUNCTIONAL_LAMBDA ────────────────────────────────────────────

    @Test
    fun `java util function types are functional lambdas`() {
        listOf(
            "java.util.function.Function",
            "java.util.function.Consumer",
            "java.util.function.Supplier",
            "java.util.function.Predicate",
            "java.util.function.BiFunction"
        ).forEach { fqn ->
            assertEquals(
                ExtractionMode.FUNCTIONAL_LAMBDA,
                classifier.classify(type(fqn), null, CallerContext.PARAM_OF_METHOD),
                "$fqn should be FUNCTIONAL_LAMBDA"
            )
        }
    }

    // ── Règle 4 : CONTAINER ────────────────────────────────────────────────────

    @Test
    fun `Optional and CompletableFuture are containers despite java prefix`() {
        // Régression : sans l'exemption dans isSystemType, ces types tomberaient en SYSTEM_IGNORE.
        listOf(
            "java.util.Optional",
            "java.util.concurrent.CompletableFuture",
            "reactor.core.publisher.Mono",
            "reactor.core.publisher.Flux"
        ).forEach { fqn ->
            assertEquals(
                ExtractionMode.CONTAINER,
                classifier.classify(type(fqn), null, CallerContext.RETURN_OF_METHOD),
                "$fqn should be CONTAINER"
            )
        }
    }

    @Test
    fun `isContainer flag forces CONTAINER mode`() {
        // ResolvedType peut porter le flag même pour un type custom — l'introspecteur PSI
        // le pose pour les conteneurs reconnus.
        assertEquals(
            ExtractionMode.CONTAINER,
            classifier.classify(
                ResolvedType(rawType = "Maybe", fqName = "io.reactivex.Maybe", isContainer = true),
                null, CallerContext.RETURN_OF_METHOD
            )
        )
    }

    // ── Règle 5 : COLLECTION ───────────────────────────────────────────────────

    @Test
    fun `Collection types are collections despite java prefix`() {
        listOf(
            "java.util.List",
            "java.util.Set",
            "java.util.Map",
            "java.util.Collection",
            "java.lang.Iterable"
        ).forEach { fqn ->
            assertEquals(
                ExtractionMode.COLLECTION,
                classifier.classify(type(fqn), null, CallerContext.FIELD_OF_DTO),
                "$fqn should be COLLECTION"
            )
        }
    }

    // ── Règle 6 : DATA_STRUCTURE pour enum / sealed ────────────────────────────

    @Test
    fun `enum types are data structures`() {
        val desc = descriptor("com.demo.Status", isEnum = true)
        assertEquals(
            ExtractionMode.DATA_STRUCTURE,
            classifier.classify(type("com.demo.Status"), desc, CallerContext.PARAM_OF_METHOD)
        )
    }

    @Test
    fun `sealed types are data structures`() {
        val desc = descriptor("com.demo.Event", isSealed = true)
        assertEquals(
            ExtractionMode.DATA_STRUCTURE,
            classifier.classify(type("com.demo.Event"), desc, CallerContext.RETURN_OF_METHOD)
        )
    }

    // ── Règle 8 : MOCK_EXTERNAL pour interface ou abstract ────────────────────

    @Test
    fun `interfaces are mocked`() {
        val desc = descriptor("com.demo.OrderRepository", isInterface = true)
        assertEquals(
            ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(type("com.demo.OrderRepository"), desc, CallerContext.FIELD_OF_SUT)
        )
    }

    @Test
    fun `abstract classes are mocked`() {
        val desc = descriptor("com.demo.AbstractGateway", isAbstract = true)
        assertEquals(
            ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(type("com.demo.AbstractGateway"), desc, CallerContext.FIELD_OF_SUT)
        )
    }

    // ── Règle 9 : MOCK_EXTERNAL pour @Service / @Repository / @Component ──────

    @Test
    fun `Spring stereotype classes are mocked outside ROOT_SUT`() {
        val annotations = listOf(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Component",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.cloud.openfeign.FeignClient"
        )
        annotations.forEach { ann ->
            val desc = descriptor("com.demo.SomeService", annotations = listOf(ann))
            assertEquals(
                ExtractionMode.MOCK_EXTERNAL,
                classifier.classify(type("com.demo.SomeService"), desc, CallerContext.FIELD_OF_SUT),
                "annotation $ann should trigger MOCK_EXTERNAL"
            )
        }
    }

    // ── Règle 10 : DATA_STRUCTURE pour record / Lombok ────────────────────────

    @Test
    fun `record types are data structures`() {
        val desc = descriptor("com.demo.OrderDTO", isRecord = true)
        assertEquals(
            ExtractionMode.DATA_STRUCTURE,
            classifier.classify(type("com.demo.OrderDTO"), desc, CallerContext.PARAM_OF_METHOD)
        )
    }

    @Test
    fun `class with only accessors and ctor is DATA_STRUCTURE via no-business-method branch`() {
        val desc = descriptor("com.demo.OrderRequest")
        val intType = ResolvedType("int", "int")
        val voidType = ResolvedType("void", "void")
        val methods = listOf(
            MethodSignature("<init>", voidType, listOf(Parameter("id", intType)), visibility = "public"),
            MethodSignature("getId", intType, emptyList(), visibility = "public"),
            MethodSignature("setId", voidType, listOf(Parameter("id", intType)), visibility = "public"),
            MethodSignature("equals", ResolvedType("boolean", "boolean"),
                listOf(Parameter("other", ResolvedType("Object", "Object"))), visibility = "public"),
            MethodSignature("hashCode", intType, emptyList(), visibility = "public")
        )
        assertEquals(
            ExtractionMode.DATA_STRUCTURE,
            classifier.classify(type("com.demo.OrderRequest"), desc, CallerContext.PARAM_OF_METHOD,
                emptySet(), methods),
            "POJO with only accessors must be DATA_STRUCTURE (rule 10 branch « no business method »)"
        )
    }

    @Test
    fun `class with one business method is NOT classified as DATA_STRUCTURE`() {
        val desc = descriptor("com.demo.Config")
        val doubleType = ResolvedType("double", "double")
        val voidType = ResolvedType("void", "void")
        // apply(double) est une vraie méthode métier (pas un accesseur).
        val methods = listOf(
            MethodSignature("<init>", voidType, emptyList(), visibility = "public"),
            MethodSignature("getRate", doubleType, emptyList(), visibility = "public"),
            MethodSignature("apply", doubleType, listOf(Parameter("amount", doubleType)), visibility = "public")
        )
        assertEquals(
            ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(type("com.demo.Config"), desc, CallerContext.FIELD_OF_SUT,
                emptySet(), methods),
            "any non-trivial method blocks rule 10 'no business method'"
        )
    }

    @Test
    fun `Lombok value-class annotations yield DATA_STRUCTURE`() {
        listOf("lombok.Data", "lombok.Value", "lombok.Builder").forEach { ann ->
            val desc = descriptor("com.demo.OrderDTO", annotations = listOf(ann))
            assertEquals(
                ExtractionMode.DATA_STRUCTURE,
                classifier.classify(type("com.demo.OrderDTO"), desc, CallerContext.PARAM_OF_METHOD),
                "annotation $ann should yield DATA_STRUCTURE"
            )
        }
    }

    // ── Règle 11 : INTERNAL_LOGIC pour CALL_TARGET intra-hiérarchie ───────────

    @Test
    fun `CALL_TARGET in SUT hierarchy yields INTERNAL_LOGIC`() {
        // Cas d'usage : la classe SUT appelle une méthode héritée de sa super-classe.
        val hierarchy = setOf("com.demo.OrderService", "com.demo.AbstractCacheService", "com.demo.OrderHelpers")
        val desc = descriptor("com.demo.AbstractCacheService", isAbstract = true)
        // NB : sans la hiérarchie passée, le mode tomberait en MOCK_EXTERNAL (rule 8)
        // — on vérifie ici que rule 11 ne court-circuite PAS rule 8 (ordre §2.1).
        assertEquals(
            ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(type("com.demo.AbstractCacheService"), desc, CallerContext.CALL_TARGET, hierarchy),
            "abstract intra-SUT class hits rule 8 before rule 11 — spec §2.1 ordering"
        )
        // Cas où rules 6-10 ne matchent pas : classe concrète sans annotations,
        // dans la hiérarchie du SUT.
        val plainDesc = descriptor("com.demo.OrderHelpers")
        assertEquals(
            ExtractionMode.INTERNAL_LOGIC,
            classifier.classify(type("com.demo.OrderHelpers"), plainDesc, CallerContext.CALL_TARGET, hierarchy)
        )
    }

    @Test
    fun `CALL_TARGET outside hierarchy falls through to default`() {
        val plainDesc = descriptor("com.demo.ExternalHelper")
        assertEquals(
            ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(type("com.demo.ExternalHelper"), plainDesc, CallerContext.CALL_TARGET, emptySet())
        )
    }

    // ── Règle 12 : MOCK_EXTERNAL par défaut ───────────────────────────────────

    @Test
    fun `unknown user class without descriptor falls through to MOCK_EXTERNAL`() {
        // Pas de descripteur → seules rules 1, 2, 3, 4, 5 peuvent matcher. Si aucune, défaut.
        assertEquals(
            ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(type("com.demo.MysteryClass"), null, CallerContext.INSTANTIATION)
        )
    }

    @Test
    fun `concrete class without annotations falls through to MOCK_EXTERNAL`() {
        val desc = descriptor("com.demo.PlainClass")
        assertEquals(
            ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(type("com.demo.PlainClass"), desc, CallerContext.PARAM_OF_METHOD)
        )
    }

    // ── Tests de priorité ─────────────────────────────────────────────────────

    @Test
    fun `SYSTEM_IGNORE wins over every later rule`() {
        // Un type primitif resterait primitif même si on lui collait un descripteur fantaisiste.
        val desc = descriptor("int", isInterface = true,
            annotations = listOf("org.springframework.stereotype.Service"))
        assertEquals(
            ExtractionMode.SYSTEM_IGNORE,
            classifier.classify(type("int"), desc, CallerContext.PARAM_OF_METHOD),
            "rule 1 must short-circuit interface (rule 8) and Spring (rule 9)"
        )
    }

    @Test
    fun `ROOT_SUT wins over interface and Spring annotations`() {
        // Cas réel : le SUT racine est un @Service. Sans la priorité ROOT_SUT, il deviendrait MOCK_EXTERNAL.
        val desc = descriptor("com.demo.OrderService",
            annotations = listOf("org.springframework.stereotype.Service"))
        assertEquals(
            ExtractionMode.SUT_BOOTSTRAP,
            classifier.classify(type("com.demo.OrderService"), desc, CallerContext.ROOT_SUT)
        )
    }

    @Test
    fun `interface annotated with Service is mocked via rule 8 not rule 9`() {
        // Détail d'ordre — rule 8 matche en premier. Important pour ne pas double-traiter.
        val desc = descriptor("com.demo.OrderRepository", isInterface = true,
            annotations = listOf("org.springframework.stereotype.Repository"))
        assertEquals(
            ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(type("com.demo.OrderRepository"), desc, CallerContext.FIELD_OF_SUT)
        )
    }
}

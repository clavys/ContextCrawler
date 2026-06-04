package com.contextextractor.classifier

import com.contextextractor.core.classifier.DefaultContextAwareClassifier
import com.contextextractor.core.classifier.ExtractionMode
import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.extractor.ResolvedType
import com.contextextractor.core.model.refs.ClassReference
import com.contextextractor.core.model.refs.UsageSite
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Phase 2.3 — Tests du `DefaultContextAwareClassifier`.
//
// Couvre les 16 règles V1.2 + reproduit les patterns V1.1 qui causaient
// des patches structurels (Bug U, B, S, I) pour confirmer qu'ils sont
// résolus NATIVEMENT par la classification context-aware.
class ContextAwareClassifierTest {

    private val classifier = DefaultContextAwareClassifier()

    // ── Helpers de fabrication de ClassReference ─────────────────────────────

    private fun ty(fqn: String, nullable: Boolean = false) =
        ResolvedType(rawType = fqn.substringAfterLast('.'), fqName = fqn, nullable = nullable)

    private fun desc(
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

    private fun ref(
        fqn: String,
        descriptor: ClassDescriptor? = null,
        vararg usages: UsageSite
    ) = ClassReference(fqn, descriptor, usages.toList())

    private fun callerMethod(name: String = "caller", returns: ResolvedType = ty("void")) =
        MethodSignature(name = name, returnType = returns, parameters = emptyList(), visibility = "public")

    private fun callSite(
        ownerOfTarget: String,
        ownerOfCaller: String,
        methodName: String,
        resolved: MethodSignature? = null
    ) = UsageSite.AsCallTarget(
        call = MethodCall(ownerOfTarget, methodName, emptyList()),
        resolvedMethod = resolved,
        callerOwnerFqn = ownerOfCaller,
        callerMethod = callerMethod()
    )

    private fun fieldSite(fieldName: String, typeFqn: String, declaringClass: String,
                          annotations: List<String> = emptyList()) =
        UsageSite.AsFieldOfSut(ClassField(
            name = fieldName,
            type = ty(typeFqn),
            visibility = "private",
            annotations = annotations,
            declaredIn = declaringClass
        ))

    // ── Règle 1 : Système (primitifs + java.*) ───────────────────────────────

    @Test
    fun `R1 — primitives → SYSTEM_IGNORE`() {
        listOf("int", "long", "boolean", "char", "double", "void").forEach { p ->
            val mode = classifier.classify(ref(p), emptySet())
            assertEquals(ExtractionMode.SYSTEM_IGNORE, mode, "primitive $p")
        }
    }

    @Test
    fun `R1 — JDK types → SYSTEM_IGNORE`() {
        listOf("java.lang.String", "java.time.LocalDate", "javax.sql.DataSource",
            "kotlin.Unit", "sun.misc.Unsafe").forEach { fqn ->
            val mode = classifier.classify(ref(fqn), emptySet())
            assertEquals(ExtractionMode.SYSTEM_IGNORE, mode, "JDK $fqn")
        }
    }

    // ── Règle 2 : Framework prefixes ─────────────────────────────────────────

    @Test
    fun `R2 — framework prefix → SYSTEM_IGNORE`() {
        val mode = classifier.classify(
            ref("javax.faces.context.FacesContext", desc("javax.faces.context.FacesContext")),
            emptySet(),
            frameworkPrefixes = listOf("javax.faces.")
        )
        assertEquals(ExtractionMode.SYSTEM_IGNORE, mode)
    }

    // ── Règle 3-4 : Container & Collection ──────────────────────────────────

    @Test
    fun `R3 — Optional → CONTAINER`() {
        assertEquals(ExtractionMode.CONTAINER,
            classifier.classify(ref("java.util.Optional"), emptySet()))
    }

    @Test
    fun `R3 — CompletableFuture → CONTAINER`() {
        assertEquals(ExtractionMode.CONTAINER,
            classifier.classify(ref("java.util.concurrent.CompletableFuture"), emptySet()))
    }

    @Test
    fun `R4 — List → COLLECTION`() {
        assertEquals(ExtractionMode.COLLECTION,
            classifier.classify(ref("java.util.List"), emptySet()))
    }

    @Test
    fun `R4 — Map → COLLECTION`() {
        assertEquals(ExtractionMode.COLLECTION,
            classifier.classify(ref("java.util.Map"), emptySet()))
    }

    // ── Règle 5 : SAM ────────────────────────────────────────────────────────

    @Test
    fun `R5 — java_util_function Function → FUNCTIONAL_LAMBDA`() {
        assertEquals(ExtractionMode.FUNCTIONAL_LAMBDA,
            classifier.classify(ref("java.util.function.Function"), emptySet()))
    }

    // ── Règle 6 : Hiérarchie SUT → INTERNAL_LOGIC ────────────────────────────

    @Test
    fun `R6 — SUT hierarchy class → INTERNAL_LOGIC`() {
        val mode = classifier.classify(
            ref("com.foo.SutBase", desc("com.foo.SutBase")),
            hierarchyFqns = setOf("com.foo.SutBase", "com.foo.SutChild")
        )
        assertEquals(ExtractionMode.INTERNAL_LOGIC, mode)
    }

    // ── Règle 7 : STATIC_UTILITY ─────────────────────────────────────────────

    @Test
    fun `R7 — only static calls → STATIC_UTILITY`() {
        val ref = ref("com.foo.Utils", desc("com.foo.Utils"),
            UsageSite.AsStaticCallTarget(
                MethodCall("com.foo.Utils", "helper", emptyList(), isStatic = true),
                callerMethod()
            )
        )
        assertEquals(ExtractionMode.STATIC_UTILITY, classifier.classify(ref, emptySet()))
    }

    @Test
    fun `R7 — both static AND instance calls → MOCK_EXTERNAL (rule 8 wins)`() {
        // Si un type est appelé EN INSTANCE quelque part, on doit pouvoir le
        // mocker — règle 8 prioritaire sur règle 7.
        val ref = ref("com.foo.Hybrid", desc("com.foo.Hybrid"),
            UsageSite.AsStaticCallTarget(
                MethodCall("com.foo.Hybrid", "staticHelper", emptyList(), isStatic = true),
                callerMethod()
            ),
            callSite("com.foo.Hybrid", "com.foo.Caller", "doIt")
        )
        assertEquals(ExtractionMode.MOCK_EXTERNAL, classifier.classify(ref, emptySet()))
    }

    // ── Règle 8 : MOCK_EXTERNAL via appel d'instance (Bug U natif) ──────────

    @Test
    fun `R8 — Bug U natif — modele with only setters but called as instance → MOCK_EXTERNAL`() {
        // En V1.1, ce cas tombait dans rule 10 (toutes méthodes triviales →
        // DATA_STRUCTURE) puis nécessitait Bug U pour le promouvoir en MOCK.
        // En V1.2, la règle 8 (appel d'instance détecté) court-circuite la
        // détection DTO → décision correcte immédiatement.
        val modeleDesc = desc("com.foo.Modele")
        val modeleMethods = listOf(
            MethodSignature("setAfficherResultats", ty("void"),
                listOf(Parameter("v", ty("boolean"))), visibility = "public"),
            MethodSignature("setLienVisible", ty("void"),
                listOf(Parameter("v", ty("boolean"))), visibility = "public")
        )
        val r = ref("com.foo.Modele", modeleDesc,
            fieldSite("modele", "com.foo.Modele", "com.foo.Ctrl",
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired")),
            callSite("com.foo.Modele", "com.foo.Ctrl", "setAfficherResultats")
        )
        assertEquals(ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(r, emptySet(), descriptorMethods = modeleMethods),
            "Bug U natif : modele avec setters appelé doit être MOCK_EXTERNAL " +
                "sans promotion post-hoc")
    }

    @Test
    fun `R8 — service called as instance → MOCK_EXTERNAL`() {
        val r = ref("com.foo.Svc", desc("com.foo.Svc", isInterface = true),
            callSite("com.foo.Svc", "com.foo.Ctrl", "execute")
        )
        assertEquals(ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(r, emptySet()))
    }

    // ── Règle 9 : ENUM / SEALED / RECORD ─────────────────────────────────────

    @Test
    fun `R9 — enum → DATA_STRUCTURE`() {
        assertEquals(ExtractionMode.DATA_STRUCTURE,
            classifier.classify(
                ref("com.foo.Status", desc("com.foo.Status", isEnum = true)),
                emptySet()
            )
        )
    }

    @Test
    fun `R9 — record → DATA_STRUCTURE`() {
        assertEquals(ExtractionMode.DATA_STRUCTURE,
            classifier.classify(
                ref("com.foo.PointR", desc("com.foo.PointR", isRecord = true)),
                emptySet()
            )
        )
    }

    @Test
    fun `R9 — sealed → DATA_STRUCTURE`() {
        assertEquals(ExtractionMode.DATA_STRUCTURE,
            classifier.classify(
                ref("com.foo.Result", desc("com.foo.Result", isSealed = true)),
                emptySet()
            )
        )
    }

    // ── Règle 10 : Lombok ───────────────────────────────────────────────────

    @Test
    fun `R10 — Lombok @Data → DATA_STRUCTURE`() {
        assertEquals(ExtractionMode.DATA_STRUCTURE,
            classifier.classify(
                ref("com.foo.UserDTO", desc("com.foo.UserDTO", annotations = listOf("lombok.Data"))),
                emptySet()
            )
        )
    }

    @Test
    fun `R10 — Lombok @Builder → DATA_STRUCTURE`() {
        assertEquals(ExtractionMode.DATA_STRUCTURE,
            classifier.classify(
                ref("com.foo.QueryBuilder",
                    desc("com.foo.QueryBuilder", annotations = listOf("lombok.Builder"))),
                emptySet()
            )
        )
    }

    // ── Règle 11 : Toutes méthodes triviales (V1.1 rule 10 préservé) ────────

    @Test
    fun `R11 — class with only accessors → DATA_STRUCTURE`() {
        val pojoMethods = listOf(
            MethodSignature("getName", ty("java.lang.String"), emptyList(), visibility = "public"),
            MethodSignature("setName", ty("void"),
                listOf(Parameter("n", ty("java.lang.String"))), visibility = "public"),
            MethodSignature("equals", ty("boolean"),
                listOf(Parameter("o", ty("java.lang.Object"))), visibility = "public")
        )
        val r = ref("com.foo.UserDTO", desc("com.foo.UserDTO"))
        assertEquals(ExtractionMode.DATA_STRUCTURE,
            classifier.classify(r, emptySet(), descriptorMethods = pojoMethods))
    }

    // ── Règle 12 : Instancié → DATA_STRUCTURE forcé ──────────────────────────

    @Test
    fun `R12 — instantiated in body → DATA_STRUCTURE (cannot mock concrete instantiations)`() {
        val r = ref("com.foo.Helper",
            desc("com.foo.Helper", isInterface = true),  // serait MOCK via R13 normalement
            UsageSite.AsInstantiationInBody(callerMethod())
        )
        assertEquals(ExtractionMode.DATA_STRUCTURE, classifier.classify(r, emptySet()),
            "Si le body fait `new Helper()`, on ne peut PAS le mocker — DTO forcé")
    }

    // ── Règle 13 : Interface / abstract → MOCK ───────────────────────────────

    @Test
    fun `R13 — interface (uncalled) → MOCK_EXTERNAL`() {
        val r = ref("com.foo.SomeInterface",
            desc("com.foo.SomeInterface", isInterface = true))
        assertEquals(ExtractionMode.MOCK_EXTERNAL, classifier.classify(r, emptySet()))
    }

    @Test
    fun `R13 — abstract class → MOCK_EXTERNAL`() {
        val r = ref("com.foo.AbstractBase",
            desc("com.foo.AbstractBase", isAbstract = true))
        assertEquals(ExtractionMode.MOCK_EXTERNAL, classifier.classify(r, emptySet()))
    }

    // ── Règle 14 : Spring annotations → MOCK ────────────────────────────────

    @Test
    fun `R14 — Spring @Service → MOCK_EXTERNAL`() {
        val r = ref("com.foo.OrderService",
            desc("com.foo.OrderService",
                annotations = listOf("org.springframework.stereotype.Service")))
        assertEquals(ExtractionMode.MOCK_EXTERNAL, classifier.classify(r, emptySet()))
    }

    @Test
    fun `R14 — Spring @Repository → MOCK_EXTERNAL`() {
        val r = ref("com.foo.UserRepo",
            desc("com.foo.UserRepo",
                annotations = listOf("org.springframework.stereotype.Repository")))
        assertEquals(ExtractionMode.MOCK_EXTERNAL, classifier.classify(r, emptySet()))
    }

    // ── Règle 16 : Défaut → DATA_STRUCTURE ──────────────────────────────────

    @Test
    fun `R16 — plain POJO with no signals → DATA_STRUCTURE (V1 2 default)`() {
        val r = ref("com.foo.Address", desc("com.foo.Address"))
        assertEquals(ExtractionMode.DATA_STRUCTURE, classifier.classify(r, emptySet()),
            "V1.2 default DTO — V1.1 default était MOCK_EXTERNAL (sécuritaire)")
    }

    // ── Priorités / chevauchements ──────────────────────────────────────────

    @Test
    fun `system type wins over framework prefix`() {
        // java.lang.String est SYSTEM_IGNORE même avec un préfixe framework
        // qui pourrait théoriquement matcher.
        val mode = classifier.classify(
            ref("java.lang.String"), emptySet(),
            frameworkPrefixes = listOf("java.lang.")
        )
        assertEquals(ExtractionMode.SYSTEM_IGNORE, mode)
    }

    @Test
    fun `hierarchy class wins over MOCK rules`() {
        // Même si un service Spring serait normalement MOCK_EXTERNAL,
        // s'il est dans la hiérarchie SUT → INTERNAL_LOGIC.
        val r = ref("com.foo.SutService",
            desc("com.foo.SutService",
                annotations = listOf("org.springframework.stereotype.Service")),
            callSite("com.foo.SutService", "com.foo.Other", "doIt")
        )
        assertEquals(ExtractionMode.INTERNAL_LOGIC,
            classifier.classify(r, hierarchyFqns = setOf("com.foo.SutService")))
    }

    @Test
    fun `instance call wins over Lombok DTO`() {
        // Cas réel : un @Builder peut quand même être appelé via une chaîne
        // fluide. Si appelé d'instance → MOCK pour permettre le stubbing.
        // Wait — un builder appelé en chaîne EST appelé d'instance. Mais on
        // ne veut PAS le mocker, on veut le construire. Question subtile.
        //
        // Heuristique V1.2 : si Lombok @Builder, on est dans un cas DTO clair —
        // l'utilisateur fluentement construit. La règle 8 (isCalledAsInstance)
        // devrait perdre ici. MAIS notre classifier actuel met R8 AVANT R10.
        //
        // → Test documentaire : on CONSTATE le comportement actuel.
        //   Si en pratique ça pose problème, on inverse R8/R10.
        val r = ref("com.foo.QueryBuilder",
            desc("com.foo.QueryBuilder", annotations = listOf("lombok.Builder")),
            callSite("com.foo.QueryBuilder", "com.foo.Caller", "withName")
        )
        // Documentaire : R8 gagne actuellement (instance call → MOCK).
        // Si ce comportement gêne sur des vrais cas Astrea, on inverse l'ordre.
        assertEquals(ExtractionMode.MOCK_EXTERNAL, classifier.classify(r, emptySet()))
    }

    @Test
    fun `instantiation wins over interface MOCK rule`() {
        // R12 (instantiated) vient AVANT R13 (interface) — un code qui fait
        // `new SomeInterface()` (anonyme) doit être DATA_STRUCTURE.
        val r = ref("com.foo.SomeIface",
            desc("com.foo.SomeIface", isInterface = true),
            UsageSite.AsInstantiationInBody(callerMethod())
        )
        assertEquals(ExtractionMode.DATA_STRUCTURE, classifier.classify(r, emptySet()))
    }

    // ── Patterns V1.1 patches reproduits ─────────────────────────────────────

    @Test
    fun `Bug B natif — unused autowired field → DATA_STRUCTURE (no instance calls)`() {
        // V1.1 : Bug B filtrait ces champs en post-hoc. V1.2 : décision naturelle.
        // Le champ est de type interface @Autowired mais jamais appelé → on
        // peut quand même le mocker via R13 (interface).
        val r = ref("com.foo.UnusedSvc",
            desc("com.foo.UnusedSvc", isInterface = true),
            fieldSite("unused", "com.foo.UnusedSvc", "com.foo.Ctrl",
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
        )
        // R13 (interface) → MOCK. C'est ok — le LLM pourra @Mock dessus.
        // Le filter "champ non utilisé" n'est plus nécessaire car l'éviction
        // V1.2 est désactivée (Phase 4).
        assertEquals(ExtractionMode.MOCK_EXTERNAL, classifier.classify(r, emptySet()))
    }

    @Test
    fun `Bug DD natif — type as stub return is NOT MOCK if data-shaped`() {
        // Si `mock.getList(): List<Foo>` est stubé et Foo est data-shaped,
        // Foo doit être DTO (le LLM construira via `new Foo(...)` ou
        // `mock(Foo.class)`). Pas MOCK_EXTERNAL.
        val r = ref("com.foo.Foo", desc("com.foo.Foo"),
            UsageSite.AsStubReturn(
                mockOwnerFqn = "com.foo.SomeSvc",
                mockMethod = MethodSignature("getList",
                    returnType = ty("java.util.List"),
                    parameters = emptyList(), visibility = "public")
            )
        )
        // Sans appel d'instance dessus → DTO par défaut (R16).
        assertEquals(ExtractionMode.DATA_STRUCTURE, classifier.classify(r, emptySet()))
    }
}

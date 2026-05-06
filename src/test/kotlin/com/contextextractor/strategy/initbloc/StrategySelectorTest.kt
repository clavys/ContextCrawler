package com.contextextractor.strategy.initbloc

import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.extractor.ResolvedType
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.core.model.init.InitSource
import com.contextextractor.core.model.init.InitStrategy
import com.contextextractor.core.model.init.MethodInitKind
import com.contextextractor.core.model.init.MethodKey
import com.contextextractor.strategies.recursive.initbloc.CallGraphBuilder
import com.contextextractor.strategies.recursive.initbloc.EntryPointFinder
import com.contextextractor.strategies.recursive.initbloc.StrategySelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4e-ε — vérifie les 11 branches de §4.5 + sous-branches 6a/6b/6c
// + sorties 7a/7b/7c. Total : 18 tests (15 branches/sorties + 3 overrides).
//
// Chaque test construit une situation minimale, puis appelle directement
// StrategySelector.choose(field, sources) — c'est le selector qui est sous test,
// pas SourceCollector ni le BFS (déjà couverts par les tests dédiés).
class StrategySelectorTest {

    // -- Helpers ----------------------------------------------------------------

    private fun aField(name: String, fqType: String, annotations: List<String> = emptyList()) =
        ClassField(
            name = name, type = T(fqType),
            visibility = "private", declaredIn = "com.test.A",
            annotations = annotations
        )

    private fun aMethod(
        owner: String,
        name: String,
        visibility: String = "public",
        params: List<Parameter> = emptyList(),
        annotations: List<String> = emptyList(),
        returnType: ResolvedType = T("void")
    ): MethodSignature {
        return MethodSignature(
            name = name,
            returnType = returnType,
            parameters = params,
            annotations = annotations,
            visibility = visibility
        )
    }

    private fun selectorFor(
        fake: FakeIntrospector,
        hierarchy: Set<String>,
        target: MethodSignature,
        targetOwner: String = "com.test.A",
        maxGraphDepth: Int = 4
    ): StrategySelector {
        val callGraph = CallGraphBuilder(fake).build(hierarchy)
        val finder = EntryPointFinder(fake, hierarchy, maxGraphDepth)
        return StrategySelector(
            introspector = fake,
            hierarchyFqns = hierarchy,
            callGraph = callGraph,
            finder = finder,
            targetMethod = target,
            targetMethodKey = MethodKey.of(targetOwner, target)
        )
    }

    // -- Branch 1 ---------------------------------------------------------------

    @Test
    fun `branch 1 — Constructor source returns CONSTRUCTOR`() {
        val fake = fixture { klass("com.test.A") { method("calculate") } }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val sources = listOf(InitSource.Constructor(parameterName = "repo"))
        val result = sel.choose(aField("repo", "com.test.Repo"), sources)
        assertEquals(InitStrategy.CONSTRUCTOR, result)
    }

    // -- Branch 2 ---------------------------------------------------------------

    @Test
    fun `branch 2 — Autowired field returns MOCKITO_INJECT_MOCKS`() {
        val fake = fixture { klass("com.test.A") { method("calculate") } }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val annotated = aField("repo", "com.test.Repo",
            annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
        // Même avec un setter source disponible, @Autowired gagne (override testé en 16).
        val result = sel.choose(annotated, listOf(InitSource.Setter("setRepo", T("com.test.Repo"))))
        assertEquals(InitStrategy.MOCKITO_INJECT_MOCKS, result)
    }

    // -- Branch 3 ---------------------------------------------------------------

    @Test
    fun `branch 3 — public Setter source returns SETTER`() {
        val fake = fixture { klass("com.test.A") { method("calculate") } }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val sources = listOf(InitSource.Setter("setRepo", T("com.test.Repo")))
        val result = sel.choose(aField("repo", "com.test.Repo"), sources)
        assertEquals(InitStrategy.SETTER("setRepo"), result)
    }

    // -- Branch 4 ---------------------------------------------------------------

    @Test
    fun `branch 4 — direct PostConstruct without params returns CALL_POST_CONSTRUCT`() {
        val fake = fixture { klass("com.test.A") { method("calculate") } }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val initMethod = aMethod("com.test.A", "init",
            annotations = listOf("jakarta.annotation.PostConstruct"))
        val sources = listOf(InitSource.MethodInitializer(
            kind = MethodInitKind.POST_CONSTRUCT,
            method = initMethod,
            visibility = "public",
            parametersRequired = emptyList(),
            hasNullGuard = false,
            externalCalls = emptyList(),
            assignsAlso = emptyList()
        ))
        val result = sel.choose(aField("cache", "java.util.Map"), sources)
        assertEquals(InitStrategy.CALL_POST_CONSTRUCT(initMethod), result)
    }

    // -- Branch 5 ---------------------------------------------------------------

    @Test
    fun `branch 5 — FieldInitializer with safe system type returns IMPLICIT`() {
        val fake = fixture { klass("com.test.A") { method("calculate") } }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val sources = listOf(InitSource.FieldInitializer(
            expression = "LoggerFactory.getLogger(A.class)",
            initType = T("org.slf4j.Logger") // pas système, mais vérifions ENUM en complément
        ))
        // Logger n'est PAS sous un préfixe système (org.slf4j.*) — donc tombe en
        // branche suivante (ici, pas d'autre source → UNTESTABLE_AS_IS).
        val result = sel.choose(aField("log", "org.slf4j.Logger"), sources)
        assertTrue(result is InitStrategy.UNTESTABLE_AS_IS,
            "org.slf4j.Logger n'est pas système → branche 5 ne s'applique pas")
    }

    @Test
    fun `branch 5 — FieldInitializer with java util type returns IMPLICIT`() {
        val fake = fixture { klass("com.test.A") { method("calculate") } }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val sources = listOf(InitSource.FieldInitializer(
            expression = "new ArrayList<>()",
            initType = T("java.util.ArrayList")
        ))
        val result = sel.choose(aField("items", "java.util.ArrayList"), sources)
        assertEquals(InitStrategy.IMPLICIT, result)
    }

    @Test
    fun `branch 5 — FieldInitializer with enum type returns IMPLICIT`() {
        val fake = fixture {
            klass("com.test.A") { method("calculate") }
            klass("com.test.Color", isEnum = true, enumValues = listOf("RED", "BLUE"))
        }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val sources = listOf(InitSource.FieldInitializer(
            expression = "Color.RED", initType = T("com.test.Color")
        ))
        val result = sel.choose(aField("c", "com.test.Color"), sources)
        assertEquals(InitStrategy.IMPLICIT, result)
    }

    // -- Branch 6 (3 sous-branches) ---------------------------------------------

    @Test
    fun `branch 6a — public method no params no externals returns CALL_PUBLIC`() {
        val fake = fixture { klass("com.test.A") { method("calculate") } }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val publicInit = aMethod("com.test.A", "doInit", visibility = "public")
        val sources = listOf(InitSource.MethodInitializer(
            kind = MethodInitKind.ORDINARY, method = publicInit,
            visibility = "public", parametersRequired = emptyList(),
            hasNullGuard = false, externalCalls = emptyList(), assignsAlso = emptyList()
        ))
        assertEquals(InitStrategy.CALL_PUBLIC(publicInit),
            sel.choose(aField("x", "int"), sources))
    }

    @Test
    fun `branch 6b — public method no params with externals returns CALL_PUBLIC_WITH_STUBS`() {
        val fake = fixture { klass("com.test.A") { method("calculate") } }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val publicInit = aMethod("com.test.A", "doInit", visibility = "public")
        val externals = listOf(MethodCall("com.test.B", "service", emptyList(), false))
        val sources = listOf(InitSource.MethodInitializer(
            kind = MethodInitKind.ORDINARY, method = publicInit,
            visibility = "public", parametersRequired = emptyList(),
            hasNullGuard = false, externalCalls = externals, assignsAlso = emptyList()
        ))
        val result = sel.choose(aField("x", "int"), sources)
        assertEquals(InitStrategy.CALL_PUBLIC_WITH_STUBS(publicInit, externals), result)
    }

    @Test
    fun `branch 6c — public method with params returns CALL_PUBLIC_WITH_ARGS`() {
        val fake = fixture { klass("com.test.A") { method("calculate") } }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val publicInit = aMethod("com.test.A", "doInit", visibility = "public",
            params = listOf(Parameter("v", T("int"))))
        val sources = listOf(InitSource.MethodInitializer(
            kind = MethodInitKind.ORDINARY, method = publicInit,
            visibility = "public",
            parametersRequired = listOf(Parameter("v", T("int"))),
            hasNullGuard = false, externalCalls = emptyList(), assignsAlso = emptyList()
        ))
        val result = sel.choose(aField("x", "int"), sources)
        assertTrue(result is InitStrategy.CALL_PUBLIC_WITH_ARGS)
        assertEquals(1, (result as InitStrategy.CALL_PUBLIC_WITH_ARGS).args.size)
    }

    // -- Branch 7 (3 sorties) ---------------------------------------------------

    @Test
    fun `branch 7a — private method reachable via constructor returns IMPLICIT_VIA_CONSTRUCTOR`() {
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                method("<init>", returns = T("com.test.A")) {
                    calls("com.test.A", "doInit")
                }
                method("doInit", visibility = "private") {
                    assigns("com.test.A", "x", rhsExpression = "1")
                }
                method("calculate") { reads("com.test.A", "x") }
            }
        }
        val target = fake.listMethodsOf("com.test.A").single { it.name == "calculate" }
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val doInit = fake.listMethodsOf("com.test.A").single { it.name == "doInit" }
        val sources = listOf(InitSource.MethodInitializer(
            kind = MethodInitKind.ORDINARY, method = doInit,
            visibility = "private", parametersRequired = emptyList(),
            hasNullGuard = false, externalCalls = emptyList(), assignsAlso = emptyList()
        ))
        assertEquals(InitStrategy.IMPLICIT_VIA_CONSTRUCTOR,
            sel.choose(aField("x", "int"), sources))
    }

    @Test
    fun `branch 7b — private method reachable via PostConstruct returns CALL_POST_CONSTRUCT`() {
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                method("init", annotations = listOf("jakarta.annotation.PostConstruct")) {
                    calls("com.test.A", "doInit")
                }
                method("doInit", visibility = "private") {
                    assigns("com.test.A", "x", rhsExpression = "1")
                }
                method("calculate") { reads("com.test.A", "x") }
            }
        }
        val target = fake.listMethodsOf("com.test.A").single { it.name == "calculate" }
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val doInit = fake.listMethodsOf("com.test.A").single { it.name == "doInit" }
        val sources = listOf(InitSource.MethodInitializer(
            kind = MethodInitKind.ORDINARY, method = doInit,
            visibility = "private", parametersRequired = emptyList(),
            hasNullGuard = false, externalCalls = emptyList(), assignsAlso = emptyList()
        ))
        val result = sel.choose(aField("x", "int"), sources)
        assertTrue(result is InitStrategy.CALL_POST_CONSTRUCT)
        assertEquals("init", (result as InitStrategy.CALL_POST_CONSTRUCT).method.name)
    }

    @Test
    fun `branch 7c — private method reachable via public transitive returns CALL_PUBLIC_TRANSITIVE`() {
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                method("publicEntry") { calls("com.test.A", "doInit") }
                method("doInit", visibility = "private") {
                    assigns("com.test.A", "x", rhsExpression = "1")
                }
                method("calculate") { reads("com.test.A", "x") }
            }
        }
        val target = fake.listMethodsOf("com.test.A").single { it.name == "calculate" }
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val doInit = fake.listMethodsOf("com.test.A").single { it.name == "doInit" }
        val sources = listOf(InitSource.MethodInitializer(
            kind = MethodInitKind.ORDINARY, method = doInit,
            visibility = "private", parametersRequired = emptyList(),
            hasNullGuard = false, externalCalls = emptyList(), assignsAlso = emptyList()
        ))
        val result = sel.choose(aField("x", "int"), sources)
        assertTrue(result is InitStrategy.CALL_PUBLIC_TRANSITIVE)
        assertEquals("publicEntry", (result as InitStrategy.CALL_PUBLIC_TRANSITIVE).entryPoint.name)
    }

    // -- Branch 9 ---------------------------------------------------------------

    @Test
    fun `branch 9 — package-private MethodInitializer no params returns CALL_SAME_PACKAGE`() {
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                method("calculate")
            }
        }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val pkgMethod = aMethod("com.test.A", "doInit", visibility = "package-private")
        val sources = listOf(InitSource.MethodInitializer(
            kind = MethodInitKind.ORDINARY, method = pkgMethod,
            visibility = "package-private", parametersRequired = emptyList(),
            hasNullGuard = false, externalCalls = emptyList(), assignsAlso = emptyList()
        ))
        val result = sel.choose(aField("x", "int"), sources)
        assertTrue(result is InitStrategy.CALL_SAME_PACKAGE)
        assertEquals(listOf("doInit"), (result as InitStrategy.CALL_SAME_PACKAGE).callChain)
    }

    // -- Branch 10 --------------------------------------------------------------

    @Test
    fun `branch 10 — auto-init in target method returns IMPLICIT`() {
        // Target assigne `x` AVANT de le lire — ordre source garanti par le DSL.
        val fake = fixture {
            klass("com.test.A") {
                method("calculate") {
                    assigns("com.test.A", "x", rhsExpression = "1")
                    reads("com.test.A", "x")
                }
            }
        }
        val target = fake.listMethodsOf("com.test.A").single { it.name == "calculate" }
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        // Aucune source standard — la branche 10 doit s'activer.
        assertEquals(InitStrategy.IMPLICIT,
            sel.choose(aField("x", "int"), emptyList()))
    }

    // -- Branch 11 --------------------------------------------------------------

    @Test
    fun `branch 11 — no source returns UNTESTABLE_AS_IS with field-named reason and hints`() {
        val fake = fixture { klass("com.test.A") { method("calculate") } }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val result = sel.choose(aField("orphan", "com.test.Repo"), emptyList())
        assertTrue(result is InitStrategy.UNTESTABLE_AS_IS)
        val u = result as InitStrategy.UNTESTABLE_AS_IS
        assertTrue(u.reason.contains("orphan"), "raison doit nommer le champ")
        assertTrue(u.refactorHints.isNotEmpty(), "au moins une piste de refactor")
    }

    // -- Overrides de priorité --------------------------------------------------

    @Test
    fun `priority — Constructor wins over Setter`() {
        val fake = fixture { klass("com.test.A") { method("calculate") } }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        val sources = listOf(
            InitSource.Setter("setRepo", T("com.test.Repo")),
            InitSource.Constructor(parameterName = "repo")
        )
        assertEquals(InitStrategy.CONSTRUCTOR,
            sel.choose(aField("repo", "com.test.Repo"), sources))
    }

    @Test
    fun `priority — branch 5 NON-safe init type falls through to branch 6`() {
        val fake = fixture { klass("com.test.A") { method("calculate") } }
        val target = aMethod("com.test.A", "calculate")
        val sel = selectorFor(fake, setOf("com.test.A"), target)
        // FieldInitializer non-safe + MethodInitializer public direct sans param
        // → branche 5 saute, branche 6a gagne avec CALL_PUBLIC.
        val publicInit = aMethod("com.test.A", "doInit", visibility = "public")
        val sources = listOf(
            InitSource.FieldInitializer("new MyHelper()", T("com.test.MyHelper")),
            InitSource.MethodInitializer(
                kind = MethodInitKind.ORDINARY, method = publicInit,
                visibility = "public", parametersRequired = emptyList(),
                hasNullGuard = false, externalCalls = emptyList(), assignsAlso = emptyList()
            )
        )
        assertEquals(InitStrategy.CALL_PUBLIC(publicInit),
            sel.choose(aField("h", "com.test.MyHelper"), sources))
    }
}

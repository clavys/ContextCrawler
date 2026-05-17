package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptContext
import com.contextextractor.core.prompt.meta.LayerKind
import com.contextextractor.core.prompt.stages.ContextRenderStage
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Verrous des 4 écarts étape 4 corrigés pour la V1.0 :
//   1. BLOC 2 — éléments structurels du corps (throws / exceptions / branches /
//      lambdas / sources non-déterministes) extraits, mappés et rendus.
//   2. BLOC 6d — un `new DTO()` du corps est crawlé en DATA_STRUCTURE même
//      s'il n'est ni champ, ni paramètre, ni type de retour.
//   3. # Instanciation du SUT — rend les vrais paramètres du constructeur.
//   4. Constructeurs Lombok — @RequiredArgsConstructor synthétisé (§8bis.6).
class Step4GapsTest {

    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val stage = ContextRenderStage()

    private fun render(fake: FakeIntrospector, sutFqn: String, methodName: String): String {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == methodName }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        val ctx = PromptContext(tree = mapper.map(result))
        stage.apply(ctx)
        return ctx.layers[LayerKind.CONTEXT] ?: error("layer CONTEXT absente")
    }

    // ── Point 1 — BLOC 2 : éléments structurels du corps ─────────────────────

    @Test
    fun `BLOC 2 structural elements are rendered in the target method section`() {
        val pkg = "com.test.step4.bloc2"
        val fake = fixture {
            klass("$pkg.OrderService", annotations = listOf("org.springframework.stereotype.Service")) {
                method(
                    "calculate",
                    returns = T("java.lang.String"),
                    declaredThrows = listOf("java.io.IOException")
                ) {
                    param("amount", T("int"))
                    throwsInBody("java.lang.IllegalArgumentException", "amount must be positive")
                    catchesInBody("java.io.IOException")
                    branch("IF", "amount < 0")
                    nonDeterministic("java.time.LocalDateTime.now")
                    expectsLambda("java.util.function.Function")
                }
            }
        }
        val output = render(fake, "$pkg.OrderService", "calculate")

        assertTrue(output.contains("- throws : java.io.IOException"),
            "le throws déclaré doit être rendu")
        assertTrue(
            output.contains(
                "- exceptions lancées dans le corps : " +
                    "java.lang.IllegalArgumentException (\"amount must be positive\")"
            ),
            "l'exception lancée avec son message constant doit être rendue"
        )
        assertTrue(output.contains("- exceptions catchées : java.io.IOException"),
            "le bloc catch doit être rendu")
        assertTrue(output.contains("- branches :") && output.contains("  - IF: amount < 0"),
            "la branche conditionnelle doit être rendue")
        assertTrue(output.contains("- sources non-déterministes : java.time.LocalDateTime.now"),
            "la source non-déterministe doit être rendue")
        assertTrue(output.contains("- lambdas attendues : java.util.function.Function"),
            "la lambda attendue doit être rendue")
    }

    @Test
    fun `BLOC 2 produces no empty bullets when the body analysis is empty`() {
        // FakeIntrospector sans analyse configurée ⇒ MethodBodyAnalysis() vide.
        // Le renderer ne doit produire AUCUNE puce structurelle parasite.
        val pkg = "com.test.step4.empty"
        val fake = fixture {
            klass("$pkg.OrderService", annotations = listOf("org.springframework.stereotype.Service")) {
                method("calculate", returns = T("void"))
            }
        }
        val output = render(fake, "$pkg.OrderService", "calculate")
        assertTrue(!output.contains("- exceptions lancées"),
            "aucune puce d'exception quand l'analyse du corps est vide")
        assertTrue(!output.contains("- branches :"),
            "aucune puce de branche quand l'analyse du corps est vide")
    }

    // ── Point 2 — BLOC 6d : instanciation crawlée en DATA_STRUCTURE ───────────

    @Test
    fun `BLOC 6d crawls a new DTO from the body into data structures`() {
        val pkg = "com.test.step4.bloc6d"
        val fake = fixture {
            // Receipt : record ⇒ classé DATA_STRUCTURE sans ambiguïté.
            klass("$pkg.Receipt", isRecord = true) {
                field("total", T("double"))
                method("<init>", returns = T("$pkg.Receipt")) {
                    param("total", T("double"))
                }
            }
            klass("$pkg.OrderService", annotations = listOf("org.springframework.stereotype.Service")) {
                // process() ne retourne pas Receipt, n'a pas de champ Receipt :
                // seul le `new Receipt(...)` du corps doit le faire apparaître.
                method("process", returns = T("void")) {
                    instantiates("$pkg.Receipt")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.OrderService")!!
        val target = fake.listMethodsOf("$pkg.OrderService").single { it.name == "process" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue(result.dataStructures.containsKey("$pkg.Receipt"),
            "le `new Receipt(...)` du corps doit être crawlé en DATA_STRUCTURE (BLOC 6d)")

        val output = render(fake, "$pkg.OrderService", "process")
        assertTrue(output.contains("# Structures de données à construire"),
            "section structures de données attendue")
        assertTrue(output.contains("$pkg.Receipt"),
            "Receipt doit figurer dans les structures à construire")
    }

    // ── Point 3 — # Instanciation du SUT : vrais paramètres du constructeur ──

    @Test
    fun `instantiation section renders the real constructor parameters`() {
        val pkg = "com.test.step4.ctor"
        val fake = fixture {
            klass("$pkg.Repo", isInterface = true) {
                method("findAll", returns = T("java.lang.String"))
            }
            klass("$pkg.OrderService", annotations = listOf("org.springframework.stereotype.Service")) {
                field("repo", T("$pkg.Repo"))
                method("<init>", returns = T("$pkg.OrderService")) {
                    param("repo", T("$pkg.Repo"))
                }
                method("calculate", returns = T("void"))
            }
        }
        val output = render(fake, "$pkg.OrderService", "calculate")
        assertTrue(
            output.contains("new $pkg.OrderService($pkg.Repo repo)"),
            "l'instanciation doit montrer le vrai paramètre du constructeur, " +
                "pas un `new SUT()` no-args"
        )
    }

    // ── Point 4 — Lombok @RequiredArgsConstructor synthétisé (§8bis.6) ───────

    @Test
    fun `Lombok RequiredArgsConstructor is synthesized from final fields`() {
        val pkg = "com.test.step4.lombok"
        val fake = fixture {
            klass("$pkg.Repo", isInterface = true) {
                method("findAll", returns = T("java.lang.String"))
            }
            // @RequiredArgsConstructor + aucun <init> visible (plugin Lombok off).
            klass(
                "$pkg.OrderService",
                annotations = listOf(
                    "org.springframework.stereotype.Service",
                    "lombok.RequiredArgsConstructor"
                )
            ) {
                field("repo", T("$pkg.Repo"), isFinal = true)
                field("label", T("java.lang.String"), isFinal = true)
                method("calculate", returns = T("void"))
            }
        }
        val sut = fake.resolveClass("$pkg.OrderService")!!
        val target = fake.listMethodsOf("$pkg.OrderService").single { it.name == "calculate" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val ctor = result.instantiationPlan.selectedConstructor
        assertEquals(listOf("repo", "label"), ctor.parameters.map { it.name },
            "le constructeur Lombok doit reprendre les champs final non initialisés")
        assertEquals("lombok.RequiredArgsConstructor", ctor.triggerAnnotation)

        val output = render(fake, "$pkg.OrderService", "calculate")
        assertTrue(
            output.contains("new $pkg.OrderService($pkg.Repo repo, java.lang.String label)"),
            "l'instanciation doit refléter le constructeur Lombok synthétisé"
        )
    }
}

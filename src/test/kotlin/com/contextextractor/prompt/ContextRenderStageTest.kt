package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptContext
import com.contextextractor.core.prompt.meta.LayerKind
import com.contextextractor.core.prompt.stages.ContextRenderStage
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.Fixtures
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4f-β — vérifie que ContextRenderStage produit la layer CONTEXT
// au format §6 :
//   • sections # Classe sous test, # Méthode cible, # Protocole d'init,
//     # Mocks, # Structures de données présentes ;
//   • UNTESTABLE_AS_IS produit le bloc TODO obligatoire (verrou §6 ligne
//     1108-1116) — c'est le test pivot demandé en 4f-β.
class ContextRenderStageTest {

    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val stage = ContextRenderStage()

    private fun render(fake: com.contextextractor.fakes.FakeIntrospector,
                       sutFqn: String, methodName: String): String {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == methodName }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        val tree = mapper.map(result)
        val ctx = PromptContext(tree = tree)
        stage.apply(ctx)
        return ctx.layers[LayerKind.CONTEXT]
            ?: error("ContextRenderStage doit peupler la layer CONTEXT")
    }

    // ── Cas nominal — case91 ─────────────────────────────────────────────────

    @Test
    fun `case91 — CONTEXT layer contains all major sections`() {
        val output = render(Fixtures.case91(), "com.testproject.case91.OrderService", "calculate")
        assertTrue(output.contains("# Class under test"), "section classe absente")
        assertTrue(output.contains("# Target method"), "section méthode cible absente")
        assertTrue(output.contains("# Field initialization protocol"),
            "section protocole d'init absente alors que cache nécessite CALL_POST_CONSTRUCT")
        assertTrue(output.contains("# Mocks"), "section mocks absente")
        assertTrue(output.contains("# Data structures to construct"), "section DTOs absente")
    }

    @Test
    fun `case91 — CALL_POST_CONSTRUCT renders sut_init() with PostConstruct comment`() {
        val output = render(Fixtures.case91(), "com.testproject.case91.OrderService", "calculate")
        assertTrue(output.contains("## Field `cache`: com.testproject.case91.DiscountCache"),
            "en-tête de champ cache au format §6 attendu")
        assertTrue(output.contains("Strategy: CALL_POST_CONSTRUCT"))
        // Variable name dérivé du SUT (OrderService → orderService).
        assertTrue(output.contains("orderService.init();"),
            "appel orderService.init() attendu pour CALL_POST_CONSTRUCT")
        assertTrue(output.contains("@PostConstruct"), "commentaire @PostConstruct attendu")
    }

    @Test
    fun `case91 — repository is implicit (MOCKITO_INJECT_MOCKS) so no init block`() {
        val output = render(Fixtures.case91(), "com.testproject.case91.OrderService", "calculate")
        // §6 ligne 1069 : on n'écrit PAS de bloc init pour MOCKITO_INJECT_MOCKS.
        assertFalse(output.contains("## Field `repository`"),
            "repository @Autowired ne doit PAS apparaître dans le protocole d'init")
    }

    // ── Pivot UNTESTABLE_AS_IS — verrou §6 ligne 1108-1116 ───────────────────

    @Test
    fun `UNTESTABLE field renders the mandatory TODO block with reason and refactor hints`() {
        // Champ `cache` assigné par méthode privée orpheline → branche 11.
        val pkg = "com.test.untestable_renderer"
        val fake = fixture {
            klass("$pkg.SUT") {
                field("cache", T("java.util.Map"))
                method("calculate") {
                    reads("$pkg.SUT", "cache")
                }
                method("helper", visibility = "private") {
                    assigns("$pkg.SUT", "cache", rhsExpression = "new HashMap<>()")
                }
            }
        }
        val output = render(fake, "$pkg.SUT", "calculate")

        // Stratégie nommée explicitement (verrou label, pas simpleName).
        assertTrue(output.contains("Strategy: UNTESTABLE_AS_IS"),
            "label UNTESTABLE_AS_IS attendu dans le rendu")

        // Bloc TODO §6 lignes 1108-1116 — chaque ligne est un verrou.
        assertTrue(output.contains("// STOP: this field cannot be initialized without refactoring."),
            "ligne d'arrêt obligatoire absente")
        assertTrue(output.contains("// Reason:"), "ligne de raison absente")
        assertTrue(output.contains("// Hints:"), "ligne de pistes absente")
        assertTrue(output.contains("@Test"), "annotation @Test du bloc TODO absente")
        assertTrue(output.contains("void cache_TODO_untestable()"),
            "signature de la méthode test marquée TODO absente")
        assertTrue(output.contains("fail("),
            "appel à fail(...) dans le corps du test TODO absent")

        // Au moins une piste de refactor doit être mentionnée (par défaut le
        // sélecteur en propose 2 : ajouter ctor / ajouter setter).
        val hintsBlock = output.substringAfter("// Hints:").substringBefore("// Generate")
        assertTrue(hintsBlock.contains("//   - "),
            "au moins une piste indentée attendue sous '// Hints:'")
    }

    // ── R3-A — Enum values rendues sous "Values: A, B, C" ──────────────────

    @Test
    fun `enum DTO renders Values line listing the constants`() {
        // Astrea case 4.1 R3 : LLM hallucinait `OrdreTriEnum.ASC` parce que
        // le prompt n'exposait pas les constantes. Verrou : tout DTO classé
        // ENUM doit afficher `Values: ...` sous son en-tête.
        val pkg = "com.test.enum_values"
        val fake = fixture {
            klass("$pkg.OrdreTri",
                isEnum = true,
                enumValues = listOf("ASCENDANT", "DESCENDANT"))
            klass("$pkg.SUT") {
                method("trier") {
                    param("ordre", T("$pkg.OrdreTri"))
                }
            }
        }
        val output = render(fake, "$pkg.SUT", "trier")
        assertTrue(output.contains("## $pkg.OrdreTri [ENUM]"),
            "en-tête ENUM attendu pour le DTO")
        assertTrue(output.contains("Values: ASCENDANT, DESCENDANT"),
            "ligne 'Values: ASCENDANT, DESCENDANT' attendue pour court-circuiter " +
                "l'hallucination par le LLM (Astrea R3-A)")
    }

    @Test
    fun `non-enum DTO does NOT render Values line`() {
        // Garde-fou : la ligne Values ne doit apparaître que pour ENUM,
        // jamais pour SETTER_BASED / CONSTRUCTOR / RECORD / etc.
        val output = render(Fixtures.case91(),
            "com.testproject.case91.OrderService", "calculate")
        assertFalse(output.contains("Values:"),
            "case91 ne contient aucun ENUM — la ligne Values: ne doit pas apparaître")
    }

    // ── Verrou diagnostic non-testable agrégé ────────────────────────────────

    @Test
    fun `UNTESTABLE field still produces sections — never silent omission`() {
        val pkg = "com.test.silent_check"
        val fake = fixture {
            klass("$pkg.SUT") {
                field("cache", T("java.util.Map"))
                method("calculate") {
                    reads("$pkg.SUT", "cache")
                }
                method("helper", visibility = "private") {
                    assigns("$pkg.SUT", "cache", rhsExpression = "new HashMap<>()")
                }
            }
        }
        val output = render(fake, "$pkg.SUT", "calculate")
        // Le champ DOIT figurer — c'est le piège qu'évite le verrou §6.
        assertTrue(output.contains("## Field `cache`"),
            "un champ UNTESTABLE doit apparaître dans le rendu, jamais omis")
        assertNotNull(output.lines().find { it.startsWith("# Field initialization protocol") },
            "section protocole d'init obligatoire dès qu'au moins un champ visible")
    }
}

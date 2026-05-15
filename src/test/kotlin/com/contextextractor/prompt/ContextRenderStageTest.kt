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
        assertTrue(output.contains("# Classe sous test"), "section classe absente")
        assertTrue(output.contains("# Méthode cible"), "section méthode cible absente")
        assertTrue(output.contains("# Protocole d'initialisation des champs"),
            "section protocole d'init absente alors que cache nécessite CALL_POST_CONSTRUCT")
        assertTrue(output.contains("# Mocks"), "section mocks absente")
        assertTrue(output.contains("# Structures de données à construire"), "section DTOs absente")
    }

    @Test
    fun `case91 — CALL_POST_CONSTRUCT renders sut_init() with PostConstruct comment`() {
        val output = render(Fixtures.case91(), "com.testproject.case91.OrderService", "calculate")
        assertTrue(output.contains("## Champ `cache` : com.testproject.case91.DiscountCache"),
            "en-tête de champ cache au format §6 attendu")
        assertTrue(output.contains("Stratégie : CALL_POST_CONSTRUCT"))
        assertTrue(output.contains("sut.init();"), "appel sut.init() attendu pour CALL_POST_CONSTRUCT")
        assertTrue(output.contains("@PostConstruct"), "commentaire @PostConstruct attendu")
    }

    @Test
    fun `case91 — repository is implicit (MOCKITO_INJECT_MOCKS) so no init block`() {
        val output = render(Fixtures.case91(), "com.testproject.case91.OrderService", "calculate")
        // §6 ligne 1069 : on n'écrit PAS de bloc init pour MOCKITO_INJECT_MOCKS.
        assertFalse(output.contains("## Champ `repository`"),
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
        assertTrue(output.contains("Stratégie : UNTESTABLE_AS_IS"),
            "label UNTESTABLE_AS_IS attendu dans le rendu")

        // Bloc TODO §6 lignes 1108-1116 — chaque ligne est un verrou.
        assertTrue(output.contains("// STOP : ce champ ne peut pas être initialisé sans refactor."),
            "ligne d'arrêt obligatoire absente")
        assertTrue(output.contains("// Raison :"), "ligne de raison absente")
        assertTrue(output.contains("// Pistes :"), "ligne de pistes absente")
        assertTrue(output.contains("@Test"), "annotation @Test du bloc TODO absente")
        assertTrue(output.contains("void cache_TODO_untestable()"),
            "signature de la méthode test marquée TODO absente")
        assertTrue(output.contains("fail("),
            "appel à fail(...) dans le corps du test TODO absent")

        // Au moins une piste de refactor doit être mentionnée (par défaut le
        // sélecteur en propose 2 : ajouter ctor / ajouter setter).
        val pistesBlock = output.substringAfter("// Pistes :").substringBefore("// Génère")
        assertTrue(pistesBlock.contains("//   - "),
            "au moins une piste indentée attendue sous '// Pistes :'")
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
        assertTrue(output.contains("## Champ `cache`"),
            "un champ UNTESTABLE doit apparaître dans le rendu, jamais omis")
        assertNotNull(output.lines().find { it.startsWith("# Protocole d'initialisation des champs") },
            "section protocole d'init obligatoire dès qu'au moins un champ visible")
    }
}

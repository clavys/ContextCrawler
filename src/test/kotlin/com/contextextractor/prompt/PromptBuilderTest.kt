package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptBuilder
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.Fixtures
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 5-β — tests end-to-end du pipeline complet
// (RecursiveDeepStrategy → ContextResultTreeMapper → PromptBuilder.defaultPipeline).
//
// **Périmètre** :
//   • case91 : flux nominal CALL_POST_CONSTRUCT — prompt complet avec toutes
//     les sections, placeholders substitués, layer CONTEXT cohérente.
//   • case92 : verrou explicite sur `# Instanciation du SUT` — case92 a
//     `pricingGateway` (@Autowired, mocké) et `config` (init via configure())
//     mais AUCUN constructeur explicite. La sortie attendue est `new
//     OrderService()` — c'est correct, pas un gap. L'assertion documente
//     ce choix : si un fixture futur ajoute un ctor avec deps, ce test
//     échouera et forcera la mise à jour de ContextRenderStage avec
//     l'instantiationPlan.
//   • case93 : chaîne transitive (CALL_PUBLIC_TRANSITIVE) — verrou que le
//     chemin BFS apparaît dans le rendu.
//   • case95 : UNTESTABLE-SUT — court-circuit `STOP-UNTESTABLE`, pas de
//     prompt normal. Verrou pivot demandé par PROMPT_FORMAT.md §"Special
//     case — UNTESTABLE_AS_IS at SUT level".
class PromptBuilderTest {

    private val builder = PromptBuilder.defaultPipeline()

    // Pipeline complet : strategy.extractCore → mapper.map → builder.build.
    private fun buildFor(fake: FakeIntrospector, sutFqn: String, methodName: String): String {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == methodName }
        val result = RecursiveDeepStrategy().extractCore(
            fake, DefaultClassifier(), StrategyConfig(), sut, target
        )
        val tree = ContextResultTreeMapper().map(result)
        return builder.build(tree)
    }

    // ── case91 : flux nominal complet ────────────────────────────────────────

    @Test
    fun `case91 produces a fully assembled prompt with all section markers`() {
        val output = buildFor(Fixtures.case91(),
            "com.testproject.case91.OrderService", "calculate")

        // SYSTEM (PROMPT_FORMAT.md §"Section 1") — pas de marker, juste le contenu.
        assertTrue(output.contains("expert Java unit test writer"),
            "section SYSTEM par défaut absente")

        // Marqueurs de séparation produits par LayerCompositionStage.
        assertTrue(output.contains("=== CONTEXT ==="), "marker === CONTEXT === absent")
        assertTrue(output.contains("=== CONSTRAINTS ==="), "marker === CONSTRAINTS === absent")
        assertTrue(output.contains("=== INSTRUCTION ==="), "marker === INSTRUCTION === absent")

        // Placeholders substitués dans CONSTRAINTS + INSTRUCTION.
        assertTrue(output.contains("Class name: OrderServiceTest"),
            "[TargetClassName] non substitué")
        assertTrue(output.contains("Package: com.testproject.case91"),
            "[same package as target class] non substitué")
        assertTrue(output.contains("Cover the nominal path of calculate"),
            "[targetMethodName] non substitué dans CONSTRAINTS")
        assertTrue(output.contains("Generate the complete Java test class for method calculate"),
            "[targetMethodName] non substitué dans INSTRUCTION")
    }

    @Test
    fun `case91 CONTEXT layer carries the CALL_POST_CONSTRUCT init block`() {
        val output = buildFor(Fixtures.case91(),
            "com.testproject.case91.OrderService", "calculate")

        // §6 : ## Champ pour cache + stratégie + appel sut.init() avec
        // commentaire @PostConstruct (ContextRenderStage).
        assertTrue(output.contains("## Champ `cache`"),
            "section champ cache absente du rendu")
        assertTrue(output.contains("Stratégie : CALL_POST_CONSTRUCT"))
        assertTrue(output.contains("sut.init();"), "appel sut.init() attendu")
    }

    @Test
    fun `case91 truncated branch is NOT injected when extraction is complete`() {
        val output = buildFor(Fixtures.case91(),
            "com.testproject.case91.OrderService", "calculate")
        // Le bloc {IF tree.tronque} doit être supprimé entièrement.
        assertFalse(output.contains("WARNING: extracted context is partial"),
            "le bloc tronqué ne doit pas apparaître quand truncated=false")
        assertFalse(output.contains("{IF "), "marker {IF ...} ne doit jamais fuiter")
        assertFalse(output.contains("{END IF}"), "marker {END IF} ne doit jamais fuiter")
    }

    // ── case92 : verrou explicite sur l'instanciation du SUT ─────────────────

    @Test
    fun `case92 instantiation line shows new OrderService() — assumed V1 limit`() {
        // Verrou explicite demandé : ContextRenderStage rend l'instanciation
        // avec un ctor no-args. La fixture case92 ne déclare PAS de
        // constructeur explicite sur OrderService — donc `new OrderService()`
        // est CORRECT (pas un gap). Si une fixture future ajoute un ctor
        // avec dépendances, ce test cassera et forcera la mise à jour de
        // ContextRenderStage pour lire `instantiationPlan.selectedConstructor`.
        val output = buildFor(Fixtures.case92(),
            "com.testproject.case92.OrderService", "calculate")
        assertTrue(output.contains("# Instanciation du SUT"),
            "section instanciation absente du rendu")
        assertTrue(output.contains("new com.testproject.case92.OrderService()"),
            "instanciation no-args attendue (case92 SUT n'a pas de ctor avec deps)")
    }

    @Test
    fun `case92 renders CALL_PUBLIC_WITH_ARGS for the config field`() {
        val output = buildFor(Fixtures.case92(),
            "com.testproject.case92.OrderService", "calculate")
        // BLOC 7 : configure(int, java.lang.String) → CALL_PUBLIC_WITH_ARGS.
        assertTrue(output.contains("## Champ `config`"),
            "section champ config absente du rendu case92")
        assertTrue(output.contains("Stratégie : CALL_PUBLIC_WITH_ARGS"),
            "stratégie CALL_PUBLIC_WITH_ARGS attendue pour case92")
        assertTrue(output.contains("sut.configure("),
            "appel sut.configure(...) attendu pour initialiser config")
    }

    // ── case93 : chaîne transitive ───────────────────────────────────────────

    @Test
    fun `case93 transitive chain renders the BFS callChain`() {
        val output = buildFor(Fixtures.case93(),
            "com.testproject.case93.OrderService", "calculate")
        assertTrue(output.contains("## Champ `cache`"),
            "section champ cache absente du rendu case93")
        assertTrue(output.contains("Stratégie : CALL_PUBLIC_TRANSITIVE"),
            "stratégie CALL_PUBLIC_TRANSITIVE attendue pour case93")
        // La chaîne doit apparaître textuellement (séparateur ' → ').
        assertTrue(output.contains(" → "),
            "le séparateur de callChain doit apparaître pour case93")
    }

    @Test
    fun `case93 callChain is rendered in runtime order — entry point first, assignment site last`() {
        // Verrou EXPECTED_PROMPTS.md case93 : la chaîne lisible par le LLM
        // suit l'ordre d'invocation runtime — `start → startInternal → warmup`,
        // PAS l'ordre BFS interne `warmup → startInternal → start`.
        // Étape 7 fix #2 : reverse à la projection metadata.
        val output = buildFor(Fixtures.case93(),
            "com.testproject.case93.OrderService", "calculate")

        // Verrou direct : la chaîne complète dans l'ordre runtime attendu,
        // avec extension downstream (#3) jusqu'à `buildCache`.
        assertTrue(output.contains("start → startInternal → warmup → buildCache"),
            "la chaîne complète doit être rendue dans l'ordre runtime " +
                "(entry point → ... → assignmentSite → callees downstream)")

        // Verrou inverse : l'ordre BFS NE doit PAS apparaître dans le prompt.
        assertFalse(output.contains("warmup → startInternal → start"),
            "l'ordre BFS interne (warmup → startInternal → start) ne doit " +
                "pas fuiter au rendu utilisateur")
    }

    @Test
    fun `case93 captures loader_load() as a stub — verrou step 7 fix 3`() {
        // EXPECTED_PROMPTS.md case93 : « Le code suggéré contient un
        // when(loader.load()).thenReturn(...) AVANT sut.start(); »
        // L'appel `loader.load()` vit dans `buildCache()` (downstream du
        // assignment site warmup). Sans la traversée downstream étape 7 #3,
        // ce stub était invisible.
        val output = buildFor(Fixtures.case93(),
            "com.testproject.case93.OrderService", "calculate")

        // Verrou texte dans la section "Stubs requis avant l'appel".
        assertTrue(output.contains("Stubs requis avant l'appel"),
            "section 'Stubs requis avant l'appel' attendue (downstream produit l'appel externe)")
        assertTrue(output.contains("Loader") && output.contains("load"),
            "le stub doit mentionner Loader et load (call externe atteint via buildCache)")
    }

    @Test
    fun `case93 lists internal helpers in Sous-methodes internes`() {
        // Verrou EXPECTED_PROMPTS.md case93 : « # Sous-méthodes internes liste
        // startInternal, warmup, buildCache ». Étape 7 #3 — synthèse downstream
        // ajoute buildCache à internalLogics. startInternal/warmup arrivent via
        // BLOC 6 (le callGraph les visite déjà). buildCache était l'écart.
        val output = buildFor(Fixtures.case93(),
            "com.testproject.case93.OrderService", "calculate")

        assertTrue(output.contains("# Sous-méthodes internes"),
            "section '# Sous-méthodes internes' attendue dès qu'au moins une " +
                "méthode intra-SUT est référencée par le protocole d'init")
        assertTrue(output.contains("buildCache"),
            "buildCache doit figurer parmi les sous-méthodes internes (callee " +
                "downstream de warmup, ajouté par l'enrichissement étape 7 #3)")
    }

    // ── Verrous body-only — corps source des méthodes intra-SUT ──────────────
    //
    // STRATEGIE.md §3.1 (SUT_BOOTSTRAP) et §3.2 (INTERNAL_LOGIC) : le corps des
    // méthodes intra-SUT EST capturé et rendu. §3.3 (MOCK_EXTERNAL) ligne « STOP :
    // ne jamais lire le corps des méthodes externes » : verrou de frontière —
    // aucun corps de méthode externe ne doit fuiter dans le prompt.

    @Test
    fun `case92 target method body is rendered in CONTEXT layer`() {
        val output = buildFor(Fixtures.case92(),
            "com.testproject.case92.OrderService", "calculate")

        // §6 — bloc « Code source : ```java …``` » sous la signature cible.
        assertTrue(output.contains("Code source :"),
            "bloc 'Code source :' attendu pour la méthode cible (§6)")
        // Fragments caractéristiques du body case92.calculate (cf Fixtures.kt).
        // Verrouille que le LLM voit la logique réelle, pas seulement la signature.
        assertTrue(output.contains("if (request == null)"),
            "le corps de calculate doit contenir le guard if (request == null)")
        assertTrue(output.contains("throw new IllegalArgumentException"),
            "le corps de calculate doit contenir le throw IllegalArgumentException")
        assertTrue(output.contains("config.apply(request.getRawAmount())"),
            "le corps de calculate doit contenir l'appel config.apply(...)")
    }

    @Test
    fun `case93 internal method bodies are rendered in Sous-methodes internes`() {
        val output = buildFor(Fixtures.case93(),
            "com.testproject.case93.OrderService", "calculate")

        // Le body de buildCache contient `loader.load()` ET `new Cache(entries)`
        // (cf Fixtures.kt). Cet appel est UNIQUE au body — il ne figure dans
        // aucun callSummary ou trace BLOC 6 sous cette forme exacte. C'est donc
        // le marqueur le plus fiable pour vérifier que le body texte fuit bien
        // jusqu'au rendu.
        assertTrue(output.contains("List<CacheEntry> entries = loader.load();"),
            "le corps de buildCache doit apparaître intégralement (étape body-only §3.2)")
        assertTrue(output.contains("return new Cache(entries);"),
            "le corps de buildCache doit contenir le return new Cache(entries)")
    }

    @Test
    fun `case92 Mocks section never leaks external method bodies — STRATEGIE §3,3`() {
        // Verrou de frontière : §3.3 ligne « STOP : ne jamais lire le corps des
        // méthodes externes ». La section # Mocks liste les signatures des
        // méthodes appelées sur les mocks (PricingGateway.fetchRate) mais NE
        // DOIT JAMAIS rendre un bloc ```java de leur implémentation.
        // Empêche une future généralisation utile (« body partout ») de
        // contaminer silencieusement la frontière externe.
        val output = buildFor(Fixtures.case92(),
            "com.testproject.case92.OrderService", "calculate")

        val mocksStart = output.indexOf("# Mocks")
        assertTrue(mocksStart >= 0, "section # Mocks attendue pour case92")
        val nextSection = output.indexOf("\n# ", mocksStart + 1)
        val mocksEnd = if (nextSection >= 0) nextSection else output.length
        val mocksSection = output.substring(mocksStart, mocksEnd)

        assertFalse(mocksSection.contains("Code source :"),
            "la section # Mocks ne doit JAMAIS contenir 'Code source :' — " +
                "§3.3 interdit la lecture du corps des méthodes externes")
        assertFalse(mocksSection.contains("```java"),
            "la section # Mocks ne doit jamais ouvrir un bloc ```java " +
                "(frontière MOCK_EXTERNAL fermée par construction)")
    }

    // ── Pivot UNTESTABLE-SUT — verrou PROMPT_FORMAT.md §"Special case" ───────

    @Test
    fun `case95 short-circuits to STOP-UNTESTABLE block — no normal prompt`() {
        val output = buildFor(Fixtures.case95(),
            "com.testproject.case95.OrderService", "calculate")

        // Sentinelle parsable en tête : l'IDE doit pouvoir détecter sans
        // inspecter le contenu.
        assertTrue(output.startsWith("STOP-UNTESTABLE"),
            "le préfixe sentinelle STOP-UNTESTABLE doit ouvrir la sortie")

        // Bloc d'alerte humain (PROMPT_FORMAT.md §"Special case").
        assertTrue(output.contains("⚠️ Cette méthode ne peut pas être testée automatiquement."),
            "ligne d'alerte ⚠️ obligatoire")
        assertTrue(output.contains("Champs bloquants :"),
            "section 'Champs bloquants' obligatoire")
        assertTrue(output.contains("cache"),
            "le champ bloquant 'cache' doit être listé")
        assertTrue(output.contains("Raisons :"),
            "section 'Raisons' obligatoire")
        assertTrue(output.contains("Pistes de refactoring :"),
            "section 'Pistes de refactoring' obligatoire")

        // VERROU CRITIQUE : PAS de prompt normal en sortie. Le LLM ne doit
        // JAMAIS recevoir un contexte ambigu avec des sections vides.
        assertFalse(output.contains("=== CONTEXT ==="),
            "PAS de section CONTEXT en sortie SUT-untestable (sinon ambigu pour le LLM)")
        assertFalse(output.contains("=== CONSTRAINTS ==="),
            "PAS de section CONSTRAINTS en sortie SUT-untestable")
        assertFalse(output.contains("=== INSTRUCTION ==="),
            "PAS de section INSTRUCTION en sortie SUT-untestable")
        assertFalse(output.contains("expert Java unit test writer"),
            "PAS de SYSTEM par défaut en sortie SUT-untestable")
    }

    @Test
    fun `case95 untestable diagnostic lists at least one refactor hint`() {
        val output = buildFor(Fixtures.case95(),
            "com.testproject.case95.OrderService", "calculate")
        // Au moins une piste indentée — la stratégie en propose 2 par défaut
        // (ajouter setter, exposer méthode publique). Le test ne fixe pas
        // le wording exact pour éviter le couplage au texte des hints.
        val pistesBlock = output.substringAfter("Pistes de refactoring :")
        assertTrue(pistesBlock.contains("  - "),
            "au moins une piste indentée ('  - ') attendue sous 'Pistes de refactoring :'")
    }
}

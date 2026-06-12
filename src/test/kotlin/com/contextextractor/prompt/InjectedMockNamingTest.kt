package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptBuilder
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4.1 Fix D — le @Mock d'un champ MOCKITO_INJECT_MOCKS doit porter le NOM
// DU CHAMP, pas le nom dérivé du type.
//
// Vrai bug Astrea case 4.1 (2e itération, après Fix A/B/C) :
//   - `# Mocks` rendait `@Mock private IHMDTO iHMDTO;` (nom dérivé du type)
//     et « Methods to stub on `iHMDTO`: ... getNombreMaxOccurrencesRecherchees »
//   - `# Inherited fields requiring @Mock by name` demandait en parallèle
//     `@Mock IHMDTO structurePage;` (nom du champ, requis pour l'injection)
//   - Le LLM, obéissant aux DEUX instructions, déclarait les deux mocks et
//     stubbait `iHMDTO` — l'instance jamais injectée. Le mock réellement
//     injecté (`structurePage`) restait non stubbé → getter retourne null →
//     unboxing int → NPE à la première ligne de `rechercher`.
//
// Fix D (3 volets) :
//   1. renderMocks nomme le mock d'après le champ injecté (map type→nom,
//      restreinte aux types portés par exactement un champ).
//   2. Le hint inherited verrouille l'unicité (« Do NOT declare a SECOND mock »).
//   3. BaseConstraints : EXCEPTION à la règle de nommage type-derived.
class InjectedMockNamingTest {

    private val builder = PromptBuilder.defaultPipeline()
    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val pkg = "com.test.fixd"

    private fun buildFor(fake: FakeIntrospector,
                         sutFqn: String, methodName: String): String {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == methodName }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        return builder.build(mapper.map(result))
    }

    private fun inheritedScenario(): String {
        val fake = fixture {
            klass("$pkg.IHMDTO") {
                method("getNombreMax", returns = T("int"))
            }
            klass("$pkg.Parent") {
                field(
                    name = "structurePage",
                    type = T("$pkg.IHMDTO"),
                    visibility = "protected"
                )
            }
            klass("$pkg.SUT", superFqn = "$pkg.Parent") {
                method(
                    "rechercher",
                    returns = T("int"),
                    body = "return structurePage.getNombreMax();"
                ) {
                    reads("$pkg.Parent", "structurePage")
                    calls("$pkg.IHMDTO", "getNombreMax")
                }
            }
            superChain("$pkg.SUT", "$pkg.Parent")
        }
        return buildFor(fake, "$pkg.SUT", "rechercher")
    }

    @Test
    fun `Fix D — Mocks block declares the injected field name not the type-derived name`() {
        val output = inheritedScenario()
        assertTrue(output.contains("private $pkg.IHMDTO structurePage;"),
            "Fix D : la déclaration VERBATIM doit porter le nom du champ injecté " +
                "(structurePage). Output Mocks:\n" +
                output.substringAfter("# Mocks (annotate", "").take(500))
        assertFalse(output.contains("iHMDTO"),
            "Fix D : le nom dérivé du type (iHMDTO) ne doit apparaître NULLE PART — " +
                "c'est lui que le LLM stubbait à la place du mock injecté.")
    }

    @Test
    fun `Fix D — Methods to stub anchors the injected field name`() {
        val output = inheritedScenario()
        assertTrue(output.contains("Methods to stub on `structurePage`:"),
            "Fix D : le header Methods to stub doit ancrer le nom du champ. Output:\n" +
                output.substringAfter("# Mocks (annotate", "").take(500))
        assertTrue(output.contains("- structurePage.getNombreMax():int"),
            "Fix D : chaque ligne de stub doit être préfixée par le nom du champ " +
                "(Bug BB + Fix D combinés).")
    }

    @Test
    fun `Fix D — inherited hint forbids declaring a second mock of the same type`() {
        val output = inheritedScenario()
        assertTrue(output.contains("# Inherited fields requiring @Mock by name"),
            "le hint inherited doit toujours être présent")
        assertTrue(output.contains("Do NOT declare a SECOND mock of the same type"),
            "Fix D : verrou d'unicité attendu dans le hint — le LLM déclarait " +
                "les deux mocks « par prudence ».")
    }

    @Test
    fun `Fix D — Autowired field with custom name also drives the mock name`() {
        // Fix D ne se limite pas aux champs hérités : un champ @Autowired dont
        // le nom diffère du nom dérivé du type doit aussi imposer son nom
        // (injection par nom = robuste même avec plusieurs champs du même type).
        val fake = fixture {
            klass("$pkg.Svc", isInterface = true) {
                method("doIt", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl") {
                field("monService", T("$pkg.Svc"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.monService.doIt();") {
                    reads("$pkg.Ctrl", "monService")
                    calls("$pkg.Svc", "doIt")
                }
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl", "handle")
        assertTrue(output.contains("private $pkg.Svc monService;"),
            "Fix D : le mock doit porter le nom du champ @Autowired (monService), " +
                "pas `svc`. Output Mocks:\n" +
                output.substringAfter("# Mocks (annotate", "").take(500))
        assertTrue(output.contains("Methods to stub on `monService`:"),
            "Fix D : Methods to stub ancré sur le nom du champ @Autowired.")
    }

    @Test
    fun `Fix D — two injected fields of the same type fall back to type-derived name`() {
        // Garde-fou : quand DEUX champs injectés partagent le même type, la
        // désambiguïsation par nom est ambiguë côté renderer (quel champ pour
        // le mock unique ?) — on garde le nom dérivé du type, comportement V1.4.
        val fake = fixture {
            klass("$pkg.Repo", isInterface = true) {
                method("load", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl2") {
                field("primaryRepo", T("$pkg.Repo"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("backupRepo", T("$pkg.Repo"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.primaryRepo.load() + this.backupRepo.load();") {
                    reads("$pkg.Ctrl2", "primaryRepo")
                    reads("$pkg.Ctrl2", "backupRepo")
                    calls("$pkg.Repo", "load")
                }
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl2", "handle")
        assertTrue(output.contains("private $pkg.Repo repo;"),
            "Fix D : type ambigu (2 champs) → fallback nom dérivé du type. " +
                "Output Mocks:\n" +
                output.substringAfter("# Mocks (annotate", "").take(500))
    }

    @Test
    fun `Fix D — BaseConstraints contains the naming EXCEPTION rule`() {
        // Verrou structurel : sans l'exception, la règle « TypeName → typeName »
        // contredit le nom prescrit par # Mocks et pousse le LLM à déclarer
        // un doublon type-derived.
        val baseText = com.contextextractor.core.prompt.constraints.BaseConstraints.TEXT
        assertTrue(baseText.contains("EXCEPTION (V1.4.1)"),
            "exception V1.4.1 attendue dans la règle de nommage")
        assertTrue(baseText.contains("SECOND mock of the same type under the type-derived name"),
            "interdiction du doublon type-derived attendue")
    }
}

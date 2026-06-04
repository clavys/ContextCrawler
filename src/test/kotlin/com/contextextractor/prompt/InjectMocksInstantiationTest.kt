package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptBuilder
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.Fixtures
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bug V — Le LLM doit utiliser @InjectMocks quand SUT a des champs @Autowired,
// PAS `new SUT()` + setters inventés. Cas concret prod (redirigerVersDetailsDeltaVec) :
// LLM a généré `controleur = spy(new SUT()); controleur.setSupervisionDeltaVecService(...)`
// — `setSupervisionDeltaVecService` n'existe pas → erreur de compilation.
//
// Avec Bug V : la section instantiation rend explicitement le pattern
// @InjectMocks, le LLM s'aligne. Combinaison spy + @InjectMocks :
//   @InjectMocks private SUT sut;
//   @BeforeEach { sut = spy(sut); doAnswer(...).when(sut).redirige(...); }
class InjectMocksInstantiationTest {

    private val builder = PromptBuilder.defaultPipeline()
    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val pkg = "com.test.bugv"

    private fun buildFor(fake: FakeIntrospector,
                         sutFqn: String, methodName: String): String {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == methodName }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        return builder.build(mapper.map(result))
    }

    @Test
    fun `Bug V — SUT with @Autowired field renders @InjectMocks instantiation`() {
        val fake = fixture {
            klass("$pkg.Svc", isInterface = true) {
                method("doIt", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl") {
                field("svc", T("$pkg.Svc"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.svc.doIt();") {
                    reads("$pkg.Ctrl", "svc")
                    calls("$pkg.Svc", "doIt")
                }
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl", "handle")

        assertTrue(output.contains("@InjectMocks"),
            "Bug V : @InjectMocks attendu. Output:\n$output")
        assertTrue(output.contains("private $pkg.Ctrl ctrl;"),
            "Bug V : déclaration champ typée avec varName camelCase. Output:\n$output")
        // L'ancien `new SUT()` ne doit plus apparaître dans la section instantiation.
        val instSection = output.substringAfter("# Class under test instantiation", "")
            .substringBefore("# ", "")
        assertFalse(instSection.contains("new $pkg.Ctrl()"),
            "Bug V : ancien `new Ctrl()` ne doit plus figurer dans la section instantiation " +
                "quand @Autowired présent. Section:\n$instSection")
    }

    @Test
    fun `Bug V — SUT without @Autowired keeps literal new() instantiation`() {
        // Vérification inverse : SUT sans champ injecté = pas de besoin de
        // @InjectMocks → on garde `new SUT()` historique.
        val fake = fixture {
            klass("$pkg.NoDeps") {
                method("compute", returns = T("int"),
                    body = "return 42;")
            }
        }
        val output = buildFor(fake, "$pkg.NoDeps", "compute")

        // Pas de @Autowired → instanciation explicite légitime.
        assertTrue(output.contains("new $pkg.NoDeps()"),
            "SUT sans dépendance @Autowired garde `new NoDeps()`. Output:\n$output")
        // @InjectMocks figure toujours dans CONSTRAINTS comme convention générale ;
        // ce qu'on vérifie c'est qu'il N'apparaît PAS dans la section instantiation
        // (pattern actif suggéré au LLM pour CE SUT précis).
        val instSection = output.substringAfter("# Class under test instantiation", "")
            .substringBefore("# ", "")
        assertFalse(instSection.contains("@InjectMocks"),
            "@InjectMocks NE doit PAS apparaître dans la section instantiation " +
                "quand aucun champ @Autowired. Section:\n$instSection")
    }

    @Test
    fun `Bug V — spy pattern works on @InjectMocks built instance`() {
        // Cas concret prod redirigerVersDetailsDeltaVec : @Autowired + spy.
        // Le LLM doit voir :
        //   1. @InjectMocks private Ctrl ctrl;
        //   2. dans @BeforeEach : ctrl = spy(ctrl); doAnswer(...).when(ctrl).redirige(...)
        val fake = fixture {
            klass("$pkg.Page", isInterface = true)
            klass("$pkg.BaseCtrl") {
                method("redirige", returns = T("void")) {
                    param("p", T("$pkg.Page"))
                    calls("javax.faces.context.FacesContext", "getCurrentInstance")
                }
            }
            klass("$pkg.Svc2", isInterface = true) {
                method("doIt", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl2", superFqn = "$pkg.BaseCtrl") {
                field("svc", T("$pkg.Svc2"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("void"),
                    body = "this.svc.doIt(); this.redirige(new Page());") {
                    reads("$pkg.Ctrl2", "svc")
                    calls("$pkg.Svc2", "doIt")
                    calls("$pkg.BaseCtrl", "redirige", "$pkg.Page")
                    instantiates("$pkg.Page")
                }
            }
            superChain("$pkg.Ctrl2", "$pkg.BaseCtrl")
        }
        val output = buildFor(fake, "$pkg.Ctrl2", "handle")

        // @InjectMocks pour la SUT.
        assertTrue(output.contains("@InjectMocks"),
            "Bug V : @InjectMocks pour SUT avec @Autowired. Output:\n$output")
        // Pattern spy s'enchaîne sur la variable typeName.
        assertTrue(output.contains("ctrl2 = spy(ctrl2);"),
            "Bug V : pattern spy wrap l'instance @InjectMocks. Output:\n$output")
    }

    @Test
    fun `Bug V — Case91 OrderService also gets @InjectMocks pattern`() {
        // Garde-fou de cohérence : Case91 a aussi des @Autowired
        // (DiscountRepository) → doit appliquer le pattern.
        val output = buildFor(Fixtures.case91(),
            "com.testproject.case91.OrderService", "calculate")
        assertTrue(output.contains("@InjectMocks"),
            "Bug V : case91 OrderService doit avoir @InjectMocks. Output:\n$output")
        assertTrue(output.contains("private com.testproject.case91.OrderService orderService;"),
            "Bug V : déclaration typée pour case91. Output:\n$output")
    }
}

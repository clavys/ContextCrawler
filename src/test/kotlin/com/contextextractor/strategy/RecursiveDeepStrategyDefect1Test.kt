package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.model.MetaKeys
import com.contextextractor.core.prompt.PromptContext
import com.contextextractor.core.prompt.meta.LayerKind
import com.contextextractor.core.prompt.stages.ContextRenderStage
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Défaut #1 — §3.2bis STUB_VIA_SPY pour méthodes héritées framework.
//
// Reproduit le pattern controleur JSF (BaseAstreaControleur#redirige) :
// méthode définie en super-classe qui descend dans javax.faces.* / org.primefaces.*.
// Sans court-circuit, la stratégie expand cette méthode en INTERNAL_LOGIC et
// crée des mocks parasites pour FacesContext, NavigationHandler, etc.
class RecursiveDeepStrategyDefect1Test {

    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val stage = ContextRenderStage()
    private val pkg = "com.test.defect1"

    @Test
    fun `method calling javax_faces is flagged STUB_VIA_SPY and not exploded`() {
        val fake = fixture {
            // Classe parente JSF — simule BaseAstreaControleur#redirige.
            klass("$pkg.BaseControleur") {
                method("redirige", returns = T("void")) {
                    param("page", T("$pkg.PageData"))
                    // Descend dans javax.faces — frontière framework.
                    calls("javax.faces.context.FacesContext", "getCurrentInstance")
                    calls("javax.faces.application.NavigationHandler", "handleNavigation")
                }
            }
            klass("$pkg.PageData") {
                method("getUrl", returns = T("java.lang.String"))
            }
            klass("$pkg.Controller", superFqn = "$pkg.BaseControleur") {
                method("handle", returns = T("void"),
                    body = "this.redirige(new PageData());") {
                    calls("$pkg.BaseControleur", "redirige")
                    instantiates("$pkg.PageData")
                }
            }
            superChain("$pkg.Controller", "$pkg.BaseControleur")
        }
        val sut = fake.resolveClass("$pkg.Controller")!!
        val target = fake.listMethodsOf("$pkg.Controller").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // redirige doit être marqué STUB_VIA_SPY (présent dans internalLogics
        // mais avec stubViaSpy=true et body vide).
        val redirigeKey = result.internalLogics.keys.firstOrNull { it.contains("#redirige") }
            ?: error("redirige attendu dans internalLogics. Vu : ${result.internalLogics.keys}")
        val redirigeLogic = result.internalLogics[redirigeKey]!!
        assertTrue(redirigeLogic.stubViaSpy,
            "redirige doit être marqué stubViaSpy=true")
        assertTrue("javax.faces." in redirigeLogic.frameworkPrefixesHit,
            "le préfixe javax.faces. doit figurer dans frameworkPrefixesHit, " +
                "vu : ${redirigeLogic.frameworkPrefixesHit}")
        assertEquals("", redirigeLogic.body,
            "le corps NE doit PAS être capturé pour une frontière de test")
        assertTrue(redirigeLogic.callSummaries.isEmpty(),
            "les appels NE doivent PAS être capturés pour une frontière de test")

        // Aucune classe javax.faces.* ne doit apparaître dans mocks (la
        // récursion n'a PAS suivi les appels framework).
        assertFalse(result.mocks.keys.any { it.startsWith("javax.faces") },
            "aucun mock javax.faces.* ne doit être généré. Vu : ${result.mocks.keys}")
    }

    @Test
    fun `STUB_VIA_SPY method is rendered in dedicated prompt section`() {
        val fake = fixture {
            klass("$pkg.BaseControleur") {
                method("redirige", returns = T("void")) {
                    param("page", T("$pkg.PageData"))
                    calls("javax.faces.context.FacesContext", "getCurrentInstance")
                }
            }
            klass("$pkg.PageData") {
                method("getUrl", returns = T("java.lang.String"))
            }
            klass("$pkg.Controller", superFqn = "$pkg.BaseControleur") {
                method("handle", returns = T("void"),
                    body = "this.redirige(new PageData());") {
                    calls("$pkg.BaseControleur", "redirige")
                    instantiates("$pkg.PageData")
                }
            }
            superChain("$pkg.Controller", "$pkg.BaseControleur")
        }
        val sut = fake.resolveClass("$pkg.Controller")!!
        val target = fake.listMethodsOf("$pkg.Controller").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val ctx = PromptContext(tree = mapper.map(result))
        stage.apply(ctx)
        val output = ctx.layers[LayerKind.CONTEXT] ?: error("layer CONTEXT absente")

        assertTrue(output.contains("# Methods to stub via spy (framework boundary)"),
            "section dédiée doit être présente. Output:\n$output")
        assertTrue(output.contains("redirige"),
            "redirige doit figurer dans la section spy")
        assertTrue(output.contains("doAnswer(invocation -> null)") || output.contains("doReturn"),
            "le pattern Mockito spy doit être suggéré (doAnswer pour void/objet, " +
                "doReturn typé pour primitive)")
        // Bug R — variable dérivée du SUT (Controller → controller).
        assertTrue(output.contains("controller = spy(controller);"),
            "l'instruction spy(controller) doit figurer (variable name dérivée du SUT)")
        // Vérification inverse : redirige NE doit PAS apparaître dans la section
        // « Internal sub-methods » (qui est l'autre cas).
        val internalSection = output.substringAfter(
            "# Internal sub-methods (informational only, do not mock)", missingDelimiterValue = "")
            .substringBefore("# Methods to stub via spy", missingDelimiterValue = "")
        assertFalse(internalSection.contains("#redirige"),
            "redirige NE doit PAS être dans la section internes — frontière de test exclusive")
    }

    @Test
    fun `non-framework intra-SUT method stays in regular INTERNAL_LOGIC`() {
        // Garde-fou inverse : une méthode héritée qui n'appelle PAS de framework
        // reste en INTERNAL_LOGIC normal (avec body, callSummaries).
        val fake = fixture {
            klass("$pkg.BaseController") {
                method("computeKey", returns = T("java.lang.String"),
                    body = "return \"k-\" + this.id;") {
                    reads("$pkg.BaseController", "id")
                }
                field("id", T("java.lang.String"))
            }
            klass("$pkg.Svc", superFqn = "$pkg.BaseController") {
                method("handle", returns = T("java.lang.String"),
                    body = "return this.computeKey();") {
                    calls("$pkg.BaseController", "computeKey")
                }
            }
            superChain("$pkg.Svc", "$pkg.BaseController")
        }
        val sut = fake.resolveClass("$pkg.Svc")!!
        val target = fake.listMethodsOf("$pkg.Svc").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val computeKey = result.internalLogics.values
            .firstOrNull { it.signature.name == "computeKey" }
            ?: error("computeKey attendu dans internalLogics")
        assertFalse(computeKey.stubViaSpy,
            "computeKey n'appelle pas de framework → INTERNAL_LOGIC normal")
        assertTrue(computeKey.body.isNotEmpty(),
            "le corps de computeKey doit être capturé (frontière intra-SUT ouverte)")
    }

    @Test
    fun `framework boundary detection works for org primefaces too`() {
        // Vérifie qu'un autre préfixe par défaut (org.primefaces.) déclenche
        // bien le court-circuit.
        val fake = fixture {
            klass("$pkg.JSFController") {
                method("update", returns = T("void")) {
                    calls("org.primefaces.context.RequestContext", "getCurrentInstance")
                }
                method("handle", returns = T("void"),
                    body = "this.update();") {
                    calls("$pkg.JSFController", "update")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.JSFController")!!
        val target = fake.listMethodsOf("$pkg.JSFController").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val updateLogic = result.internalLogics.values
            .firstOrNull { it.signature.name == "update" }
            ?: error("update attendu dans internalLogics")
        assertTrue(updateLogic.stubViaSpy,
            "update appelle org.primefaces.* → stubViaSpy=true")
        assertTrue("org.primefaces." in updateLogic.frameworkPrefixesHit)
    }
}

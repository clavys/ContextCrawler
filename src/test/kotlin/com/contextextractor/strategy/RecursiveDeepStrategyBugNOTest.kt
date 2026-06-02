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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bug N — détection transitive de la frontière framework.
// Bug O — valeur de retour du stub spy cohérente avec le type retourné.
//
// Cas production déclencheur : SupervisionDeltaVecControleur → redirige(PageDataDTO)
// → redirige(PageDataDTO, boolean) → redirige(PageDataDTO, boolean, boolean) →
// redirige(PageDataDTO, boolean, boolean, boolean) → javax.faces.
//
// Avant Bug N : la stratégie stubbait la 4-arg (la plus profonde, celle qui
// touche javax.faces directement), mais le target appelle la 1-arg → la
// cascade s'exécutait quand même. Stub inutile.
//
// Avant Bug O : `doReturn(/* TODO */)` poussait le LLM à inventer la valeur,
// produisant `doReturn(pageDataDTO)` sur une signature qui retourne String →
// WrongTypeOfReturnValue au runtime.
class RecursiveDeepStrategyBugNOTest {

    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val stage = ContextRenderStage()
    private val pkg = "com.test.bugno"

    @Test
    fun `Bug N — entry point of cascade chain is stubbed instead of deepest`() {
        // Reproduit la cascade 4 niveaux de SupervisionDeltaVecControleur :
        //   target → redirige(P) → redirige(P,b) → redirige(P,b,b) →
        //            redirige(P,b,b,b) → javax.faces
        // Bug N attend : seule la 1-arg est en STUB_VIA_SPY (le point d'entrée
        // appelé par target), les 3 intermédiaires ne sont PAS dans internalLogics.
        val fake = fixture {
            klass("$pkg.PageData", isInterface = true)
            klass("$pkg.Base") {
                method("redirige", returns = T("java.lang.String"),
                    body = "return this.redirige(p, false);") {
                    param("p", T("$pkg.PageData"))
                    calls("$pkg.Base", "redirige", "$pkg.PageData", "boolean")
                }
                method("redirige", returns = T("java.lang.String"),
                    body = "return this.redirige(p, b, true);") {
                    param("p", T("$pkg.PageData"))
                    param("b", T("boolean"))
                    calls("$pkg.Base", "redirige", "$pkg.PageData", "boolean", "boolean")
                }
                method("redirige", returns = T("java.lang.String"),
                    body = "return this.redirige(p, b1, b2, false);") {
                    param("p", T("$pkg.PageData"))
                    param("b1", T("boolean"))
                    param("b2", T("boolean"))
                    calls("$pkg.Base", "redirige", "$pkg.PageData", "boolean", "boolean", "boolean")
                }
                method("redirige", returns = T("java.lang.String")) {
                    param("p", T("$pkg.PageData"))
                    param("b1", T("boolean"))
                    param("b2", T("boolean"))
                    param("b3", T("boolean"))
                    // Frontière framework au fond de la cascade.
                    calls("javax.faces.context.FacesContext", "getCurrentInstance")
                }
            }
            klass("$pkg.Ctrl", superFqn = "$pkg.Base") {
                method("handle", returns = T("void"),
                    body = "this.redirige(new PageData());") {
                    calls("$pkg.Base", "redirige", "$pkg.PageData")
                    instantiates("$pkg.PageData")
                }
            }
            superChain("$pkg.Ctrl", "$pkg.Base")
        }
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // Bug N #1 — la 1-arg (point d'entrée) est en STUB_VIA_SPY.
        val redirige1 = result.internalLogics.values.firstOrNull {
            it.signature.name == "redirige" && it.signature.parameters.size == 1
        }
        assertTrue(redirige1 != null,
            "redirige(1-arg) doit être surfacé. internalLogics: ${result.internalLogics.keys}")
        assertTrue(redirige1!!.stubViaSpy,
            "redirige(1-arg) doit être stubViaSpy=true (entrée de la cascade framework)")
        assertTrue("javax.faces." in redirige1.frameworkPrefixesHit,
            "le hit javax.faces. doit être reporté sur l'entrée. " +
                "Vu: ${redirige1.frameworkPrefixesHit}")

        // Bug N #2 — les 2/3/4-arg NE doivent PAS être dans internalLogics
        // (la stratégie n'a pas exploré la cascade — la chaîne est court-circuitée).
        val redirige2 = result.internalLogics.values.firstOrNull {
            it.signature.name == "redirige" && it.signature.parameters.size == 2
        }
        val redirige3 = result.internalLogics.values.firstOrNull {
            it.signature.name == "redirige" && it.signature.parameters.size == 3
        }
        val redirige4 = result.internalLogics.values.firstOrNull {
            it.signature.name == "redirige" && it.signature.parameters.size == 4
        }
        assertTrue(redirige2 == null,
            "redirige(2-arg) NE doit PAS être dans internalLogics. Vu: $redirige2")
        assertTrue(redirige3 == null,
            "redirige(3-arg) NE doit PAS être dans internalLogics. Vu: $redirige3")
        assertTrue(redirige4 == null,
            "redirige(4-arg) NE doit PAS être dans internalLogics — c'est le " +
                "point d'entrée 1-arg qui est stubbé. Vu: $redirige4")
    }

    @Test
    fun `Bug O — String return uses doAnswer invocation null instead of doReturn TODO`() {
        // Une méthode framework boundary qui retourne String → doAnswer pattern
        // (compatible avec n'importe quel objet, retourne null).
        val fake = fixture {
            klass("$pkg.Base2") {
                method("redirige", returns = T("java.lang.String")) {
                    param("p", T("java.lang.String"))
                    calls("javax.faces.context.FacesContext", "getCurrentInstance")
                }
            }
            klass("$pkg.Ctrl2", superFqn = "$pkg.Base2") {
                method("handle", returns = T("void"),
                    body = "this.redirige(\"x\");") {
                    calls("$pkg.Base2", "redirige", "java.lang.String")
                }
            }
            superChain("$pkg.Ctrl2", "$pkg.Base2")
        }
        val sut = fake.resolveClass("$pkg.Ctrl2")!!
        val target = fake.listMethodsOf("$pkg.Ctrl2").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val ctx = PromptContext(tree = mapper.map(result))
        stage.apply(ctx)
        val output = ctx.layers[LayerKind.CONTEXT] ?: error("layer CONTEXT absente")

        assertTrue(output.contains("doAnswer(invocation -> null)"),
            "pour un retour objet (String), le pattern doit être doAnswer(invocation -> null). " +
                "Output:\n$output")
        assertFalse(output.contains("doReturn(/* TODO */)"),
            "l'ancien placeholder doReturn(/* TODO */) doit avoir disparu. " +
                "Output:\n$output")
    }

    @Test
    fun `Bug O — boolean return uses doReturn false`() {
        val fake = fixture {
            klass("$pkg.Base3") {
                method("isAllowed", returns = T("boolean")) {
                    calls("javax.faces.context.FacesContext", "getCurrentInstance")
                }
            }
            klass("$pkg.Ctrl3", superFqn = "$pkg.Base3") {
                method("handle", returns = T("void"),
                    body = "if (this.isAllowed()) {}") {
                    calls("$pkg.Base3", "isAllowed")
                }
            }
            superChain("$pkg.Ctrl3", "$pkg.Base3")
        }
        val sut = fake.resolveClass("$pkg.Ctrl3")!!
        val target = fake.listMethodsOf("$pkg.Ctrl3").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val ctx = PromptContext(tree = mapper.map(result))
        stage.apply(ctx)
        val output = ctx.layers[LayerKind.CONTEXT] ?: error("layer CONTEXT absente")

        assertTrue(output.contains("doReturn(false)"),
            "pour un retour boolean, le pattern doit être doReturn(false). Output:\n$output")
    }

    @Test
    fun `Bug O — int return uses doReturn 0`() {
        val fake = fixture {
            klass("$pkg.Base4") {
                method("countItems", returns = T("int")) {
                    calls("javax.faces.context.FacesContext", "getCurrentInstance")
                }
            }
            klass("$pkg.Ctrl4", superFqn = "$pkg.Base4") {
                method("handle", returns = T("void"),
                    body = "int n = this.countItems();") {
                    calls("$pkg.Base4", "countItems")
                }
            }
            superChain("$pkg.Ctrl4", "$pkg.Base4")
        }
        val sut = fake.resolveClass("$pkg.Ctrl4")!!
        val target = fake.listMethodsOf("$pkg.Ctrl4").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val ctx = PromptContext(tree = mapper.map(result))
        stage.apply(ctx)
        val output = ctx.layers[LayerKind.CONTEXT] ?: error("layer CONTEXT absente")

        assertTrue(output.contains("doReturn(0)"),
            "pour un retour int, le pattern doit être doReturn(0). Output:\n$output")
    }
}

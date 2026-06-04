package com.contextextractor.strategy

import com.contextextractor.core.model.HierarchyLevel
import com.contextextractor.core.model.SelectedConstructor
import com.contextextractor.core.model.refs.UsageSite
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.T
import com.contextextractor.fakes.Tg
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.refs.ReferenceGraphBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Phase 1.3 — Tests du ReferenceGraphBuilder.
//
// Vérifie que la PASSE 1 énumère correctement tous les UsageSite attendus
// pour des cas représentatifs incluant les patterns qui causaient des bugs
// structurels en V1.1 :
//   - Cas U : modele avec setters → AsCallTarget natif (sans promotion)
//   - Cas S : type essentiel atteint via field → AsFieldOfSut + AsCallTarget
//   - Cas I : type évincé V1.1 → présent dans le graph (pas d'éviction V1.2)
//   - Cas trivial getter chain : champ derrière le getter référencé
//   - Cas DTO récursif : Order contient Customer → Customer enregistré
class ReferenceGraphBuilderTest {

    private val pkg = "com.test.refs"

    private fun buildGraph(
        fake: FakeIntrospector,
        sutFqn: String,
        targetName: String,
        frameworkPrefixes: List<String> = emptyList()
    ) = run {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == targetName }
        val hierarchy = listOf(HierarchyLevel(sut.fqn, sut.annotations)) +
            fake.listSuperClasses(sut).map { HierarchyLevel(it.fqn, it.annotations) }
        val ctor = SelectedConstructor(parameters = emptyList())
        ReferenceGraphBuilder(fake, frameworkPrefixes).build(sut, target, hierarchy, ctor)
    }

    @Test
    fun `case U natif — modele field with only setters gets AsCallTarget`() {
        // Pattern V1.1 Bug U : `SupervisionDeltaVecModele` n'a que des setters
        // → classifier rule 10 dit DATA_STRUCTURE. Mais target appelle dessus.
        // V1.1 : promotion post-hoc. V1.2 : le builder enregistre AsCallTarget
        // ET AsFieldOfSut — le classifier (Phase 2) décide.
        val fake = fixture {
            klass("$pkg.Modele") {
                method("setAfficherResultats", returns = T("void")) {
                    param("v", T("boolean"))
                }
                method("setLienVisible", returns = T("void")) {
                    param("v", T("boolean"))
                }
            }
            klass("$pkg.Ctrl") {
                field("modele", T("$pkg.Modele"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("void"),
                    body = "this.modele.setAfficherResultats(true); this.modele.setLienVisible(false);") {
                    reads("$pkg.Ctrl", "modele")
                    calls("$pkg.Modele", "setAfficherResultats", "boolean")
                    calls("$pkg.Modele", "setLienVisible", "boolean")
                }
            }
        }
        val graph = buildGraph(fake, "$pkg.Ctrl", "handle")

        val modeleRef = graph.referenceFor("$pkg.Modele")
        assertNotNull(modeleRef, "Modele doit être présent dans le graph")
        assertTrue(modeleRef!!.isSutField,
            "Modele doit avoir AsFieldOfSut. Usages: ${modeleRef.usages}")
        assertTrue(modeleRef.isCalledAsInstance,
            "Modele doit avoir AsCallTarget (Bug U natif). Usages: ${modeleRef.usages}")
        assertEquals(2, modeleRef.instanceCallSites.size,
            "Modele doit avoir 2 sites d'appel (setAfficherResultats + setLienVisible)")
    }

    @Test
    fun `case S natif — essential type via SUT field properly tracked`() {
        // Pattern V1.1 Bug S : un type essentiel atteint via un champ chain
        // était filtré par usage transitif. V1.2 : tout ce qui est référencé
        // reste dans le graph — décision de garder/jeter en Phase 2/3.
        val fake = fixture {
            klass("$pkg.Service", isInterface = true) {
                method("execute", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl2") {
                field("svc", T("$pkg.Service"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.svc.execute();") {
                    reads("$pkg.Ctrl2", "svc")
                    calls("$pkg.Service", "execute")
                }
            }
        }
        val graph = buildGraph(fake, "$pkg.Ctrl2", "handle")

        val svcRef = graph.referenceFor("$pkg.Service")
        assertNotNull(svcRef, "Service doit être présent")
        assertTrue(svcRef!!.isSutField && svcRef.isCalledAsInstance,
            "Service doit être à la fois AsFieldOfSut et AsCallTarget. " +
                "Usages: ${svcRef.usages}")
        assertEquals(1, svcRef.resolvedCalledSignatures.size,
            "execute() doit être résolu. Signatures: ${svcRef.resolvedCalledSignatures}")
    }

    @Test
    fun `target params and return type are seeded as references`() {
        val fake = fixture {
            klass("$pkg.RequestDTO")
            klass("$pkg.ResultDTO")
            klass("$pkg.Ctrl3") {
                method("process", returns = T("$pkg.ResultDTO"),
                    body = "return new ResultDTO();") {
                    param("req", T("$pkg.RequestDTO"))
                    instantiates("$pkg.ResultDTO")
                }
            }
        }
        val graph = buildGraph(fake, "$pkg.Ctrl3", "process")

        val reqRef = graph.referenceFor("$pkg.RequestDTO")
        assertNotNull(reqRef, "RequestDTO (param target) attendu")
        assertTrue(reqRef!!.usages.any { it is UsageSite.AsParamOfTarget },
            "RequestDTO doit avoir AsParamOfTarget. Usages: ${reqRef.usages}")

        val resRef = graph.referenceFor("$pkg.ResultDTO")
        assertNotNull(resRef, "ResultDTO (return + instantiation) attendu")
        assertTrue(resRef!!.usages.any { it is UsageSite.AsReturnTypeOfTarget },
            "ResultDTO doit avoir AsReturnTypeOfTarget. Usages: ${resRef.usages}")
        assertTrue(resRef.isInstantiatedInBody,
            "ResultDTO doit avoir AsInstantiationInBody. Usages: ${resRef.usages}")
    }

    @Test
    fun `generic type args of return type are tracked as AsTypeArgOfReference`() {
        // Cas Bug DD natif : `List<ElementXxx>` → ElementXxx doit être référencé.
        val fake = fixture {
            klass("$pkg.Element")
            klass("$pkg.Ctrl4") {
                method("getAll", returns = Tg("java.util.List", T("$pkg.Element"))) {}
            }
        }
        val graph = buildGraph(fake, "$pkg.Ctrl4", "getAll")

        val elemRef = graph.referenceFor("$pkg.Element")
        assertNotNull(elemRef, "Element (type-arg du retour) attendu dans le graph")
        // Soit comme AsReturnTypeOfTarget(asGenericArg=true), soit comme
        // AsTypeArgOfReference. Les deux sont acceptables selon la sémantique.
        assertTrue(
            elemRef!!.usages.any {
                (it is UsageSite.AsReturnTypeOfTarget && it.asGenericArg) ||
                    it is UsageSite.AsTypeArgOfReference
            },
            "Element doit avoir un usage de type-arg. Usages: ${elemRef.usages}"
        )
    }

    @Test
    fun `instance calls on mock collect their return type as AsStubReturn`() {
        // Pattern critique pour Bug DD : `mock.getList()` returns
        // `List<Element>` → Element doit être marqué AsStubReturn pour que
        // le materializer le surface.
        val fake = fixture {
            klass("$pkg.Item")
            klass("$pkg.Service2", isInterface = true) {
                method("getItems", returns = Tg("java.util.List", T("$pkg.Item")))
            }
            klass("$pkg.Ctrl5") {
                field("svc", T("$pkg.Service2"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("void"),
                    body = "this.svc.getItems();") {
                    reads("$pkg.Ctrl5", "svc")
                    calls("$pkg.Service2", "getItems")
                }
            }
        }
        val graph = buildGraph(fake, "$pkg.Ctrl5", "handle")

        val itemRef = graph.referenceFor("$pkg.Item")
        assertNotNull(itemRef, "Item (returnType type-arg du stub) attendu")
        // Doit avoir au moins AsStubReturn (propagateStubReturns) OU
        // AsTypeArgOfReference selon le path d'enregistrement.
        val hasStubReturnOrTypeArg = itemRef!!.usages.any {
            it is UsageSite.AsStubReturn || it is UsageSite.AsTypeArgOfReference
        }
        assertTrue(hasStubReturnOrTypeArg,
            "Item doit avoir AsStubReturn ou AsTypeArgOfReference. " +
                "Usages: ${itemRef.usages}")
    }

    @Test
    fun `case I natif — type stays in graph even with many references (no eviction)`() {
        // Pattern V1.1 Bug I : type évincé du budget. V1.2 : tout reste.
        // On crée 15 services référencés, tous doivent rester dans le graph
        // (V1.1 aurait évincé les moins prioritaires sur maxMockCount=10).
        val fake = fixture {
            for (i in 1..15) {
                klass("$pkg.Svc$i", isInterface = true) {
                    method("doIt$i", returns = T("java.lang.String"))
                }
            }
            klass("$pkg.CtrlMany") {
                for (i in 1..15) {
                    field("svc$i", T("$pkg.Svc$i"),
                        annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                }
                method("handleAll", returns = T("java.lang.String"),
                    body = (1..15).joinToString("") { "this.svc$it.doIt$it(); " }) {
                    for (i in 1..15) {
                        reads("$pkg.CtrlMany", "svc$i")
                        calls("$pkg.Svc$i", "doIt$i")
                    }
                }
            }
        }
        val graph = buildGraph(fake, "$pkg.CtrlMany", "handleAll")

        // Les 15 services doivent tous être présents — pas d'éviction.
        for (i in 1..15) {
            assertNotNull(graph.referenceFor("$pkg.Svc$i"),
                "Svc$i doit rester dans le graph (V1.2 = pas d'éviction)")
        }
    }

    @Test
    fun `framework boundary stops BFS — internal method body not explored`() {
        // Bug E natif : méthode intra-SUT qui descend dans javax.faces ne doit
        // pas voir son body exploré (sera STUB_VIA_SPY en Phase 3).
        val fake = fixture {
            klass("javax.faces.context.FacesContext", isInterface = true) {
                method("doFwk", returns = T("void"))
            }
            klass("$pkg.Ctrl6") {
                method("compute", returns = T("void"),
                    body = "this.frameworkBoundary();") {
                    calls("$pkg.Ctrl6", "frameworkBoundary")
                }
                method("frameworkBoundary", returns = T("void"),
                    body = "javax.faces.context.FacesContext.getCurrentInstance();") {
                    calls("javax.faces.context.FacesContext", "doFwk")
                }
            }
        }
        val graph = buildGraph(fake, "$pkg.Ctrl6", "compute",
            frameworkPrefixes = listOf("javax.faces."))

        // FacesContext ne doit PAS être référencé : la méthode frameworkBoundary
        // descend dans javax.faces, son body n'est pas exploré.
        val fcRef = graph.referenceFor("javax.faces.context.FacesContext")
        assertFalse(fcRef != null && fcRef.isCalledAsInstance,
            "FacesContext ne doit PAS être AsCallTarget si frontière respectée. " +
                "Ref: $fcRef")
    }

    @Test
    fun `DTO recursive fields are propagated`() {
        // Un Order qui a un Customer en champ → Customer doit être dans le graph
        // via AsFieldOfReferencedClass.
        val fake = fixture {
            klass("$pkg.Customer") {
                field("name", T("java.lang.String"))
            }
            klass("$pkg.Order") {
                field("customer", T("$pkg.Customer"))
                field("id", T("java.lang.Long"))
            }
            klass("$pkg.Ctrl7") {
                method("getOrder", returns = T("$pkg.Order")) {}
            }
        }
        val graph = buildGraph(fake, "$pkg.Ctrl7", "getOrder")

        val customerRef = graph.referenceFor("$pkg.Customer")
        assertNotNull(customerRef, "Customer (champ de Order) attendu dans le graph")
        assertTrue(customerRef!!.usages.any { it is UsageSite.AsFieldOfReferencedClass },
            "Customer doit avoir AsFieldOfReferencedClass. Usages: ${customerRef.usages}")
    }

    @Test
    fun `unresolved types stay in graph with descriptor=null`() {
        // §8bis.1 cas dégradé — une classe non résolvable reste dans le graph
        // (le materializer la traitera comme stub si nécessaire).
        val fake = fixture {
            klass("$pkg.Ctrl8") {
                method("handle", returns = T("void"),
                    body = "this.unknown.doSomething();") {
                    calls("$pkg.UnknownClass", "doSomething")
                }
            }
        }
        val graph = buildGraph(fake, "$pkg.Ctrl8", "handle")

        val unknownRef = graph.referenceFor("$pkg.UnknownClass")
        assertNotNull(unknownRef, "UnknownClass doit être enregistré même non résolu")
        assertTrue(unknownRef!!.descriptor == null,
            "UnknownClass.descriptor doit être null (non résolvable)")
        assertTrue(unknownRef.isCalledAsInstance,
            "UnknownClass doit avoir AsCallTarget malgré descriptor null")
    }

    @Test
    fun `static calls are tagged AsStaticCallTarget not AsCallTarget`() {
        val fake = fixture {
            klass("$pkg.Utils") {
                method("staticHelper", returns = T("java.lang.String"), isStatic = true) {}
            }
            klass("$pkg.Ctrl9") {
                method("compute", returns = T("java.lang.String"),
                    body = "return $pkg.Utils.staticHelper();") {
                    calls("$pkg.Utils", "staticHelper", isStatic = true)
                }
            }
        }
        val graph = buildGraph(fake, "$pkg.Ctrl9", "compute")

        val utilsRef = graph.referenceFor("$pkg.Utils")
        assertNotNull(utilsRef, "Utils doit être présent")
        assertTrue(utilsRef!!.isCalledAsStatic && !utilsRef.isCalledAsInstance,
            "Utils doit être AsStaticCallTarget, PAS AsCallTarget. " +
                "Usages: ${utilsRef.usages}")
    }

    @Test
    fun `unused autowired field is still in graph (no transitive filter pre-classification)`() {
        // Bug B natif : un @Autowired non utilisé reste dans le graph avec
        // seulement AsFieldOfSut. V1.1 le filtrait ; V1.2 défère au classifier.
        val fake = fixture {
            klass("$pkg.UnusedSvc", isInterface = true) {
                method("idle", returns = T("void"))
            }
            klass("$pkg.UsedSvc", isInterface = true) {
                method("active", returns = T("void"))
            }
            klass("$pkg.Ctrl10") {
                field("unused", T("$pkg.UnusedSvc"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("used", T("$pkg.UsedSvc"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("work", returns = T("void"),
                    body = "this.used.active();") {
                    reads("$pkg.Ctrl10", "used")
                    calls("$pkg.UsedSvc", "active")
                }
            }
        }
        val graph = buildGraph(fake, "$pkg.Ctrl10", "work")

        val unusedRef = graph.referenceFor("$pkg.UnusedSvc")
        assertNotNull(unusedRef, "UnusedSvc doit être présent dans le graph")
        assertTrue(unusedRef!!.isSutField && !unusedRef.isCalledAsInstance,
            "UnusedSvc doit avoir AsFieldOfSut SEULEMENT (pas AsCallTarget). " +
                "Usages: ${unusedRef.usages}")
        // C'est en Phase 2 que ce ref sera potentiellement filtré.
    }
}

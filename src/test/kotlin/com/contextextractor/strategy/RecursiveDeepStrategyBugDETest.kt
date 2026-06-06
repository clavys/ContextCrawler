package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.Budget
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bug D + Bug E — priorisation BLOC 6a + respect framework boundary dans
// le BFS d'usage transitif.
//
// Reproduit le cas SupervisionDeltaVecControleur : SUT JSF avec ~10 @Autowired
// parasites + 1 essentiel (Service touché par target) + 1 essentiel hérité du
// grand-parent (Session via trivial getter). Bug A (LFU strict) bloquait toute
// éviction domaine — résultat : les @Autowired parasites prenaient le budget
// et l'essentiel hérité (Session) ne rentrait jamais.
class RecursiveDeepStrategyBugDETest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugde"

    @Test
    fun `essential mock reached via inherited trivial getter wins priority over unused Autowireds`() {
        // Setup : SUT a 10 @Autowired parasites + 1 service utile + parent qui
        // expose getSession() (trivial getter sur un champ hérité Session).
        // Budget=10. La méthode cible appelle service.doIt() + getSession().getId().
        //
        // Sans Bug D fix : les 10 parasites entrent en premier → Session dropé.
        // Avec Bug D fix : Service + Session entrent en high-priority → les
        // parasites sont visités après et certains sont dropés en FIFO.
        val fake = fixture {
            klass("$pkg.Session", isInterface = true) {
                method("getId", returns = T("java.lang.String"))
            }
            klass("$pkg.Svc", isInterface = true) {
                method("doIt", returns = T("java.lang.String"))
            }
            // 9 services parasites
            for (i in 1..9) {
                klass("$pkg.Parasite$i", isInterface = true) {
                    method("nope", returns = T("java.lang.String"))
                }
            }
            klass("$pkg.BaseControleur") {
                field("session", T("$pkg.Session"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("getSession", returns = T("$pkg.Session")) {
                    reads("$pkg.BaseControleur", "session")
                }
            }
            klass("$pkg.Controller", superFqn = "$pkg.BaseControleur") {
                // service touché par target
                field("svc", T("$pkg.Svc"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                // 9 parasites jamais touchés par target
                for (i in 1..9) {
                    field("p$i", T("$pkg.Parasite$i"),
                        annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                }
                method("handle", returns = T("java.lang.String"),
                    body = "return svc.doIt() + getSession().getId();") {
                    reads("$pkg.Controller", "svc")
                    calls("$pkg.Svc", "doIt")
                    // PSI résout `this.getSession()` vers la classe déclarante.
                    calls("$pkg.BaseControleur", "getSession")
                    calls("$pkg.Session", "getId")
                }
            }
            superChain("$pkg.Controller", "$pkg.BaseControleur")
        }
        val sut = fake.resolveClass("$pkg.Controller")!!
        val target = fake.listMethodsOf("$pkg.Controller").single { it.name == "handle" }
        // V1.2 — Budget(maxMockCount=…) supprimé (cf RAPPORT_CONTEXT §9 défaut #3).
        // En V1.2, les types essentiels (Svc + Session via trivial getter) sont
        // garantis par le classifier context-aware (PASSE 2), pas par un cap.
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // Essentiels touchés par target → conservés.
        assertTrue("$pkg.Svc" in result.mocks.keys,
            "Svc (touché direct par target) doit être mocké. Vu: ${result.mocks.keys}")
        // Session est touchée via getSession() trivial getter — le BFS de priorité
        // descend dans le getter pour atteindre le champ, donc Session est high-priority.
        assertTrue("$pkg.Session" in result.mocks.keys,
            "Session (hérité, atteint via trivial getter) doit être mocké — c'est " +
                "le verrou principal de Bug D. Vu: ${result.mocks.keys}")
    }

    @Test
    fun `field accessed only via STUB_VIA_SPY method is NOT mocked`() {
        // Bug E : le BFS d'usage transitif doit s'arrêter aux frontières
        // framework. Sans ce verrou, un champ touché uniquement par redirige()
        // (méthode framework) serait considéré utile et donc mocké, alors
        // qu'il n'est jamais accédé dans le test (redirige est stubé via spy).
        val fake = fixture {
            klass("$pkg.FilAriane", isInterface = true) {
                method("ajoutPage", returns = T("void")) {
                    param("page", T("java.lang.String"))
                }
            }
            klass("$pkg.UsedSvc", isInterface = true) {
                method("doIt", returns = T("java.lang.String"))
            }
            klass("$pkg.BaseControleur2") {
                // filAriane est accédé par redirige (framework)
                field("filAriane", T("$pkg.FilAriane"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("redirige", returns = T("void")) {
                    param("page", T("java.lang.String"))
                    reads("$pkg.BaseControleur2", "filAriane")
                    // Descend dans javax.faces → STUB_VIA_SPY
                    calls("javax.faces.context.FacesContext", "getCurrentInstance")
                    calls("$pkg.FilAriane", "ajoutPage")
                }
            }
            klass("$pkg.Controller2", superFqn = "$pkg.BaseControleur2") {
                field("svc", T("$pkg.UsedSvc"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("void"),
                    body = "svc.doIt(); this.redirige(\"page\");") {
                    reads("$pkg.Controller2", "svc")
                    calls("$pkg.UsedSvc", "doIt")
                    calls("$pkg.BaseControleur2", "redirige")
                }
            }
            superChain("$pkg.Controller2", "$pkg.BaseControleur2")
        }
        val sut = fake.resolveClass("$pkg.Controller2")!!
        val target = fake.listMethodsOf("$pkg.Controller2").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // UsedSvc essentiel — conservé.
        assertTrue("$pkg.UsedSvc" in result.mocks.keys)
        // FilAriane n'est touché QUE par redirige (framework boundary STUB_VIA_SPY).
        // Le BFS d'usage transitif doit s'arrêter avant d'y accéder.
        assertFalse("$pkg.FilAriane" in result.mocks.keys,
            "FilAriane n'est touché que via redirige (STUB_VIA_SPY) → ne doit PAS " +
                "être mocké. Vu: ${result.mocks.keys}")
        assertFalse("filAriane" in result.fields.map { it.name },
            "le champ filAriane doit être filtré (défaut #2 + Bug E)")
    }
}

package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bug S — Filet de sécurité : un champ @Autowired dont le TYPE est essentiel
// (touché directement par le corps de la méthode cible) doit survivre au
// filtre `filteredFields` même si le BFS `computeTransitiveFieldUsage` rate
// l'accès. Cas production déclencheur (SupervisionDeltaVecControleur#rechercher) :
//   this.supervisionDeltaVecService.rechercherDeltaVec(
//       this.tableauSupervisionDeltaVecModele.getCriteresRecherche());
//
// Avant Bug S : la chaîne `this.field.method(this.otherField.method())` cassait
// le BFS pour certaines configurations PSI — `supervisionDeltaVecService` et
// `tableauSupervisionDeltaVecModele` n'étaient pas dans transitiveUsage,
// donc leurs champs MOCKITO_INJECT_MOCKS étaient dropés, donc leurs mocks
// étaient retirés par la réconciliation. Le LLM panique et stub `getXxx()`
// sur le SUT (anti-hallucination violée massivement).
//
// Avec Bug S : `essentialMockTypes` (rempli par Bug M depuis instanceCalls
// du target) sert de filet : si le type du champ est essentiel, on garde
// le champ même hors transitiveUsage.
class RecursiveDeepStrategyBugSTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugs"

    @Test
    fun `Bug S — Autowired field whose type is essential survives transitive filter`() {
        // Setup minimal qui reproduit la chaîne :
        //   target body calls `this.svc.doIt()` mais on simule un BFS défaillant
        //   en ne déclarant PAS le reads("$pkg.Ctrl", "svc") — seul `calls()`
        //   marque l'instance call (qui rend Svc essentiel via Bug M).
        // Sans Bug S, le champ svc (MOCKITO_INJECT_MOCKS) est dropé car pas
        // dans transitiveUsage.
        val fake = fixture {
            klass("$pkg.Svc", isInterface = true) {
                method("doIt", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl") {
                // @Autowired → MOCKITO_INJECT_MOCKS stratégie automatique.
                field("svc", T("$pkg.Svc"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.svc.doIt();") {
                    // Volontairement PAS de reads("$pkg.Ctrl", "svc") — simule
                    // le cas production où PSI rate l'accès field qualifié.
                    calls("$pkg.Svc", "doIt")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // Le champ svc DOIT être préservé via Bug S — son type Svc est dans
        // essentialMockTypes (Bug M l'ajoute depuis l'instanceCall sur Svc.doIt).
        assertTrue(result.fields.any { it.name == "svc" },
            "Bug S : champ `svc` doit être préservé même hors transitiveUsage " +
                "car son type est essentiel (target appelle Svc.doIt). " +
                "Champs vus: ${result.fields.map { it.name }}")
        // Le mock Svc DOIT figurer dans le résultat final.
        assertTrue("$pkg.Svc" in result.mocks.keys,
            "Bug S : Svc doit être dans les mocks. Vu: ${result.mocks.keys}")
    }

    @Test
    fun `Bug S — chained field access this dot a method this dot b method survives`() {
        // Reproduit littéralement le pattern problématique de la prod :
        //   this.svcA.doA(this.svcB.getCriteres())
        // Bug M ajoute SvcA ET SvcB à essentialMockTypes. Bug S garantit
        // que les DEUX champs Autowired sont conservés.
        val fake = fixture {
            klass("$pkg.Critere", isInterface = true)
            klass("$pkg.SvcB", isInterface = true) {
                method("getCriteres", returns = T("$pkg.Critere"))
            }
            klass("$pkg.SvcA", isInterface = true) {
                method("doA", returns = T("java.lang.String")) {
                    param("c", T("$pkg.Critere"))
                }
            }
            klass("$pkg.Ctrl2") {
                field("svcA", T("$pkg.SvcA"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("svcB", T("$pkg.SvcB"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.svcA.doA(this.svcB.getCriteres());") {
                    // Volontairement aucun reads(...) — simule défaillance PSI
                    // sur chaînes imbriquées.
                    calls("$pkg.SvcA", "doA", "$pkg.Critere")
                    calls("$pkg.SvcB", "getCriteres")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl2")!!
        val target = fake.listMethodsOf("$pkg.Ctrl2").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue(result.fields.any { it.name == "svcA" },
            "champ svcA préservé via Bug S. Vu: ${result.fields.map { it.name }}")
        assertTrue(result.fields.any { it.name == "svcB" },
            "champ svcB préservé via Bug S. Vu: ${result.fields.map { it.name }}")
        assertTrue("$pkg.SvcA" in result.mocks.keys,
            "mock SvcA doit rester. Vu: ${result.mocks.keys}")
        assertTrue("$pkg.SvcB" in result.mocks.keys,
            "mock SvcB doit rester. Vu: ${result.mocks.keys}")
    }

    @Test
    fun `Bug S — non-essential Autowired stays dropped (no regression on Bug B filter)`() {
        // Verrou inverse : un parasite @Autowired (target ne le touche pas
        // du tout) ne doit PAS être maintenu par Bug S. essentialMockTypes
        // n'inclut que les types appelés par target, donc Parasite hors
        // de cet ensemble est correctement dropé.
        val fake = fixture {
            klass("$pkg.Svc3", isInterface = true) {
                method("doIt", returns = T("java.lang.String"))
            }
            klass("$pkg.Parasite", isInterface = true)
            klass("$pkg.Ctrl3") {
                field("svc", T("$pkg.Svc3"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("parasite", T("$pkg.Parasite"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return svc.doIt();") {
                    reads("$pkg.Ctrl3", "svc")
                    calls("$pkg.Svc3", "doIt")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl3")!!
        val target = fake.listMethodsOf("$pkg.Ctrl3").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // Svc essentiel via Bug M → kept.
        assertTrue("$pkg.Svc3" in result.mocks.keys,
            "Svc essentiel doit rester. Vu: ${result.mocks.keys}")
        // Parasite non essentiel + hors transitiveUsage → dropé (pas de
        // régression sur le filet Bug B/défaut #2).
        assertTrue("$pkg.Parasite" !in result.mocks.keys,
            "Parasite NON touché par target NE doit PAS être maintenu par Bug S. " +
                "Vu: ${result.mocks.keys}")
    }
}

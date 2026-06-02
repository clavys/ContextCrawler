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

// Bug M + Bug P — Préservation des types touchés par le corps de target.
//
// Bug M : tout type sur lequel le corps de la méthode cible appelle une
// méthode (instance call) doit être marqué essentiel — sinon un service
// directement utilisé peut être évincé par une vague de @Autowired moins
// importants visités en BLOC 6a avant lui.
//
// Bug P : un getter trivial intra-SUT (ex : `BaseControleur#getUserSession()`
// retournant le champ `userSession`) doit propager :
//   • le nom du champ retourné → activeFields (le champ entre en usefulFields
//     même sans @Autowired)
//   • le type FQN du champ retourné → essentialMockTypes
//
// Cas de production déclencheur (`SupervisionDeltaVecControleur`) :
//   void redirigerVersDetailsDeltaVec(LigneResultatSupervisionDeltaVecDTO ligne) {
//       ReferenceDTO ref = supervisionDeltaVecService.preparerContexteDetailDeltaVec(ligne);
//       this.redirige(this.getUserSession().getPageData(NavigationEnum.X));
//   }
// Avant Bug M, `SupervisionDeltaVecService` est évincé du budget par les
// 10 @Autowired du controleur. Avant Bug P, le LLM doit "deviner" que
// getUserSession() retourne SessionAstreaModele.
class RecursiveDeepStrategyBugMPTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugmp"

    @Test
    fun `Bug M — service called from target body survives tight budget`() {
        // Setup réplique du cas SupervisionDeltaVecControleur :
        // 3 @Autowired parasites (non appelés) + 1 service appelé.
        // Budget = 3 → si CalledSvc n'est PAS essentiel, il est évincé.
        val fake = fixture {
            klass("$pkg.CalledSvc", isInterface = true) {
                method("doRealWork", returns = T("java.lang.String"))
            }
            klass("$pkg.ParasiteA", isInterface = true)
            klass("$pkg.ParasiteB", isInterface = true)
            klass("$pkg.ParasiteC", isInterface = true)
            klass("$pkg.Ctrl") {
                field("calledSvc", T("$pkg.CalledSvc"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("parasiteA", T("$pkg.ParasiteA"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("parasiteB", T("$pkg.ParasiteB"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("parasiteC", T("$pkg.ParasiteC"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.calledSvc.doRealWork();") {
                    reads("$pkg.Ctrl", "calledSvc")
                    calls("$pkg.CalledSvc", "doRealWork")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "handle" }
        val tightBudget = StrategyConfig(budget = Budget(maxMockCount = 3))
        val result = strategy.extractCore(fake, DefaultClassifier(), tightBudget, sut, target)

        assertTrue("$pkg.CalledSvc" in result.mocks.keys,
            "CalledSvc (appelé directement dans le body) DOIT être préservé. " +
                "Vu: ${result.mocks.keys}")
        // Bug D + Bug M coopèrent : CalledSvc est reachable depuis target (un
        // appel direct compte), donc il entre en PRIORITÉ HAUTE en BLOC 6a et
        // n'a pas besoin d'évincer — c'est un parasite qui est dropé en bout
        // de chaîne. La trace de drop confirme que le budget a été atteint.
        assertTrue(result.truncationReasons.any { it.contains("Parasite") || it.contains("drop mock") },
            "au moins un parasite doit être dropé (preuve que le budget=3 est saturé). " +
                "Vu: ${result.truncationReasons}")
        // Au plus 2 parasites sur 3 dans les mocks (CalledSvc + 2 = budget=3).
        val parasitesInMocks = result.mocks.keys.count { it.contains("Parasite") }
        assertTrue(parasitesInMocks <= 2,
            "au plus 2 parasites dans les mocks (CalledSvc + 2 = budget=3). " +
                "Vu: ${result.mocks.keys}")
    }

    @Test
    fun `Bug M — return type of method called on returned mock is essential`() {
        // Cas chaîné : `service.find().getValue()`.
        // Bug M doit marquer Service ET ResultDTO essentiels :
        //   • Service est l'instanceCall.targetType de `.find()`
        //   • ResultDTO est l'instanceCall.targetType de `.getValue()`
        val fake = fixture {
            klass("$pkg.ResultDTO2", isInterface = true) {
                method("getValue", returns = T("java.lang.String"))
            }
            klass("$pkg.Service2", isInterface = true) {
                method("find", returns = T("$pkg.ResultDTO2"))
            }
            klass("$pkg.ParA2", isInterface = true)
            klass("$pkg.ParB2", isInterface = true)
            klass("$pkg.Ctrl2") {
                field("service", T("$pkg.Service2"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("parA", T("$pkg.ParA2"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("parB", T("$pkg.ParB2"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.service.find().getValue();") {
                    reads("$pkg.Ctrl2", "service")
                    calls("$pkg.Service2", "find")
                    calls("$pkg.ResultDTO2", "getValue")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl2")!!
        val target = fake.listMethodsOf("$pkg.Ctrl2").single { it.name == "handle" }
        val tightBudget = StrategyConfig(budget = Budget(maxMockCount = 2))
        val result = strategy.extractCore(fake, DefaultClassifier(), tightBudget, sut, target)

        assertTrue("$pkg.Service2" in result.mocks.keys,
            "Service2 (appelé directement) doit rester. Vu: ${result.mocks.keys}")
        assertTrue("$pkg.ResultDTO2" in result.mocks.keys,
            "ResultDTO2 (méthode appelée sur le retour) doit rester. " +
                "Vu: ${result.mocks.keys}")
        // ParA / ParB non essentiels et budget=2 → tous deux évincés.
        assertFalse("$pkg.ParA2" in result.mocks.keys)
        assertFalse("$pkg.ParB2" in result.mocks.keys)
    }

    @Test
    fun `Bug P — inherited trivial getter field type is essential`() {
        // Scénario inspiré du cas BaseControleur :
        //   class Base { SessionModel userSession; SessionModel getUserSession() { return this.userSession; } }
        //   class Ctrl extends Base { ... target() { this.getUserSession().getPageData(...); } }
        // Le getter trivial sur Base ne déclare PAS @Autowired sur userSession,
        // donc sans Bug P le champ serait dropé de usefulFields. Et son type
        // SessionModel serait vulnérable à l'éviction.
        val fake = fixture {
            klass("$pkg.PageDataDTOp", isInterface = true)
            klass("$pkg.SessionModel", isInterface = true) {
                method("getPageData", returns = T("$pkg.PageDataDTOp"))
            }
            klass("$pkg.ParA3", isInterface = true)
            klass("$pkg.ParB3", isInterface = true)
            klass("$pkg.ParC3", isInterface = true)
            klass("$pkg.Base") {
                // userSession N'A PAS @Autowired — set via setter en prod.
                field("userSession", T("$pkg.SessionModel"))
                method("getUserSession", returns = T("$pkg.SessionModel"),
                    body = "return this.userSession;") {
                    reads("$pkg.Base", "userSession")
                }
            }
            klass("$pkg.Ctrl3") {
                field("parA", T("$pkg.ParA3"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("parB", T("$pkg.ParB3"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("parC", T("$pkg.ParC3"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.getUserSession().getPageData().toString();") {
                    calls("$pkg.Base", "getUserSession")
                    calls("$pkg.SessionModel", "getPageData")
                }
            }
            superChain("$pkg.Ctrl3", "$pkg.Base")
        }
        val sut = fake.resolveClass("$pkg.Ctrl3")!!
        val target = fake.listMethodsOf("$pkg.Ctrl3").single { it.name == "handle" }
        val tightBudget = StrategyConfig(budget = Budget(maxMockCount = 3))
        val result = strategy.extractCore(fake, DefaultClassifier(), tightBudget, sut, target)

        // Bug P #1 — le champ hérité userSession entre dans usefulFields via
        // la propagation getter → activeFields.
        assertTrue(result.fields.any { it.name == "userSession" },
            "Bug P : userSession (champ hérité accédé via getter trivial) " +
                "doit être listé dans les champs utiles. Vu: ${result.fields.map { it.name }}")

        // Bug P #2 — SessionModel (type du champ retourné par le getter) est
        // essentiel et survit malgré les 3 @Autowired qui saturent le budget.
        assertTrue("$pkg.SessionModel" in result.mocks.keys,
            "Bug P : SessionModel (type du champ retourné par le getter) " +
                "doit être préservé. Vu: ${result.mocks.keys}")
    }

    @Test
    fun `Bug P — trivial getter does not pollute internalLogics`() {
        // Garde-fou : Bug P ne doit PAS faire entrer le getter trivial dans
        // internalLogics. Le LLM n'a pas besoin de voir `return this.userSession;`
        // — il lui suffit que SessionModel soit mocké + userSession soit listé
        // comme champ utile.
        val fake = fixture {
            klass("$pkg.Sess4", isInterface = true) {
                method("doX", returns = T("java.lang.String"))
            }
            klass("$pkg.Base4") {
                field("sess", T("$pkg.Sess4"))
                method("getSess", returns = T("$pkg.Sess4"),
                    body = "return this.sess;") {
                    reads("$pkg.Base4", "sess")
                }
            }
            klass("$pkg.Ctrl4") {
                method("run", returns = T("java.lang.String"),
                    body = "return this.getSess().doX();") {
                    calls("$pkg.Base4", "getSess")
                    calls("$pkg.Sess4", "doX")
                }
            }
            superChain("$pkg.Ctrl4", "$pkg.Base4")
        }
        val sut = fake.resolveClass("$pkg.Ctrl4")!!
        val target = fake.listMethodsOf("$pkg.Ctrl4").single { it.name == "run" }
        val result = strategy.extractCore(fake, DefaultClassifier(),
            StrategyConfig(), sut, target)

        // Le getter trivial ne doit pas figurer dans internalLogics — c'est
        // l'invariant existant que Bug P préserve.
        val getterKey = result.internalLogics.keys.firstOrNull { it.endsWith("#getSess()") }
        assertTrue(getterKey == null,
            "getter trivial ne doit pas être listé dans internalLogics. " +
                "Vu: ${result.internalLogics.keys}")
        // Mais le type retourné est bien mocké via Bug P.
        assertTrue("$pkg.Sess4" in result.mocks.keys,
            "Sess4 doit quand même être dans les mocks. Vu: ${result.mocks.keys}")
    }
}

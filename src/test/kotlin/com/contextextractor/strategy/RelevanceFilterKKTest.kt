package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4.3 Bug KK — explosion du volume de contexte.
//
// Vrai problème Astrea case 4.4 (`SaisieMessage01Controleur#getMethodeControle`) :
// la hiérarchie porte ~170 champs (125 sur AbstractSaisieMessageControleur).
// `seedSutFields` les enregistrait TOUS, puis `propagateDataStructureFields`
// descendait à point fixe dans chaque type non appelé → le graphe d'entités
// JPA entier entrait dans le prompt : 260 dataStructures + 31 mocks dont ~20
// fantômes. Le user a validé le case 4.2 « avec grande difficulté à cause du
// volume de contexte ».
//
// Trois gardes V1.4.3 :
//   KK-1 — la descente DTO ne part que des types à usage FORT (param/retour
//          de target, appel, static, instanciation, stub return) ;
//   KK-2 — la descente est bornée en profondeur (maxDtoFieldDepth, défaut 2) ;
//   KK-3 — une réf « champ déclaré uniquement » (AsFieldOfSut pur) n'est
//          matérialisée ni en mock ni en dataStructure, SAUF si son champ a
//          survécu au filtre d'usage transitif (cas 9.1 : repo @Autowired
//          consommé par le protocole d'init — verrouillé par Case91*Test).
class RelevanceFilterKKTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugkk"

    // Hiérarchie réduite type Astrea : un service utilisé par la cible, un
    // service hérité jamais touché, un modele hérité jamais touché dont le
    // type a des champs imbriqués.
    private fun unusedFieldsFixture(): FakeIntrospector = fixture {
        klass("$pkg.BaseCtrl") {
            field("unusedService", T("$pkg.UnusedService"),
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
            field("unusedModele", T("$pkg.UnusedModele"))
        }
        klass("$pkg.Ctrl", superFqn = "$pkg.BaseCtrl") {
            field("usedService", T("$pkg.UsedService"),
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
            method("target", returns = T("$pkg.ResultDTO"),
                body = "return this.usedService.compute();") {
                reads("$pkg.Ctrl", "usedService")
                calls("$pkg.UsedService", "compute")
            }
        }
        klass("$pkg.UnusedService", isInterface = true) {
            method("ping", returns = T("void"))
        }
        klass("$pkg.UsedService", isInterface = true) {
            method("compute", returns = T("$pkg.ResultDTO"))
        }
        klass("$pkg.UnusedModele") {
            field("nested", T("$pkg.UnusedNestedDTO"))
            method("getNested", returns = T("$pkg.UnusedNestedDTO"))
        }
        klass("$pkg.UnusedNestedDTO") {
            field("label", T("java.lang.String"))
            method("getLabel", returns = T("java.lang.String"))
        }
        klass("$pkg.ResultDTO") {
            field("amount", T("java.lang.Long"))
            method("getAmount", returns = T("java.lang.Long"))
        }
        superChain("$pkg.Ctrl", "$pkg.BaseCtrl")
    }

    @Test
    fun `KK-3 — unused inherited @Autowired field type is not mocked`() {
        val fake = unusedFieldsFixture()
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.UsedService" in result.mocks,
            "le service appelé par la cible reste mocké. Mocks : ${result.mocks.keys}")
        assertFalse("$pkg.UnusedService" in result.mocks,
            "UnusedService n'est jamais touché par le chemin de la cible — un " +
                "@Mock fantôme gonfle le prompt et le test. Mocks : ${result.mocks.keys}")
    }

    @Test
    fun `KK-1 — unused field's type and its nested fields are not dataStructures`() {
        val fake = unusedFieldsFixture()
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.ResultDTO" in result.dataStructures,
            "ResultDTO est le retour stubé de compute() — il reste matérialisé. " +
                "DataStructures : ${result.dataStructures.keys}")
        assertFalse("$pkg.UnusedModele" in result.dataStructures,
            "UnusedModele : champ hérité jamais touché → hors du flux de données " +
                "de la cible. DataStructures : ${result.dataStructures.keys}")
        assertFalse("$pkg.UnusedNestedDTO" in result.dataStructures,
            "UnusedNestedDTO n'est atteignable QUE via la descente dans " +
                "UnusedModele — c'est l'explosion type-graphe JPA du case 4.4. " +
                "DataStructures : ${result.dataStructures.keys}")
    }

    @Test
    fun `KK-2 — DTO field descent is depth-capped`() {
        // Chaîne LevelZero(param) → LevelOne → LevelTwo → LevelThree.
        // Avec maxDtoFieldDepth=2 : LevelZero (d0) et LevelOne (d1) sont
        // crawlés, LevelTwo (d2) est référencé/matérialisé mais PAS crawlé →
        // LevelThree n'entre jamais. Le résultat est marqué tronqué.
        val fake = fixture {
            klass("$pkg.Ctrl2") {
                field("service", T("$pkg.DeepService"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("target", returns = T("void"),
                    body = "this.service.accept(input);") {
                    param("input", T("$pkg.LevelZero"))
                    reads("$pkg.Ctrl2", "service")
                    calls("$pkg.DeepService", "accept", "$pkg.LevelZero")
                }
            }
            klass("$pkg.DeepService", isInterface = true) {
                method("accept", returns = T("void")) { param("z", T("$pkg.LevelZero")) }
            }
            klass("$pkg.LevelZero") {
                field("levelOne", T("$pkg.LevelOne"))
                method("getLevelOne", returns = T("$pkg.LevelOne"))
            }
            klass("$pkg.LevelOne") {
                field("levelTwo", T("$pkg.LevelTwo"))
                method("getLevelTwo", returns = T("$pkg.LevelTwo"))
            }
            klass("$pkg.LevelTwo") {
                field("levelThree", T("$pkg.LevelThree"))
                method("getLevelThree", returns = T("$pkg.LevelThree"))
            }
            klass("$pkg.LevelThree") {
                field("label", T("java.lang.String"))
                method("getLabel", returns = T("java.lang.String"))
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl2")!!
        val target = fake.listMethodsOf("$pkg.Ctrl2").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.LevelZero" in result.dataStructures,
            "param de la cible — d0. DataStructures : ${result.dataStructures.keys}")
        assertTrue("$pkg.LevelOne" in result.dataStructures,
            "champ de LevelZero — d1, crawlé. DataStructures : ${result.dataStructures.keys}")
        assertTrue("$pkg.LevelTwo" in result.dataStructures,
            "champ de LevelOne — d2, référencé et matérialisé (ses champs " +
                "restent listés via capturePhase3). DataStructures : ${result.dataStructures.keys}")
        assertFalse("$pkg.LevelThree" in result.dataStructures,
            "LevelTwo (d2) ne doit plus être crawlé → LevelThree n'entre pas. " +
                "DataStructures : ${result.dataStructures.keys}")
        assertTrue(result.truncationReasons.any { it.contains("depth-capped") },
            "la coupure de profondeur doit être tracée dans truncationReasons. " +
                "Vu : ${result.truncationReasons}")
    }
}

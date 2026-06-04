package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bug T — Filet textuel : un champ référencé dans le corps de target via
// `this.<name>` ou `<name>.` doit être DÉTECTÉ même si PSI rate à la fois
// l'accès field ET l'instance call. Cas production déclencheur
// (SupervisionDeltaVecControleur#rechercher) :
//   this.supervisionDeltaVecModele.setAfficherResultats(true);
//   this.supervisionDeltaVecModele.setLienExportCsvVisible(
//       CollectionUtils.isNotEmpty(this.lignesResultatSupervisionDeltaVecDTO));
//
// Avant Bug T : PSI rate la chaîne imbriquée → ni dans listFieldAccesses
// (donc absent de transitiveUsage), ni dans listMethodCalls (donc absent
// des targetAnalysis.instanceCalls qui alimentent Bug M / essentialMockTypes).
// Résultat : le filtre filteredFields drop le champ MOCKITO_INJECT_MOCKS,
// la réconciliation drop le mock, le LLM confond avec le mock de nom proche
// `tableauSupervisionDeltaVecModele` → erreurs de compilation.
//
// Avec Bug T : scan textuel du body de target contre `listFields` de toute
// la hiérarchie. Tout champ dont `this.<name>` ou `<name>.` apparaît dans
// le texte est ajouté à `activeFields` ET son type à `essentialMockTypes`.
// Le filet est plus bas niveau que Bug M (qui dépend de PSI).
class RecursiveDeepStrategyBugTTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugt"

    @Test
    fun `Bug T — Autowired field with PSI miss on both reads and calls survives`() {
        // Setup : @Autowired field (strategie MOCKITO_INJECT_MOCKS auto),
        // body référence `this.modele.setX(true)`, mais PSI rate :
        //   - aucun reads(...) → transitiveUsage vide
        //   - aucun calls(...) → instanceCalls vide → Bug M ne marque pas Modele essentiel
        // Sans Bug T : Bug S ne sait pas protéger → mock dropé.
        // Avec Bug T : scan textuel ajoute Modele à essentialMockTypes → Bug S sauve.
        val fake = fixture {
            klass("$pkg.Modele", isInterface = true) {
                method("setX", returns = T("void")) {
                    param("v", T("boolean"))
                }
            }
            klass("$pkg.Ctrl") {
                field("modele", T("$pkg.Modele"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("void"),
                    body = "this.modele.setX(true);")
                // Pas de calls() ni reads() — PSI simulé défaillant.
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue(result.fields.any { it.name == "modele" },
            "Bug T : champ `modele` détecté via scan textuel. " +
                "Vu: ${result.fields.map { it.name }}")
        assertTrue("$pkg.Modele" in result.mocks.keys,
            "Bug T : mock Modele préservé (Bug T → essentialMockTypes → Bug S → réconciliation). " +
                "Vu: ${result.mocks.keys}")
    }

    @Test
    fun `Bug T — chained this dot a setX of this dot b method PSI miss on both`() {
        // Pattern strict de la prod :
        //   this.modeleA.setX(this.modeleB.getY());
        // PSI rate les deux dans une chaîne imbriquée.
        val fake = fixture {
            klass("$pkg.ModeleB", isInterface = true) {
                method("getY", returns = T("java.util.List"))
            }
            klass("$pkg.ModeleA", isInterface = true) {
                method("setX", returns = T("void")) {
                    param("y", T("java.util.List"))
                }
            }
            klass("$pkg.Ctrl2") {
                field("modeleA", T("$pkg.ModeleA"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("modeleB", T("$pkg.ModeleB"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("void"),
                    body = "this.modeleA.setX(this.modeleB.getY());")
                // PSI simulé défaillant sur tout.
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl2")!!
        val target = fake.listMethodsOf("$pkg.Ctrl2").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue(result.fields.any { it.name == "modeleA" },
            "modeleA détecté. Vu: ${result.fields.map { it.name }}")
        assertTrue(result.fields.any { it.name == "modeleB" },
            "modeleB détecté. Vu: ${result.fields.map { it.name }}")
        assertTrue("$pkg.ModeleA" in result.mocks.keys,
            "mock ModeleA présent. Vu: ${result.mocks.keys}")
        assertTrue("$pkg.ModeleB" in result.mocks.keys,
            "mock ModeleB présent. Vu: ${result.mocks.keys}")
    }

    @Test
    fun `Bug T — field never mentioned in body stays dropped (no regression)`() {
        // Verrou inverse : un @Autowired parasite qui n'apparaît PAS dans le
        // body ne doit pas être ressuscité par Bug T. Le scan textuel ne
        // crée pas de faux positif.
        val fake = fixture {
            klass("$pkg.Svc", isInterface = true) {
                method("doIt", returns = T("java.lang.String"))
            }
            klass("$pkg.Parasite", isInterface = true)
            klass("$pkg.Ctrl3") {
                field("svc", T("$pkg.Svc"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("parasite", T("$pkg.Parasite"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.svc.doIt();") {
                    reads("$pkg.Ctrl3", "svc")
                    calls("$pkg.Svc", "doIt")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl3")!!
        val target = fake.listMethodsOf("$pkg.Ctrl3").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.Svc" in result.mocks.keys,
            "Svc essentiel doit rester. Vu: ${result.mocks.keys}")
        assertTrue("$pkg.Parasite" !in result.mocks.keys,
            "Parasite jamais référencé dans le body NE doit PAS être maintenu. " +
                "Vu: ${result.mocks.keys}")
    }

    @Test
    fun `Bug T — bare field reference field dot method without this also detected`() {
        // Variante Java sans `this.` explicite : `modele.setX()` au lieu de
        // `this.modele.setX()`. Le scan accepte les deux formes.
        val fake = fixture {
            klass("$pkg.Modele2", isInterface = true) {
                method("setX", returns = T("void"))
            }
            klass("$pkg.Ctrl4") {
                field("modele", T("$pkg.Modele2"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("void"),
                    body = "modele.setX();")
                // PSI simulé défaillant.
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl4")!!
        val target = fake.listMethodsOf("$pkg.Ctrl4").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue(result.fields.any { it.name == "modele" },
            "Bug T : forme `modele.setX()` sans `this.` détectée. " +
                "Vu: ${result.fields.map { it.name }}")
        assertTrue("$pkg.Modele2" in result.mocks.keys,
            "Bug T : mock Modele2 présent. Vu: ${result.mocks.keys}")
    }
}

package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.model.ConstructionPattern
import com.contextextractor.core.model.init.InitStrategy
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.Tg
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4.4 — verrous du round Astrea 4.4-bis / régression 4.1.
//
// Bug OO (RÉGRESSION 4.1 causée par V1.4.3 Bug KK) : en bornant la descente
//   DTO aux usages forts, les type-args des PARAMÈTRES de target étaient
//   restés en usage faible (AsTypeArgOfReference) → TriDTO non crawlé →
//   `OrdreTriEnum [ENUM]` absent du prompt → le LLM a halluciné `ASC`
//   (résurrection du bug R3-A que DTO_ENUM_VALUES devait empêcher).
//   Même famille : les exceptions déclarées (`throws`) de target n'étaient
//   jamais enregistrées → `new AstreaFonctionnelleException(String)` inventé.
//
// Bug MM : setter générique hérité (`setModele(M)`) non reconnu après la
//   substitution Bug JJ (param `M` ≠ champ `SaisieMessage01Modele`) → branche
//   6 CALL_PUBLIC_WITH_ARGS → la réconciliation supprimait le mock du modele
//   et tous ses stubs → le LLM construisait un vrai modele avec des setters
//   inventés.
//
// Bug NN : promotion STUB_VIA_SPY sur une méthode PRIVÉE → le pattern
//   `doReturn(...).when(sut).methodePrivee(...)` ne compile pas
//   (« has private access »).
class AstreaRound44FixesTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.round44"

    // ── Bug OO — type-arg d'un param de target = surface du test ─────────────

    @Test
    fun `OO — param type-arg DTO is crawled and its enum field is materialized`() {
        val fake = fixture {
            klass("$pkg.OrdreTriEnum", isEnum = true,
                enumValues = listOf("ASCENDANT", "DESCENDANT"))
            klass("$pkg.TriDTO") {
                field("colonne", T("java.lang.String"))
                field("ordre", T("$pkg.OrdreTriEnum"))
                method("getOrdre", returns = T("$pkg.OrdreTriEnum"))
            }
            klass("$pkg.Service", isInterface = true) {
                method("rechercher", returns = T("java.util.List")) {
                    param("tris", Tg("java.util.List", T("$pkg.TriDTO")))
                }
            }
            klass("$pkg.Ctrl") {
                field("service", T("$pkg.Service"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("rechercher", returns = T("java.util.List"),
                    body = "return this.service.rechercher(tris);") {
                    param("tris", Tg("java.util.List", T("$pkg.TriDTO")))
                    reads("$pkg.Ctrl", "service")
                    calls("$pkg.Service", "rechercher", "java.util.List")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "rechercher" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.TriDTO" in result.dataStructures,
            "TriDTO (type-arg du param `List<TriDTO>`) doit être matérialisé — " +
                "le test doit le CONSTRUIRE pour appeler target. " +
                "DataStructures : ${result.dataStructures.keys}")
        val enum = result.dataStructures["$pkg.OrdreTriEnum"]
        assertNotNull(enum,
            "OrdreTriEnum (champ de TriDTO) doit être crawlé — son absence a " +
                "fait halluciner `ASC` au LLM (régression Astrea 4.1). " +
                "DataStructures : ${result.dataStructures.keys}")
        assertTrue("ASCENDANT" in enum!!.enumValues,
            "les constantes réelles de l'enum doivent être exposées (R3-A)")
    }

    @Test
    fun `OO — declared throws of target is materialized with its constructors`() {
        val fake = fixture {
            klass("$pkg.FonctionnelleException") {
                method("<init>", visibility = "public") {
                    param("code", T("java.lang.Integer"))
                    param("origine", T("java.lang.String"))
                }
            }
            klass("$pkg.Service2", isInterface = true) {
                method("calculer", returns = T("int"))
            }
            klass("$pkg.Ctrl2") {
                field("service2", T("$pkg.Service2"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("calculer", returns = T("int"),
                    declaredThrows = listOf("$pkg.FonctionnelleException"),
                    body = "return this.service2.calculer();") {
                    reads("$pkg.Ctrl2", "service2")
                    calls("$pkg.Service2", "calculer")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl2")!!
        val target = fake.listMethodsOf("$pkg.Ctrl2").single { it.name == "calculer" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val exc = result.dataStructures["$pkg.FonctionnelleException"]
        assertNotNull(exc,
            "l'exception déclarée par target doit être matérialisée : le test " +
                "doit pouvoir écrire `thenThrow(new FonctionnelleException(...))`. " +
                "DataStructures : ${result.dataStructures.keys}")
        assertEquals(ConstructionPattern.CONSTRUCTOR, exc!!.pattern)
        assertTrue(exc.constructors.isNotEmpty(),
            "Bug LL : la signature du ctor doit être capturée — sans elle le LLM " +
                "invente `new FonctionnelleException(String)`")
        assertEquals(listOf("java.lang.Integer", "java.lang.String"),
            exc.constructors.single().parameters.map { it.type.fqName })
    }

    // ── Bug MM — setter générique hérité + mock conservé ─────────────────────

    @Test
    fun `MM — inherited generic setter is recognized and the modele mock survives`() {
        // Reproduit 4.4 : champ `modele` substitué (ConcreteModele) par Bug JJ,
        // setter hérité déclaré `setModele(M)` — param non substitué.
        val fake = fixture {
            klass("$pkg.BaseCtrl3") {
                field("modele", T("$pkg.ConcreteModele"))
                method("setModele", visibility = "public") {
                    param("modele", T("M"))
                    assigns("$pkg.BaseCtrl3", "modele", rhsExpression = "modele")
                }
            }
            klass("$pkg.Ctrl3", superFqn = "$pkg.BaseCtrl3") {
                method("target", returns = T("java.util.List"),
                    body = "return this.modele.getSections();") {
                    reads("$pkg.BaseCtrl3", "modele")
                    calls("$pkg.ConcreteModele", "getSections")
                }
            }
            klass("$pkg.ConcreteModele") {
                method("getSections", returns = T("java.util.List"))
            }
            superChain("$pkg.Ctrl3", "$pkg.BaseCtrl3")
        }
        val sut = fake.resolveClass("$pkg.Ctrl3")!!
        val target = fake.listMethodsOf("$pkg.Ctrl3").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val strat = result.initProtocol["modele"]?.recommendedStrategy
        assertTrue(strat is InitStrategy.SETTER,
            "MM-a : `setModele(M)` doit être reconnu comme SETTER malgré la " +
                "variable de type nue (le nom dérivé matche le champ). Vu : $strat")
        assertTrue("$pkg.ConcreteModele" in result.mocks,
            "MM-b : le mock du modele doit SURVIVRE à la réconciliation — le " +
                "protocole SETTER l'injecte, ses stubs (getSections) sont requis. " +
                "Mocks : ${result.mocks.keys}")
    }

    // ── Bug NN — jamais de spy sur une méthode privée ────────────────────────

    private fun chainedReturnFixture(visibility: String): Pair<RecursiveDeepStrategy, com.contextextractor.core.model.ContextResult> {
        val p = "$pkg.nn$visibility"
        val fake = fixture {
            klass("$p.Mapper", isInterface = true) {
                method("normalize", returns = T("$p.Item")) { param("i", T("$p.Item")) }
            }
            klass("$p.Item") {
                method("getCode", returns = T("java.lang.String"))
            }
            klass("$p.Ctrl4") {
                field("mapper", T("$p.Mapper"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("target", returns = T("java.lang.String"),
                    body = "Item i = this.prepare(input); return i.getCode();") {
                    param("input", T("$p.Item"))
                    calls("$p.Ctrl4", "prepare", "$p.Item")
                    calls("$p.Item", "getCode")
                }
                method("prepare", returns = T("$p.Item"), visibility = visibility,
                    body = "return this.mapper.normalize(item);") {
                    param("item", T("$p.Item"))
                    reads("$p.Ctrl4", "mapper")
                    calls("$p.Mapper", "normalize", "$p.Item")
                }
            }
        }
        val sut = fake.resolveClass("$p.Ctrl4")!!
        val target = fake.listMethodsOf("$p.Ctrl4").single { it.name == "target" }
        return strategy to strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
    }

    @Test
    fun `NN — private chained-return method is NOT promoted to stub-via-spy`() {
        val (_, result) = chainedReturnFixture("private")
        val key = result.internalLogics.keys.single { it.contains("#prepare(") }
        assertFalse(result.internalLogics.getValue(key).stubViaSpy,
            "Mockito ne peut pas stubber une méthode privée via spy — la " +
                "prescription `doReturn(...).when(sut).prepare(...)` ne compile " +
                "pas (Astrea 4.4 : gererPreRequisChampHeureAudience)")
    }

    @Test
    fun `NN control — public chained-return method is still promoted to spy`() {
        val (_, result) = chainedReturnFixture("public")
        val key = result.internalLogics.keys.single { it.contains("#prepare(") }
        assertTrue(result.internalLogics.getValue(key).stubViaSpy,
            "non-régression Bug #C : la promotion spy reste active pour une " +
                "méthode publique dont le retour est chaîné")
    }
}

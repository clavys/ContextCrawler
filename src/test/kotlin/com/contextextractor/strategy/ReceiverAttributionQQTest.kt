package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.constraints.BaseConstraints
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.Tg
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4.5 — Bug QQ (attribution au receveur concret) + Bug PP (jamais de
// `when()` sur un objet construit).
//
// Vrai bug Astrea 4.4 (3e re-run) : les appels d'un MÊME receveur
// (`this.modele`) se résolvent sur leurs classes DÉCLARANTES respectives —
// `getSectionPersonne()` → AbstractSaisieMessageModele,
// `getListeSectionsDemandeExtraitModele()` → SaisieMessage01Modele. Le prompt
// prescrivait DEUX mocks pour UN objet runtime, et les retours génériques
// restaient ceux de la déclaration (`List` raw / `List<T>`) → le LLM passait
// `List<AbstractSectionPersonneModele>` à un `thenReturn` qui attendait la
// liaison concrète → 4 erreurs de compile (génériques invariants).
//
// Fix : MethodCall porte la vue call-site (receiverTypeFqn + resolvedReturnType,
// calculées par PSI avec substitution générique) ; P1 attribue l'AsCallTarget
// au receveur ; le materializer et propagateStubReturns utilisent le retour
// résolu.
class ReceiverAttributionQQTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugqq"

    // Reproduit la forme 4.4 : champ `modele : ConcreteModele` (substitué par
    // Bug JJ), méthode getSections DÉCLARÉE sur BaseModele avec retour List<T>
    // déclaré raw, mais vue au call-site comme List<ConcreteSection>.
    private fun genericModeleFixture() = fixture {
        klass("$pkg.BaseModele") {
            method("getSections", returns = T("java.util.List"))
        }
        klass("$pkg.ConcreteModele", superFqn = "$pkg.BaseModele") {
            method("getDemandes", returns = Tg("java.util.List", T("$pkg.DemandeModele")))
        }
        // Ctor no-arg déclaré : sans lui, la règle 11bis (R3-1) classerait ces
        // stub-returns exclusifs en MOCK — les modeles Astrea ont un no-arg.
        klass("$pkg.ConcreteSection") {
            field("libelle", T("java.lang.String"))
            method("<init>", visibility = "public")
            method("getLibelle", returns = T("java.lang.String"))
        }
        klass("$pkg.DemandeModele") {
            field("code", T("java.lang.String"))
            method("<init>", visibility = "public")
            method("getCode", returns = T("java.lang.String"))
        }
        klass("$pkg.BaseCtrl") {
            field("modele", T("$pkg.ConcreteModele"))
            method("setModele", visibility = "public") {
                param("modele", T("M"))
                assigns("$pkg.BaseCtrl", "modele", rhsExpression = "modele")
            }
        }
        klass("$pkg.Ctrl", superFqn = "$pkg.BaseCtrl") {
            method("target", returns = T("void"),
                body = "this.modele.getSections(); this.modele.getDemandes();") {
                reads("$pkg.BaseCtrl", "modele")
                calls("$pkg.BaseModele", "getSections",
                    receiverType = "$pkg.ConcreteModele",
                    resolvedReturn = Tg("java.util.List", T("$pkg.ConcreteSection")))
                calls("$pkg.ConcreteModele", "getDemandes",
                    receiverType = "$pkg.ConcreteModele",
                    resolvedReturn = Tg("java.util.List", T("$pkg.DemandeModele")))
            }
        }
        superChain("$pkg.Ctrl", "$pkg.BaseCtrl")
        superChain("$pkg.ConcreteModele", "$pkg.BaseModele")
    }

    @Test
    fun `QQ — calls on one receiver converge on ONE mock (the receiver's type)`() {
        val fake = genericModeleFixture()
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val modeleMock = result.mocks["$pkg.ConcreteModele"]
        assertNotNull(modeleMock,
            "le mock doit porter le type du RECEVEUR (celui injecté via setModele). " +
                "Mocks : ${result.mocks.keys}")
        assertFalse("$pkg.BaseModele" in result.mocks,
            "la classe déclarante NE doit PAS avoir son propre mock — au runtime " +
                "il n'y a qu'un objet, les stubs sur un 2e mock seraient orphelins. " +
                "Mocks : ${result.mocks.keys}")
        val names = modeleMock!!.requiredSignatures.map { it.name }.toSet()
        assertTrue("getSections" in names && "getDemandes" in names,
            "les DEUX stubs doivent converger sur le mock du receveur. Vu : $names")
    }

    @Test
    fun `QQ — stub signature carries the call-site resolved return type`() {
        val fake = genericModeleFixture()
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val getSections = result.mocks["$pkg.ConcreteModele"]!!
            .requiredSignatures.single { it.name == "getSections" }
        assertTrue(getSections.returnType.typeArgs.map { it.fqName } == listOf("$pkg.ConcreteSection"),
            "le retour de la signature à stubber doit être la liaison concrète " +
                "vue au call-site (List<ConcreteSection>), pas la déclaration raw — " +
                "sinon le LLM devine le type d'élément et le thenReturn ne compile " +
                "pas (génériques invariants). Vu : ${getSections.returnType}")
    }

    @Test
    fun `QQ — resolved return type-args feed the stub-return propagation`() {
        val fake = genericModeleFixture()
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.ConcreteSection" in result.dataStructures,
            "ConcreteSection (type-arg du retour RÉSOLU, invisible dans la " +
                "déclaration raw) doit être matérialisé — le test doit pouvoir " +
                "peupler la liste. DataStructures : ${result.dataStructures.keys}")
    }

    @Test
    fun `PP — BaseConstraints forbids stubbing instances created with new`() {
        assertTrue(BaseConstraints.TEXT.contains("NEVER call `when(...)`"),
            "la règle anti when()-sur-objet-réel doit être universelle (BaseConstraints)")
        assertTrue(BaseConstraints.TEXT.contains("MissingMethodInvocationException"),
            "la règle doit expliquer la conséquence runtime")
    }
}

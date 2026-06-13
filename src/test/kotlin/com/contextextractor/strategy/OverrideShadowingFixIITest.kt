package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4.3 Bug II — collision override/parent dans les maps indexées par
// MethodSignature.
//
// Vrai bug Astrea case 4.4 : `SaisieMessage01Controleur#getMethodeControle`
// (gros switch 30/31/32/25/33/63) override
// `AbstractSaisieMessageControleurPP#getMethodeControle` (corps SEGMENT_30
// seul). Les deux méthodes produisaient des `MethodSignature` ÉGALES (pas de
// classe déclarante dans le modèle) :
//   - côté PSI, `methodCache[signature] = psiMethod` écrasait l'override par
//     la version parente dès que la hiérarchie était listée (BLOC 5/7) →
//     P1 crawlait le corps du PARENT → les sous-méthodes des 5 autres
//     branches n'entraient jamais dans le graphe → le LLM, sommé de couvrir
//     le switch, hallucinait imports et méthodes (6 erreurs de compile).
//   - côté FakeIntrospector, `callsByMethod.getOrPut(signature)` FUSIONNAIT
//     les appels des deux corps sous la même clé.
//
// Fix : `MethodSignature.declaredIn` (FQN de la classe déclarante) — renseigné
// par JavaPsiIntrospector.toSignature() ET par le DSL fixture. Deux méthodes
// de même forme déclarées dans deux classes ne sont plus égales.
class OverrideShadowingFixIITest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugii"

    // Parent déclaré AVANT l'enfant — pré-fix, c'est l'ordre qui produisait la
    // fusion des calls sous une clé unique (getOrPut accumulant les deux corps).
    private fun overrideFixture(): FakeIntrospector = fixture {
        klass("$pkg.ParentCtrl") {
            field("parentService", T("$pkg.ParentService"),
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
            method("getHandler", returns = T("$pkg.HandlerDTO"), visibility = "protected",
                body = "return this.parentService.small(memo);") {
                param("memo", T("$pkg.MemoDTO"))
                reads("$pkg.ParentCtrl", "parentService")
                calls("$pkg.ParentService", "small", "$pkg.MemoDTO")
            }
        }
        klass("$pkg.ChildCtrl", superFqn = "$pkg.ParentCtrl") {
            field("childService", T("$pkg.ChildService"),
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
            method("getHandler", returns = T("$pkg.HandlerDTO"), visibility = "protected",
                body = "return this.childService.big(memo);") {
                param("memo", T("$pkg.MemoDTO"))
                reads("$pkg.ChildCtrl", "childService")
                calls("$pkg.ChildService", "big", "$pkg.MemoDTO")
            }
        }
        klass("$pkg.ParentService", isInterface = true) {
            method("small", returns = T("$pkg.HandlerDTO")) { param("m", T("$pkg.MemoDTO")) }
        }
        klass("$pkg.ChildService", isInterface = true) {
            method("big", returns = T("$pkg.HandlerDTO")) { param("m", T("$pkg.MemoDTO")) }
        }
        klass("$pkg.HandlerDTO") {
            method("getId", returns = T("java.lang.Long"))
        }
        klass("$pkg.MemoDTO") {
            method("getRang", returns = T("int"))
        }
        superChain("$pkg.ChildCtrl", "$pkg.ParentCtrl")
    }

    @Test
    fun `Bug II — same-shape signatures from different classes are distinct`() {
        val fake = overrideFixture()
        val childSig = fake.listMethodsOf("$pkg.ChildCtrl").single { it.name == "getHandler" }
        val parentSig = fake.listMethodsOf("$pkg.ParentCtrl").single { it.name == "getHandler" }

        assertEquals("$pkg.ChildCtrl", childSig.declaredIn)
        assertEquals("$pkg.ParentCtrl", parentSig.declaredIn)
        assertNotEquals(childSig, parentSig,
            "même nom + mêmes params + même retour, mais classes déclarantes " +
                "différentes → les signatures NE doivent PAS être égales " +
                "(sinon toute map indexée par MethodSignature mélange les corps)")
        // canonical() reste volontairement sans la classe — c'est la clé de
        // dédup BFS, owner-qualifiée séparément par les callers.
        assertEquals(childSig.canonical(), parentSig.canonical())
    }

    @Test
    fun `Bug II — introspecting the override returns the override's calls only`() {
        val fake = overrideFixture()
        val childSig = fake.listMethodsOf("$pkg.ChildCtrl").single { it.name == "getHandler" }

        val calls = fake.listMethodCalls(childSig)
        assertEquals(1, calls.size,
            "le corps de l'override fait UN appel (ChildService.big) — une " +
                "liste plus longue signifie que les calls du parent ont fusionné " +
                "sous la même clé. Vu : $calls")
        assertEquals("$pkg.ChildService", calls.single().targetType)
        assertTrue(fake.readMethodBody(childSig).contains("childService"),
            "readMethodBody doit retourner le corps de l'OVERRIDE")
    }

    @Test
    fun `Bug II — pipeline crawls the override body, not the parent's`() {
        val fake = overrideFixture()
        val sut = fake.resolveClass("$pkg.ChildCtrl")!!
        val target = fake.listMethodsOf("$pkg.ChildCtrl").single { it.name == "getHandler" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.ChildService" in result.mocks,
            "ChildService est appelé par le corps de l'override — il doit être " +
                "mocké. Mocks vus : ${result.mocks.keys}")
        assertFalse("$pkg.ParentService" in result.mocks,
            "ParentService n'est appelé QUE par le corps du parent (jamais " +
                "exécuté : la cible est l'override). Sa présence signifie que " +
                "P1 a crawlé le mauvais corps — c'est exactement le bug Astrea " +
                "case 4.4. Mocks vus : ${result.mocks.keys}")
    }
}

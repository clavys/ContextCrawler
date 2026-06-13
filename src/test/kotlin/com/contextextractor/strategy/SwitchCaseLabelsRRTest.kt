package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptContext
import com.contextextractor.core.prompt.meta.LayerKind
import com.contextextractor.core.prompt.stages.ContextRenderStage
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4.6 — Gap A (Bug SS) : labels de `case` d'un switch surfacés dans la
// section # Target method.
//
// Vrai défaut Astrea 4.4 (re-run) : `getMethodeControle` fait
// `switch (memoireSaisieSegment.getNumeroOrdreGr())` avec des `case
// NumerosOrdreMessage01Constantes.SEGMENT_30/_31/...`. Le prompt ne rendait que
// `SWITCH: memoireSaisieSegment.getNumeroOrdreGr()` SANS les labels — le LLM
// devinait des littéraux (30, 31…) qui ne valent pas les constantes, donc TOUTES
// les branches tombaient dans `default` → `result.getLeft()` null sur 6 tests / 7.
//
// Fix : ConditionalBranch porte `caseLabels` (résolus en FQN `Owner.CONSTANT` par
// le PSI), le mapper les énumère et instruit le LLM de viser chaque branche en
// référençant la constante au lieu de deviner sa valeur.
class SwitchCaseLabelsRRTest {

    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val stage = ContextRenderStage()
    private val pkg = "com.test.switchcase"

    private fun render(fake: FakeIntrospector, sutFqn: String, methodName: String): String {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == methodName }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        val tree = mapper.map(result)
        val ctx = PromptContext(tree = tree)
        stage.apply(ctx)
        return ctx.layers[LayerKind.CONTEXT]
            ?: error("ContextRenderStage doit peupler la layer CONTEXT")
    }

    private val constPkg = "fr.x.NumerosOrdreMessage01Constantes"

    private fun switchFixture() = fixture {
        klass("$pkg.Ctrl", annotations = listOf("org.springframework.stereotype.Service")) {
            method("getMethodeControle", returns = T("java.lang.Object")) {
                param("memoireSaisieSegment", T("$pkg.MemoireSaisieSegment"))
                branch(
                    "SWITCH",
                    "memoireSaisieSegment.getNumeroOrdreGr()",
                    caseLabels = listOf(
                        "$constPkg.SEGMENT_30",
                        "$constPkg.SEGMENT_31",
                        "$constPkg.SEGMENT_32"
                    )
                )
            }
        }
    }

    @Test
    fun `SS — switch case labels are listed in the target method section`() {
        val output = render(switchFixture(), "$pkg.Ctrl", "getMethodeControle")

        assertTrue(output.contains("- branches:"), "la section branches doit exister")
        assertTrue(output.contains("SWITCH: memoireSaisieSegment.getNumeroOrdreGr()"),
            "le discriminant du switch doit rester rendu")
        listOf("SEGMENT_30", "SEGMENT_31", "SEGMENT_32").forEach { label ->
            assertTrue(output.contains("$constPkg.$label"),
                "le label de case $label (FQN) doit être listé pour viser sa branche. Sortie :\n$output")
        }
    }

    @Test
    fun `SS — guidance tells the LLM to reference the constant, not guess a literal`() {
        val output = render(switchFixture(), "$pkg.Ctrl", "getMethodeControle")
        assertTrue(output.contains("reference the constant directly"),
            "le rendu doit instruire de référencer la constante au lieu de deviner un littéral")
        assertTrue(output.contains("default"),
            "le rendu doit mentionner le chemin `default`")
    }

    // Non-régression : une branche SANS labels (IF, ou switch dont les labels
    // n'ont pas pu être résolus) garde le format plat `KIND: condition`, sans
    // texte de guidance parasite.
    @Test
    fun `SS — a branch without case labels keeps the plain format`() {
        val fake = fixture {
            klass("$pkg.PlainCtrl", annotations = listOf("org.springframework.stereotype.Service")) {
                method("compute", returns = T("void")) {
                    param("amount", T("int"))
                    branch("IF", "amount < 0")
                }
            }
        }
        val output = render(fake, "$pkg.PlainCtrl", "compute")
        assertTrue(output.contains("  - IF: amount < 0"),
            "une branche IF sans labels garde le format plat")
        assertFalse(output.contains("reference the constant directly"),
            "aucune guidance de case-label ne doit apparaître sur une branche sans labels")
    }
}

package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptBuilder
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.Tg
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4.3 Bug JJ (volet rendu) — une variable de type non résolue (T, M, E…)
// ne doit JAMAIS apparaître dans les type-args d'une signature à stubber.
//
// Vrai bug Astrea case 4.4 : le prompt rendait
//   `abstractSaisieMessageModele.getSectionPersonne():java.util.List<T>`
// or Bug Z (CONSTRAINTS) ordonne au LLM de copier les type-args caractère
// par caractère → `when(...).thenReturn(List<T>)` ne compile pas, et le LLM
// hallucine un type de remplacement.
//
// Fix : ContextResultTreeMapper.renderType retombe sur le raw type dès qu'un
// type-arg est un FQN sans package (variable de type nue).
class TypeVarStubSignatureTest {

    private val builder = PromptBuilder.defaultPipeline()
    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val pkg = "com.test.bugjj"

    private fun buildPrompt(): String {
        val fake = fixture {
            klass("$pkg.Modele", isInterface = true) {
                // Méthode générique non substituée — retourne List<T>.
                method("getSection", returns = Tg("java.util.List", T("T")))
                // Contrôle positif Bug W : les génériques résolus restent rendus.
                method("getCriteres", returns = Tg("java.util.List", T("$pkg.CritereDTO")))
            }
            klass("$pkg.CritereDTO") {
                method("getCode", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl") {
                field("modele", T("$pkg.Modele"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("target", returns = T("void"),
                    body = "this.modele.getSection(); this.modele.getCriteres();") {
                    reads("$pkg.Ctrl", "modele")
                    calls("$pkg.Modele", "getSection")
                    calls("$pkg.Modele", "getCriteres")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        return builder.build(mapper.map(result))
    }

    @Test
    fun `Bug JJ — bare type variable falls back to raw type in stub signatures`() {
        val context = buildPrompt().substringBefore("=== CONSTRAINTS ===")
        assertTrue(context.contains("getSection():java.util.List"),
            "la signature doit être rendue avec le raw type. Prompt :\n" +
                context.substringAfter("Methods to stub on", "<SECTION ABSENTE>").take(400))
        assertFalse(context.contains("java.util.List<T>"),
            "`List<T>` dans le prompt + Bug Z (copie caractère par caractère) " +
                "= stub non compilable garanti")
    }

    @Test
    fun `Bug W non-regression — resolved generics are still fully rendered`() {
        val context = buildPrompt().substringBefore("=== CONSTRAINTS ===")
        assertTrue(context.contains("getCriteres():java.util.List<$pkg.CritereDTO>"),
            "un type-arg RÉSOLU (avec package) doit rester rendu en entier — " +
                "c'est le verrou Bug W. Prompt :\n" +
                context.substringAfter("Methods to stub on", "<SECTION ABSENTE>").take(400))
    }
}

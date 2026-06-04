package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptBuilder
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

// Bug BB — Ancrer le nom de variable dans le header et chaque ligne de
// `Methods to stub:` pour lever l'ambiguïté quand 2 types se ressemblent.
// Cas prod : `SupervisionDeltaVecModele` (setters seulement) et
// `TableauSupervisionDeltaVecModele` (avec `getCriteresRecherche`) sont
// listés à la suite ; le LLM Qwen 3.6 écrivait
// `supervisionDeltaVecModele.getCriteresRecherche()` (faux type) →
// erreur compile.
//
// Avec Bug BB : `Methods to stub on \`tableauSupervisionDeltaVecModele\`:`
// puis `- tableauSupervisionDeltaVecModele.getCriteresRecherche():...` —
// le nom de variable apparaît dans CHAQUE ligne, impossible de se tromper.
class MockMethodVarPrefixTest {

    private val builder = PromptBuilder.defaultPipeline()
    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val pkg = "com.test.bugbb"

    private fun buildFor(fake: FakeIntrospector,
                         sutFqn: String, methodName: String): String {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == methodName }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        return builder.build(mapper.map(result))
    }

    @Test
    fun `Bug BB — Methods to stub header contains the mock variable name`() {
        val fake = fixture {
            klass("$pkg.Svc", isInterface = true) {
                method("doIt", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl") {
                field("svc", T("$pkg.Svc"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.svc.doIt();") {
                    reads("$pkg.Ctrl", "svc")
                    calls("$pkg.Svc", "doIt")
                }
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl", "handle")

        // Le header doit anchrer le varName.
        assertTrue(output.contains("Methods to stub on `svc`:"),
            "Bug BB : header avec varName attendu. Output Mocks:\n" +
                output.substringAfter("# Mocks (annotate", "").substringBefore("\n# ", "").take(400))
        // La ligne de méthode doit elle aussi commencer par `varName.`
        assertTrue(output.contains("- svc.doIt():java.lang.String"),
            "Bug BB : ligne méthode préfixée par varName. Output Mocks:\n" +
                output.substringAfter("# Mocks (annotate", "").substringBefore("\n# ", "").take(400))
        // L'ancien header nu ne doit plus apparaître.
        assertFalse(output.contains("\nMethods to stub:\n"),
            "Bug BB : ancien header `Methods to stub:` sans varName doit avoir disparu")
    }

    @Test
    fun `Bug BB — two similar named mocks each get their own anchored block`() {
        // Reproduit exactement le pattern production qui cassait :
        // 2 types « Modele » consécutifs, dont un seul a des méthodes à stubber.
        // Le LLM doit pouvoir identifier sans ambiguïté lequel.
        val fake = fixture {
            klass("$pkg.CritereDTO", isInterface = true)
            klass("$pkg.Modele", isInterface = true) {
                // Pas de méthode à stubber — uniquement verify-only.
                method("setX", returns = T("void")) { param("v", T("boolean")) }
            }
            klass("$pkg.TableauModele", isInterface = true) {
                method("getCriteresRecherche", returns = T("java.util.Map"))
            }
            klass("$pkg.Ctrl2") {
                field("modele", T("$pkg.Modele"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("tableauModele", T("$pkg.TableauModele"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.util.Map"),
                    body = "this.modele.setX(true); return this.tableauModele.getCriteresRecherche();") {
                    reads("$pkg.Ctrl2", "modele")
                    reads("$pkg.Ctrl2", "tableauModele")
                    calls("$pkg.Modele", "setX", "boolean")
                    calls("$pkg.TableauModele", "getCriteresRecherche")
                }
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl2", "handle")
        val mocksSection = output.substringAfter("# Mocks (annotate", "").substringBefore("\n# ", "")

        // getCriteresRecherche doit apparaître AVEC le bon nom de variable.
        assertTrue(mocksSection.contains("- tableauModele.getCriteresRecherche():java.util.Map"),
            "Bug BB : getCriteresRecherche préfixé par `tableauModele.` (pas `modele.`). " +
                "Section Mocks:\n${mocksSection.take(800)}")
        // ET ne doit PAS apparaître préfixé par le mauvais varName.
        assertFalse(mocksSection.contains("- modele.getCriteresRecherche"),
            "Bug BB : `modele.getCriteresRecherche` SERAIT une attribution erronée — " +
                "ne doit JAMAIS être produit. Section Mocks:\n${mocksSection.take(800)}")
    }

    @Test
    fun `Bug BB — varName respects camelCase rule from CONSTRAINTS`() {
        // Cohérent avec la règle de style : `TypeName → typeName`.
        val fake = fixture {
            klass("$pkg.PageDataDTO", isInterface = true) {
                method("getName", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl3") {
                field("pageDataDTO", T("$pkg.PageDataDTO"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.pageDataDTO.getName();") {
                    reads("$pkg.Ctrl3", "pageDataDTO")
                    calls("$pkg.PageDataDTO", "getName")
                }
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl3", "handle")

        // First letter lowered, rest verbatim — `pageDataDTO` (pas `pagedatadto`).
        assertTrue(output.contains("Methods to stub on `pageDataDTO`:"),
            "Bug BB : camelCase respecté (pageDataDTO). Output Mocks:\n" +
                output.substringAfter("# Mocks (annotate", "").substringBefore("\n# ", "").take(400))
        assertTrue(output.contains("- pageDataDTO.getName():java.lang.String"),
            "Bug BB : méthode préfixée `pageDataDTO.getName`. Output Mocks:\n" +
                output.substringAfter("# Mocks (annotate", "").substringBefore("\n# ", "").take(400))
    }
}

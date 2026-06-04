package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptBuilder
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.T
import com.contextextractor.fakes.Tg
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bug W — Préservation des génériques dans les signatures du prompt.
// Avant : `getCriteresRecherche():java.util.Map` (génériques effacés).
// Le LLM hallucinait `Map<String, Object>` et le stub Mockito ne compilait
// pas contre la vraie signature `Map<String, CritereDTO>`.
// Avec Bug W : `getCriteresRecherche():java.util.Map<java.lang.String,...CritereDTO>`.
class GenericsPreservationTest {

    private val builder = PromptBuilder.defaultPipeline()
    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val pkg = "com.test.bugw"

    private fun buildFor(fake: FakeIntrospector,
                         sutFqn: String, methodName: String): String {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == methodName }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        return builder.build(mapper.map(result))
    }

    @Test
    fun `Bug W — mock method return type Map keeps its generic parameters`() {
        // Reproduit le cas prod : SupervisionDeltaVecModele#getCriteresRecherche
        // retourne Map<String, CritereDTO> mais était rendu `:java.util.Map`.
        val fake = fixture {
            klass("$pkg.CritereDTO", isInterface = true)
            klass("$pkg.Modele", isInterface = true) {
                method("getCriteresRecherche", returns = Tg(
                    "java.util.Map", T("java.lang.String"), T("$pkg.CritereDTO")
                ))
            }
            klass("$pkg.Ctrl") {
                field("modele", T("$pkg.Modele"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = Tg("java.util.Map",
                    T("java.lang.String"), T("$pkg.CritereDTO")),
                    body = "return this.modele.getCriteresRecherche();") {
                    reads("$pkg.Ctrl", "modele")
                    calls("$pkg.Modele", "getCriteresRecherche")
                }
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl", "handle")

        // Le rendu de la méthode stub doit conserver les génériques.
        assertTrue(output.contains("getCriteresRecherche():java.util.Map<java.lang.String,$pkg.CritereDTO>"),
            "Bug W : signature stub avec génériques. Output (extrait Mocks):\n" +
                output.substringAfter("# Mocks", "").substringBefore("# ", "").take(500))
        // La forme effacée NE doit plus apparaître.
        assertFalse(output.contains("getCriteresRecherche():java.util.Map\n"),
            "Bug W : ancien rendu sans génériques doit avoir disparu")
    }

    @Test
    fun `Bug W — target method return type List keeps element type`() {
        // Reproduit `rechercher(...) : List<LigneResultatSupervisionDeltaVecDTO>`.
        val fake = fixture {
            klass("$pkg.LigneDTO", isInterface = true)
            klass("$pkg.Ctrl2") {
                method("rechercher", returns = Tg(
                    "java.util.List", T("$pkg.LigneDTO")
                ), body = "return new ArrayList<>();")
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl2", "rechercher")

        assertTrue(output.contains("java.util.List<$pkg.LigneDTO> rechercher"),
            "Bug W : type de retour target avec generic. Output (extrait):\n" +
                output.substringAfter("# Target method", "").substringBefore("# ", "").take(400))
    }

    @Test
    fun `Bug W — target method parameter List keeps element type`() {
        // Reproduit `rechercher(PaginationDTO, List<TriDTO>)` —
        // l'erreur était "Required type: List<TriDTO>, Provided: List<LigneDTO>"
        // parce que le LLM ne savait pas le type des génériques du param.
        val fake = fixture {
            klass("$pkg.TriDTO", isInterface = true)
            klass("$pkg.PaginationDTO", isInterface = true)
            klass("$pkg.Ctrl3") {
                method("rechercher", returns = T("void"),
                    body = "// noop") {
                    param("pagination", T("$pkg.PaginationDTO"))
                    param("tris", Tg("java.util.List", T("$pkg.TriDTO")))
                }
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl3", "rechercher")

        // Le canonical de target inclut les params avec génériques.
        assertTrue(output.contains("rechercher($pkg.PaginationDTO,java.util.List<$pkg.TriDTO>)"),
            "Bug W : canonical target inclut les génériques des params. Output (extrait):\n" +
                output.substringAfter("# Target method", "").substringBefore("# ", "").take(400))
    }

    @Test
    fun `Bug W — bare type without generics rendered as-is (no spurious angle brackets)`() {
        // Verrou inverse : un type sans génériques ne doit pas générer
        // d'angles vides `<>`.
        val fake = fixture {
            klass("$pkg.Plain", isInterface = true)
            klass("$pkg.Ctrl4") {
                method("doIt", returns = T("$pkg.Plain"),
                    body = "return null;")
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl4", "doIt")

        assertTrue(output.contains("$pkg.Plain doIt()"),
            "type non générique rendu sans angles. Output:\n${output.take(300)}")
        assertFalse(output.contains("$pkg.Plain<>"),
            "PAS d'angles vides pour un type non générique")
    }
}

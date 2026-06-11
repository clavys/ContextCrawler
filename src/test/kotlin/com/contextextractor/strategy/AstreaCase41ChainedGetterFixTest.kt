package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4 Fix — Astrea case 4.1 réel : `super.getStructurePage().getNombreMax(...)`.
//
// Reproduit le scénario complet :
//   - TableauPagineControleur (parent immédiat de la SUT) :
//       private IHMDTO structurePage;
//       public IHMDTO getStructurePage() { return this.structurePage; }
//       public void setStructurePage(final IHMDTO structurePage) { ... }
//       + d'AUTRES champs et méthodes pour ressembler à la prod
//   - SUT extends TableauPagineControleur :
//       public List<...> rechercher(...) {
//           super.getStructurePage().getNombreMax(...);
//           // + d'autres logiques
//       }
//
// **Avant le fix** :
//   - structurePage détecté via trivialGetterFieldNames ✓
//   - usefulFields contient structurePage ✓
//   - Bug P force MOCKITO_INJECT_MOCKS ✓
//   - filteredFields DROP structurePage car aucune des 3 clauses ne match :
//       • transitiveUsage : ne capte pas (BFS rate sur PSI réel verbeux)
//       • bodyMentionedFieldNames : non (accès via getter)
//       • strategy !is MOCKITO_INJECT_MOCKS : false (Bug P l'a forcé)
//   - droppedFieldTypes contient IHMDTO
//   - mocksAfterFieldFilter drop IHMDTO
//   - prompt n'a aucune info sur IHMDTO → LLM hallucine
//
// **Après le fix** (clause `f.name in trivialGetterFieldNames`) :
//   - filteredFields conserve structurePage ✓
//   - IHMDTO reste dans mocks ✓
//   - prompt expose correctement IHMDTO au LLM
class AstreaCase41ChainedGetterFixTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.astrea_case41"

    @Test
    fun `chained call on inherited trivial getter — structurePage preserved`() {
        val fake = buildFakeAstrea()
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "rechercher" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // Vrai verrou Astrea case 4.1.
        assertTrue(result.fields.any { it.name == "structurePage" },
            "structurePage doit être préservé dans `fields` — sinon IHMDTO disparaît " +
                "et le LLM hallucine `mock(Object.class)`. " +
                "fields actuels: ${result.fields.map { it.name }}")

        assertTrue(result.mocks.containsKey("$pkg.IHMDTO"),
            "IHMDTO doit être dans `mocks` (sinon le LLM ne peut pas appeler " +
                "getNombreMax sur le receiver). mocks actuels: ${result.mocks.keys}")
    }

    private fun buildFakeAstrea() = fixture {
        // IHMDTO — chained-call receiver.
        klass("$pkg.IHMDTO") {
            method("getNombreMaxOccurrencesRecherchees", returns = T("int"))
        }
        // Autres types pour ressembler à la prod (services injectés, DTOs).
        klass("$pkg.Modele") {
            method("getCriteresRecherche", returns = T("java.util.Map"))
            method("setAfficherResultats", returns = T("void")) {
                param("v", T("boolean"))
            }
        }
        klass("$pkg.Service") {
            method("doSomething", returns = T("java.util.List")) {
                param("criteres", T("java.util.Map"))
            }
        }
        // TableauPagineControleur — parent immédiat avec structurePage.
        klass("$pkg.TableauPagineControleur") {
            field(
                name = "structurePage",
                type = T("$pkg.IHMDTO"),
                visibility = "private"
            )
            field(
                name = "messages",
                type = T("java.util.ResourceBundle"),
                visibility = "protected"
            )
            method(
                "getStructurePage",
                visibility = "public",
                returns = T("$pkg.IHMDTO"),
                body = "return this.structurePage;"
            ) {
                reads("$pkg.TableauPagineControleur", "structurePage")
            }
            method(
                "setStructurePage",
                visibility = "public",
                returns = T("void"),
                body = "this.structurePage = structurePage;"
            ) {
                param("structurePage", T("$pkg.IHMDTO"))
                assigns(
                    "$pkg.TableauPagineControleur", "structurePage",
                    rhsExpression = "structurePage"
                )
            }
        }
        // SUT — étend directement TableauPagineControleur.
        klass("$pkg.SUT", superFqn = "$pkg.TableauPagineControleur") {
            field("modele", T("$pkg.Modele"))
            field("service", T("$pkg.Service"))
            field("total", T("int"))
            field("lignes", T("java.util.List"))
            method(
                "rechercher",
                returns = T("java.util.List"),
                body = """{
                    final int nombreMaximumResultats = super.getStructurePage().getNombreMaxOccurrencesRecherchees();
                    this.lignes = new java.util.ArrayList<>();
                    this.lignes.addAll(this.service.doSomething(this.modele.getCriteresRecherche()));
                    this.total = this.lignes.size();
                    this.modele.setAfficherResultats(true);
                    return this.lignes;
                }""".trimIndent()
            ) {
                // PSI rapporte targetType = TableauPagineControleur (declaration site).
                calls("$pkg.TableauPagineControleur", "getStructurePage")
                calls("$pkg.IHMDTO", "getNombreMaxOccurrencesRecherchees")
                calls("$pkg.Service", "doSomething", "java.util.Map")
                calls("$pkg.Modele", "getCriteresRecherche")
                calls("$pkg.Modele", "setAfficherResultats", "boolean")
                calls("java.util.List", "addAll", "java.util.List")
                calls("java.util.List", "size")
                assigns("$pkg.SUT", "lignes", rhsExpression = "new ArrayList<>()")
                assigns("$pkg.SUT", "total", rhsExpression = "this.lignes.size()")
                reads("$pkg.SUT", "lignes")
                reads("$pkg.SUT", "service")
                reads("$pkg.SUT", "modele")
            }
        }
        superChain("$pkg.SUT", "$pkg.TableauPagineControleur")
    }
}

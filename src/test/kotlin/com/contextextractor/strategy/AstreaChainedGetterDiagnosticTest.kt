package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Test

// V1.4 Fix A diagnostique — reproduction exacte du pattern Astrea case 4.1.
//
// Pattern réel observé :
//   public abstract class BaseControleur {
//       protected IHMDTO structurePage;
//       public IHMDTO getStructurePage() { return structurePage; }   ← trivial getter
//   }
//   ...
//   public class SUT extends ... extends BaseControleur {
//       public void rechercher() {
//           int max = super.getStructurePage().getNombreMaxOccurrencesRecherchees();
//           //        └──── trivial getter ──────┘└── chained call sur IHMDTO ──┘
//       }
//   }
//
// Le LLM a halluciné `Object structurePage = mock(Object.class)` et n'a pas pu
// appeler getNombreMaxOccurrencesRecherchees → erreur de compilation.
//
// Verdict attendu de ce test : montrer où exactement le pipeline perd IHMDTO.
// Pas d'assertion stricte (encore) — log des étapes.
class AstreaChainedGetterDiagnosticTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "fr.gouv.justice.astrea.fwk.coordination.controleur"
    private val dtoPkg = "fr.gouv.justice.astrea.idt.dto"
    private val sutPkg = "fr.gouv.justice.astrea.idt.coordination.controleur"

    @Test
    fun `diagnostic — chained call on inherited trivial getter — Astrea real structure`() {
        // Structure RÉELLE Astrea (confirmée par user) :
        //   TableauPagineControleur (parent immédiat de la SUT) :
        //     private IHMDTO structurePage;
        //     public IHMDTO getStructurePage() { return this.structurePage; }
        //     public void setStructurePage(final IHMDTO structurePage) { ... }
        //   SUT.rechercher() : super.getStructurePage().getNombreMaxOccurrencesRecherchees()
        val fake = fixture {
            // IHMDTO — type retourné par le getter hérité, appelé en chaîne.
            klass("$dtoPkg.IHMDTO") {
                method("getNombreMaxOccurrencesRecherchees", returns = T("int"))
            }
            // BaseControleur (racine) — vide pour ce diagnostic.
            klass("$pkg.BaseControleur")
            klass("$sutPkg.BaseAstreaControleur", superFqn = "$pkg.BaseControleur")
            // TableauPagineControleur (parent immédiat) — déclare le champ
            // PRIVATE, le getter trivial PUBLIC et le setter PUBLIC.
            klass("$sutPkg.TableauPagineControleur", superFqn = "$sutPkg.BaseAstreaControleur") {
                field(
                    name = "structurePage",
                    type = T("$dtoPkg.IHMDTO"),
                    visibility = "private"
                )
                method(
                    "getStructurePage",
                    visibility = "public",
                    returns = T("$dtoPkg.IHMDTO"),
                    body = "return this.structurePage;"
                ) {
                    reads("$sutPkg.TableauPagineControleur", "structurePage")
                }
                method(
                    "setStructurePage",
                    visibility = "public",
                    returns = T("void"),
                    body = "this.structurePage = structurePage;"
                ) {
                    param("structurePage", T("$dtoPkg.IHMDTO"))
                    assigns(
                        "$sutPkg.TableauPagineControleur", "structurePage",
                        rhsExpression = "structurePage"
                    )
                }
            }
            // SUT — appelle super.getStructurePage().getNombreMaxOccurrencesRecherchees()
            klass("$sutPkg.SUT", superFqn = "$sutPkg.TableauPagineControleur") {
                method(
                    "rechercher",
                    returns = T("int"),
                    body = "return super.getStructurePage().getNombreMaxOccurrencesRecherchees();"
                ) {
                    calls("$sutPkg.TableauPagineControleur", "getStructurePage")
                    calls("$dtoPkg.IHMDTO", "getNombreMaxOccurrencesRecherchees")
                }
            }
            superChain("$sutPkg.SUT",
                "$sutPkg.TableauPagineControleur",
                "$sutPkg.BaseAstreaControleur",
                "$pkg.BaseControleur"
            )
            superChain("$sutPkg.TableauPagineControleur",
                "$sutPkg.BaseAstreaControleur",
                "$pkg.BaseControleur"
            )
            superChain("$sutPkg.BaseAstreaControleur", "$pkg.BaseControleur")
        }
        val sut = fake.resolveClass("$sutPkg.SUT")!!
        val target = fake.listMethodsOf("$sutPkg.SUT").single { it.name == "rechercher" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        println("=== DIAGNOSTIC Astrea case 4.1 — chained getter ===")
        println("[A] hierarchy           = ${result.hierarchy.map { it.classFqn }}")
        println("[B] fields (count=${result.fields.size}):")
        result.fields.forEach {
            println("    - ${it.name} : ${it.type.fqName} (declaredIn=${it.declaredIn}, vis=${it.visibility})")
        }
        println("[C] initProtocol:")
        result.initProtocol.forEach { (name, proto) ->
            println("    - $name -> ${proto.recommendedStrategy::class.simpleName}")
        }
        println("[D] mocks (count=${result.mocks.size}):")
        result.mocks.forEach { (fqn, _) -> println("    - $fqn") }
        println("[E] internalLogics (count=${result.internalLogics.size}):")
        result.internalLogics.keys.forEach { println("    - $it") }
        println("[F] intraSutCallGraph:")
        result.intraSutCallGraph.forEach { (callee, callers) ->
            println("    - $callee ← $callers")
        }
        println("===================================================")
        println()
        println("VERDICT:")
        println("  - IHMDTO dans mocks ?              ${result.mocks.keys.contains("$dtoPkg.IHMDTO")}")
        println("  - getStructurePage dans internals? ${result.internalLogics.keys.any { it.contains("getStructurePage") }}")
        println("  - structurePage dans fields ?      ${result.fields.any { it.name == "structurePage" }}")
    }
}

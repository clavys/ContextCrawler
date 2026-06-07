package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bug #C bis (V1.3 étape 2) — héritage profond non résolu.
//
// Cas Astrea (case 4.1) observé en Round R8 :
//   target body : `super.getStructurePage().getNombreMax(...)`
//   PSI rapporte : `call.targetType = BaseAstreaControleur` (parent immédiat)
//   mais `getStructurePage()` est définie dans `BaseControleur` (racine).
//
// Sans la remontée, `ReferenceGraphBuilder.findMethodIn(BaseAstreaControleur,
// getStructurePage, [])` retourne null → le call est silencieusement perdu →
// `getStructurePage` n'apparaît jamais comme méthode interne ni STUB_VIA_SPY
// → le LLM hallucine le receiver (`astreaModele.getStructurePage()` qui
// n'existe pas).
//
// Avec la remontée (V1.3 étape 2) :
//   1. findMethodIn tente classFqn direct → null
//   2. Itère listSuperClasses(classFqn) → trouve la méthode dans le parent
//   3. Retourne la signature résolue → pipeline normal s'exécute
//
// Verrou : la méthode héritée doit être BFS-visitée comme intra-SUT.
class RecursiveDeepStrategyBugCBisTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugCbis"

    @Test
    fun `Bug C bis — method defined 2 levels up in hierarchy is resolved via parent walk`() {
        // Hiérarchie : SUT → MiddleParent → DeepRoot
        // getStructurePage() est uniquement dans DeepRoot.
        // Le target fait `super.getStructurePage()` avec targetType=MiddleParent.
        val fake = fixture {
            // POJO retourné par la méthode héritée — non utilisé ici, juste
            // pour ressembler au cas Astrea (IHMDTO retourné par getStructurePage).
            klass("$pkg.IHMDTO") {
                method("getNombreMax", returns = T("int"))
            }
            // Racine : déclare getStructurePage()
            klass("$pkg.DeepRoot") {
                method("getStructurePage",
                    visibility = "public",
                    returns = T("$pkg.IHMDTO"),
                    body = "return null;")
            }
            // Parent intermédiaire : pas de getStructurePage propre.
            klass("$pkg.MiddleParent", superFqn = "$pkg.DeepRoot")
            // SUT
            klass("$pkg.SUT", superFqn = "$pkg.MiddleParent") {
                method("rechercher",
                    returns = T("int"),
                    body = "return super.getStructurePage().getNombreMax();") {
                    // PSI rapporte targetType=MiddleParent — pas DeepRoot —
                    // parce que c'est où le compilateur fait le name binding.
                    calls("$pkg.MiddleParent", "getStructurePage")
                    calls("$pkg.IHMDTO", "getNombreMax")
                }
            }
            superChain("$pkg.SUT", "$pkg.MiddleParent", "$pkg.DeepRoot")
            superChain("$pkg.MiddleParent", "$pkg.DeepRoot")
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "rechercher" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // Sans le fix : `getStructurePage` aurait été silencieusement dropé.
        // Avec le fix : la remontée la trouve dans DeepRoot et elle est BFS-visitée.
        // → Elle apparaît dans `visitedInternalMethods` OU dans `internalLogics`.
        val hasGetStructurePageVisited = result.internalLogics.values
            .any { it.signature.name == "getStructurePage" } ||
            result.internalLogics.keys.any { it.contains("getStructurePage") }

        assertTrue(hasGetStructurePageVisited,
            "La méthode `getStructurePage` héritée 2 niveaux plus haut doit être " +
                "visitée via la remontée hiérarchie (Bug #C bis fix). " +
                "Sans la remontée, le call est silencieusement perdu.")
    }

    @Test
    fun `Bug C bis non-regression — direct method (no walk needed) still works`() {
        // Garde-fou : le fix ne doit pas casser le cas nominal où la méthode
        // EST directement dans la classe target type. La remontée ne doit fire
        // que quand la recherche directe échoue.
        val fake = fixture {
            klass("$pkg.SomeDTO")
            klass("$pkg.SUT") {
                method("direct",
                    visibility = "public",
                    returns = T("$pkg.SomeDTO"),
                    body = "return null;")
                method("target", returns = T("$pkg.SomeDTO"), body = "return direct();") {
                    calls("$pkg.SUT", "direct")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val hasDirectVisited = result.internalLogics.values
            .any { it.signature.name == "direct" } ||
            result.internalLogics.keys.any { it.contains("direct") }

        assertTrue(hasDirectVisited,
            "Le cas nominal (méthode directe sans remontée) doit toujours fonctionner")
    }

    @Test
    fun `Bug C bis — method not in any super stays unresolved (fallback safe)`() {
        // Garde-fou : si la méthode n'existe NULLE PART dans la hiérarchie
        // (call vers une méthode inconnue), le pipeline doit toujours retourner
        // null silencieusement sans crash.
        val fake = fixture {
            klass("$pkg.Parent")
            klass("$pkg.SUT", superFqn = "$pkg.Parent") {
                method("target", returns = T("int"), body = "return inexistent();") {
                    // Call vers une méthode qui n'existe ni dans SUT ni dans Parent.
                    calls("$pkg.SUT", "inexistent")
                }
            }
            superChain("$pkg.SUT", "$pkg.Parent")
        }
        val sut = fake.resolveClass("$pkg.SUT")!!
        val target = fake.listMethodsOf("$pkg.SUT").single { it.name == "target" }
        // Ne doit pas crasher.
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // `inexistent` ne doit pas apparaître comme méthode interne (elle n'existe pas).
        val hasInexistent = result.internalLogics.values
            .any { it.signature.name == "inexistent" }
        assertFalse(hasInexistent,
            "Une méthode inexistante ne doit JAMAIS être enregistrée — silencieusement skippée")
    }
}

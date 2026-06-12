package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.model.HierarchyLevel
import com.contextextractor.core.model.SelectedConstructor
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import com.contextextractor.strategies.recursive.refs.ReferenceGraphBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4.1 Bug #E — une variable de type générique (E, T, K, V…) fuit des
// signatures comme nom nu et était matérialisée `## E [SETTER_BASED]` dans
// `# Data structures` (vu en prod Astrea case 4.2 via `List.remove(int):E`).
// Ce n'est pas un type constructible : bruit pur pour le LLM.
//
// Fix : materializeDataStructures droppe tout FQN sans '.' — une vraie classe
// d'entreprise a toujours un package ; un nom nu est une type variable.
// Le graphe lui-même garde la référence (§8bis.1 : on n'ampute pas la passe 1),
// seule la matérialisation DATA_STRUCTURE filtre.
class TypeVarParasiteTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.buge"

    private fun fakeWithLeakedTypeVar() = fixture {
        klass("$pkg.Repo", isInterface = true) {
            // `pick` retourne la type variable nue `E` — mime `List.remove(int):E`.
            method("pick", returns = T("E"))
        }
        klass("$pkg.Ctrl") {
            field("repo", T("$pkg.Repo"),
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
            method("target", returns = T("void"),
                body = "this.repo.pick();") {
                reads("$pkg.Ctrl", "repo")
                calls("$pkg.Repo", "pick")
            }
        }
    }

    @Test
    fun `Bug E — bare type variable enters the graph but not dataStructures`() {
        val fake = fakeWithLeakedTypeVar()
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "target" }

        // Précondition (non-vacuité du test) : `E` entre bien dans le graphe
        // via AsStubReturn (returnType d'une méthode stubée sur Repo).
        val hierarchy = listOf(HierarchyLevel(sut.fqn, sut.annotations))
        val graph = ReferenceGraphBuilder(fake, emptyList())
            .build(sut, target, hierarchy, SelectedConstructor(parameters = emptyList()))
        assertNotNull(graph.referenceFor("E"),
            "précondition : la type variable `E` doit être enregistrée dans le " +
                "graphe (sinon ce test ne prouve rien — adapter la fixture)")

        // Pipeline complet : `E` ne doit PAS être matérialisé en data structure.
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        assertFalse(result.dataStructures.containsKey("E"),
            "Bug #E : la type variable nue `E` ne doit pas apparaître dans " +
                "dataStructures. Clés vues : ${result.dataStructures.keys}")
        assertTrue(result.dataStructures.keys.all { it.contains('.') },
            "aucun FQN sans package ne doit être matérialisé. " +
                "Clés vues : ${result.dataStructures.keys}")
    }
}

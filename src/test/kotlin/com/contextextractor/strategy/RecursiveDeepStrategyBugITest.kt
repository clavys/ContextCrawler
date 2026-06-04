package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.strategy.Budget
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bug I — Protection des mocks essentiels du drop budget.
//
// Cas observé en production : `PageDataDTO` (returnType de `getPageData()`
// stubée sur `SessionAstreaModele`) et `LigneResultatSupervisionDeltaVecDTO`
// (paramètre de la méthode cible) étaient dropés du budget des mocks. Le LLM
// essayait `new PageDataDTO()` → "Cannot resolve constructor" car PageDataDTO
// n'a pas de ctor sans args.
//
// Correctif : ces types sont marqués *essentiels* et, si le budget est saturé,
// l'éviction porte sur un mock non-essentiel quelconque (pas seulement
// framework). Garantie : un type essentiel n'est jamais perdu silencieusement.
class RecursiveDeepStrategyBugITest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugi"

    @Test
    fun `target method parameter type is preserved as essential mock even with tight budget`() {
        // Setup : SUT a 3 @Autowired (parasites — pas appelés depuis target)
        // + budget=3. target prend un DTO en paramètre. Le DTO doit toujours
        // apparaître comme mock essentiel, même si le budget est déjà saturé
        // par les 3 @Autowired visités en premier.
        //
        // Note Bug M : on ne déclare PAS de `calls(SvcA/B/C)` car cela les
        // rendrait essentiels eux aussi (Bug M ajoute tout `instanceCall.targetType`
        // aux essentiels). On veut ici tester l'éviction d'un *non-essentiel*
        // par un essentiel — donc SvcA/B/C doivent rester non-essentiels.
        val fake = fixture {
            klass("$pkg.InputDTO", isInterface = true) {
                method("getValue", returns = T("java.lang.String"))
            }
            klass("$pkg.SvcA", isInterface = true) {
                method("doA", returns = T("java.lang.String"))
            }
            klass("$pkg.SvcB", isInterface = true) {
                method("doB", returns = T("java.lang.String"))
            }
            klass("$pkg.SvcC", isInterface = true) {
                method("doC", returns = T("java.lang.String"))
            }
            klass("$pkg.Svc") {
                field("a", T("$pkg.SvcA"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("b", T("$pkg.SvcB"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("c", T("$pkg.SvcC"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return input.getValue();") {
                    param("input", T("$pkg.InputDTO"))
                    calls("$pkg.InputDTO", "getValue")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Svc")!!
        val target = fake.listMethodsOf("$pkg.Svc").single { it.name == "handle" }
        val tightBudget = StrategyConfig(budget = Budget(maxMockCount = 3))
        val result = strategy.extractCore(fake, DefaultClassifier(), tightBudget, sut, target)

        // InputDTO (paramètre target) DOIT être préservé via éviction d'un
        // non-essentiel — un des 3 @Autowired (a/b/c) a été évincé pour lui
        // faire de la place.
        assertTrue("$pkg.InputDTO" in result.mocks.keys,
            "InputDTO (paramètre target) est essentiel → doit être préservé. " +
                "Vu: ${result.mocks.keys}")
        // Vérifier que la trace d'éviction essentielle est présente.
        assertTrue(result.truncationReasons.any { it.contains("essential eviction") },
            "trace 'essential eviction' attendue (Bug Q anglais). Vu: ${result.truncationReasons}")
    }

    @Test
    fun `return type of stubbed method is preserved as essential`() {
        // Setup : Repo.find() retourne ResultDTO. Si le budget est tendu, le
        // mock pour ResultDTO doit survivre car c'est le returnType d'une
        // signature stubée — le `when(repo.find()).thenReturn(...)` aurait
        // sinon une valeur impossible à construire.
        val fake = fixture {
            klass("$pkg.ResultDTO2", isInterface = true) {
                method("getValue", returns = T("java.lang.String"))
            }
            klass("$pkg.Repo2", isInterface = true) {
                method("find", returns = T("$pkg.ResultDTO2"))
            }
            klass("$pkg.SvcP", isInterface = true) {
                method("nope", returns = T("java.lang.String"))
            }
            klass("$pkg.SvcQ", isInterface = true) {
                method("nope", returns = T("java.lang.String"))
            }
            klass("$pkg.SvcR", isInterface = true) {
                method("nope", returns = T("java.lang.String"))
            }
            klass("$pkg.Svc2") {
                field("repo", T("$pkg.Repo2"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("p", T("$pkg.SvcP"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("q", T("$pkg.SvcQ"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("r", T("$pkg.SvcR"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return repo.find().getValue();") {
                    reads("$pkg.Svc2", "repo")
                    calls("$pkg.Repo2", "find")
                    calls("$pkg.ResultDTO2", "getValue")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Svc2")!!
        val target = fake.listMethodsOf("$pkg.Svc2").single { it.name == "handle" }
        // Budget = 2 : Repo prend une place + ResultDTO essentielle. Les 3
        // parasites p/q/r doivent céder.
        val tightBudget = StrategyConfig(budget = Budget(maxMockCount = 2))
        val result = strategy.extractCore(fake, DefaultClassifier(), tightBudget, sut, target)

        assertTrue("$pkg.Repo2" in result.mocks.keys,
            "Repo2 (touché direct par target) doit rester")
        assertTrue("$pkg.ResultDTO2" in result.mocks.keys,
            "ResultDTO2 (returnType d'une signature stubée) DOIT être préservé " +
                "comme mock essentiel. Vu: ${result.mocks.keys}")
        // Aucun des 3 parasites ne survit (budget=2, 2 essentiels)
        assertFalse("$pkg.SvcP" in result.mocks.keys)
        assertFalse("$pkg.SvcQ" in result.mocks.keys)
        assertFalse("$pkg.SvcR" in result.mocks.keys)
    }
}

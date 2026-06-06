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

    // V1.2 — Le test "target method parameter type is preserved as essential mock
    // even with tight budget" est supprimé : il dépend de l'éviction LFU V1.1
    // (maxMockCount + essential eviction msg) que V1.2 ne réalise plus. En V1.2,
    // tous les types atteignables sont conservés (cf RAPPORT_CONTEXT §9 défaut #3).
    // Le 2e test du fichier (return type stubbed → essential) reste valide :
    // V1.2 garantit que les returnType de signatures stubées sont surfacés
    // via `AsStubReturn` dans le graph.

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
        // V1.2 — Budget(maxMockCount=…) supprimé (cf RAPPORT_CONTEXT §9 défaut #3).
        // V1.2 n'évince plus par cap. En revanche, l'invariant essentiel reste :
        // Repo2 (champ @Autowired touché par target) et ResultDTO2 (returnType
        // d'une signature stubée) sont conservés. Les parasites p/q/r ne sont
        // pas évincés MAIS sont filtrés via `mocksAfterFieldFilter` (champs
        // @Autowired non utilisés transitivement).
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.Repo2" in result.mocks.keys,
            "Repo2 (touché direct par target) doit rester")
        assertTrue("$pkg.ResultDTO2" in result.mocks.keys,
            "ResultDTO2 (returnType d'une signature stubée) DOIT être préservé " +
                "comme mock essentiel. Vu: ${result.mocks.keys}")
        // Parasites p/q/r non touchés par target → filtrés par mocksAfterFieldFilter.
        assertFalse("$pkg.SvcP" in result.mocks.keys)
        assertFalse("$pkg.SvcQ" in result.mocks.keys)
        assertFalse("$pkg.SvcR" in result.mocks.keys)
    }
}

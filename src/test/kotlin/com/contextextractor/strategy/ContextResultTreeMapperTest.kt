package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.model.MetaKeys
import com.contextextractor.core.model.NodeIds
import com.contextextractor.core.model.NodeKind
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.Fixtures
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4f-β — vérifie que ContextResultTreeMapper produit un arbre :
//   • avec ids stables suivant la convention NodeIds (tree.byId) ;
//   • avec un index byKind cohérent ;
//   • dans l'ordre §4.7 pour les FIELDs (via initOrder) ;
//   • avec metadata renseignée pour chaque kind (FIELD, MOCK, INTERNAL, DTO).
class ContextResultTreeMapperTest {

    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val pkg = "com.testproject.case91"

    private fun runOnCase91(): Pair<String, com.contextextractor.core.model.ContextTree> {
        val fake = Fixtures.case91()
        val sut = fake.resolveClass("$pkg.OrderService")!!
        val target = fake.listMethodsOf("$pkg.OrderService").single { it.name == "calculate" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        return target.canonical() to mapper.map(result)
    }

    @Test
    fun `root node uses NodeIds_target convention`() {
        val (canonical, tree) = runOnCase91()
        val expectedId = "target:$pkg.OrderService#$canonical"
        assertEquals(expectedId, tree.root.id)
        assertEquals(NodeKind.TARGET_METHOD, tree.root.kind)
    }

    @Test
    fun `byId lookup works for all expected children`() {
        val (_, tree) = runOnCase91()
        // hierarchy
        assertNotNull(tree.byId(NodeIds.hierarchy("$pkg.OrderService")))
        // fields (les 2 utiles : repository, cache)
        assertNotNull(tree.byId(NodeIds.field("$pkg.OrderService", "repository")))
        assertNotNull(tree.byId(NodeIds.field("$pkg.OrderService", "cache")))
        // mocks — DiscountRepository SEUL (réconciliation post-BLOC 7 retire
        // DiscountCache car son champ a stratégie CALL_POST_CONSTRUCT, pas
        // MOCKITO_INJECT_MOCKS — verrou EXPECTED_PROMPTS.md case91).
        assertNotNull(tree.byId(NodeIds.mock("$pkg.DiscountRepository")))
        // dto
        assertNotNull(tree.byId(NodeIds.dto("$pkg.OrderDTO")))
    }

    @Test
    fun `ofKind groups nodes by NodeKind`() {
        val (_, tree) = runOnCase91()
        assertEquals(1, tree.ofKind(NodeKind.HIERARCHY).size)
        assertEquals(2, tree.ofKind(NodeKind.FIELD).size)
        assertTrue(tree.ofKind(NodeKind.MOCK).isNotEmpty())
        assertTrue(tree.ofKind(NodeKind.DATA_STRUCTURE).isNotEmpty())
    }

    @Test
    fun `field nodes appear in initOrder priority`() {
        val (_, tree) = runOnCase91()
        // case91 : repository (MOCKITO=0) précède cache (CALL_POST_CONSTRUCT=2).
        val fields = tree.ofKind(NodeKind.FIELD)
        assertEquals(listOf("repository", "cache"), fields.map { it.title })
    }

    @Test
    fun `field metadata carries strategy kind for renderer`() {
        val (_, tree) = runOnCase91()
        val cache = tree.byId(NodeIds.field("$pkg.OrderService", "cache"))!!
        assertEquals("CALL_POST_CONSTRUCT", cache.metadata[MetaKeys.INIT_STRATEGY_KIND])
        assertEquals("init", cache.metadata[MetaKeys.INIT_METHOD_NAME])
        // Type FQN propagé — utile au renderer (## Champ ... : type).
        assertEquals("$pkg.DiscountCache", cache.metadata[MetaKeys.FIELD_TYPE_FQN])

        val repo = tree.byId(NodeIds.field("$pkg.OrderService", "repository"))!!
        assertEquals("MOCKITO_INJECT_MOCKS", repo.metadata[MetaKeys.INIT_STRATEGY_KIND])
    }

    @Test
    fun `walk visits root then all children in DFS pre-order`() {
        val (_, tree) = runOnCase91()
        val visited = tree.walk().toList()
        // Premier nœud = root.
        assertEquals(tree.root.id, visited.first().id)
        // Tous les ids uniques (pas de duplication par DFS).
        assertEquals(visited.size, visited.map { it.id }.toSet().size,
            "walk() doit produire chaque nœud au plus une fois")
    }

    @Test
    fun `root metadata exposes testability diagnostic`() {
        val (_, tree) = runOnCase91()
        // case91 = entièrement testable.
        assertEquals("true", tree.root.metadata[MetaKeys.TESTABILITY])
    }
}

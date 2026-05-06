package com.contextextractor.strategy.initbloc

import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.core.model.init.InitPathKind
import com.contextextractor.core.model.init.MethodKey
import com.contextextractor.strategies.recursive.initbloc.CallGraphBuilder
import com.contextextractor.strategies.recursive.initbloc.EntryPointFinder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4e-γ — vérifie le BFS §4.4 et son intégration avec scorer + chaîne.
//
// Chaque test construit un mini SUT via FakeIntrospector, construit le call
// graph (4e-β), puis appelle EntryPointFinder.find(). Les assertions portent
// sur kind, depth, score, agrégation chaîne.
class EntryPointFinderTest {

    private fun setup(
        fake: FakeIntrospector,
        hierarchy: Set<String>,
        maxDepth: Int = 4,
        acceptsPackage: Boolean = false
    ): Pair<EntryPointFinder, Map<MethodKey, Set<MethodKey>>> {
        val graph = CallGraphBuilder(fake).build(hierarchy)
        val finder = EntryPointFinder(fake, hierarchy, maxDepth, acceptsPackage)
        return finder to graph
    }

    private fun targetSentinel() = MethodKey("__never__", "__never__()")

    // a) La méthode assignatrice EST déjà publique — produit un candidat depth=0
    //    immédiatement. C'est le piège souligné par la consigne 4e-γ.
    @Test
    fun `seed already public produces an immediate depth=0 candidate`() {
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                method("setX") {
                    param("v", T("int"))
                    assigns("com.test.A", "x", rhsExpression = "v")
                }
            }
        }
        val (finder, graph) = setup(fake, setOf("com.test.A"))
        val seed = MethodKey("com.test.A", "setX(int)")

        val path = finder.find("x", seed, targetSentinel(), graph)
        assertNotNull(path)
        assertEquals(0, path!!.depth, "le seed public doit donner un candidat depth=0")
        assertEquals(InitPathKind.PUBLIC_TRANSITIF, path.kind)
        assertEquals(seed, path.entryPoint)
        assertEquals(listOf(seed), path.chain)
    }

    // b) Chaîne 1 cran : assignating est privée, son seul appelant est public.
    @Test
    fun `transitive chain of one hop returns depth=1 candidate`() {
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                method("publicEntry") {
                    calls("com.test.A", "doInit")
                }
                method("doInit", visibility = "private") {
                    assigns("com.test.A", "x", rhsExpression = "1")
                }
            }
        }
        val (finder, graph) = setup(fake, setOf("com.test.A"))
        val seed = MethodKey("com.test.A", "doInit()")
        val expectedEntry = MethodKey("com.test.A", "publicEntry()")

        val path = finder.find("x", seed, targetSentinel(), graph)!!
        assertEquals(1, path.depth)
        assertEquals(expectedEntry, path.entryPoint)
        assertEquals(InitPathKind.PUBLIC_TRANSITIF, path.kind)
    }

    // c) Chaîne 2 crans : private2 → private1 → public.
    @Test
    fun `transitive chain of two hops returns depth=2 candidate`() {
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                method("publicEntry") {
                    calls("com.test.A", "p1")
                }
                method("p1", visibility = "private") {
                    calls("com.test.A", "p2")
                }
                method("p2", visibility = "private") {
                    assigns("com.test.A", "x", rhsExpression = "1")
                }
            }
        }
        val (finder, graph) = setup(fake, setOf("com.test.A"))
        val seed = MethodKey("com.test.A", "p2()")
        val path = finder.find("x", seed, targetSentinel(), graph)!!
        assertEquals(2, path.depth)
        assertEquals(MethodKey("com.test.A", "publicEntry()"), path.entryPoint)
    }

    // d) Le ctor public comme entry point → IMPLICIT_VIA_CONSTRUCTOR avec score=0.
    @Test
    fun `constructor as entry point produces IMPLICIT_VIA_CONSTRUCTOR with score zero`() {
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                method("<init>", returns = T("com.test.A")) {
                    calls("com.test.A", "doInit")
                }
                method("doInit", visibility = "private") {
                    assigns("com.test.A", "x", rhsExpression = "1")
                }
            }
        }
        val (finder, graph) = setup(fake, setOf("com.test.A"))
        val seed = MethodKey("com.test.A", "doInit()")
        val path = finder.find("x", seed, targetSentinel(), graph)!!
        assertEquals(InitPathKind.IMPLICIT_VIA_CONSTRUCTOR, path.kind)
        assertEquals(0, path.score, "score figé à 0 pour la branche ctor (§4.4)")
        assertTrue(
            path.entryPoint.canonical.startsWith("<init>("),
            "entryPoint doit pointer vers le ctor"
        )
    }

    // e) Méthode publique annotée @PostConstruct → PUBLIC_POST_CONSTRUCT.
    @Test
    fun `public method annotated PostConstruct is classified as PUBLIC_POST_CONSTRUCT`() {
        val fake = fixture {
            klass("com.test.A") {
                field("cache", T("java.util.Map"))
                method("init", annotations = listOf("jakarta.annotation.PostConstruct")) {
                    assigns("com.test.A", "cache", rhsExpression = "new HashMap<>()")
                }
            }
        }
        val (finder, graph) = setup(fake, setOf("com.test.A"))
        val seed = MethodKey("com.test.A", "init()")
        val path = finder.find("cache", seed, targetSentinel(), graph)!!
        assertEquals(InitPathKind.PUBLIC_POST_CONSTRUCT, path.kind)
        // Bonus -200 sur baseline depth=0, params=0, void → score = -200.
        assertEquals(-200, path.score)
    }

    // f) Filtre auto-pollution : la méthode cible elle-même n'est jamais
    //    candidate, même si elle assigne le champ (cas pathologique).
    @Test
    fun `target method itself is excluded from candidates`() {
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                method("calculate") { // = méthode cible
                    assigns("com.test.A", "x", rhsExpression = "1")
                }
            }
        }
        val (finder, graph) = setup(fake, setOf("com.test.A"))
        val seed = MethodKey("com.test.A", "calculate()")
        val target = MethodKey("com.test.A", "calculate()")
        val path = finder.find("x", seed, target, graph)
        assertNull(path, "le seed est aussi la méthode cible — doit être exclu")
    }

    // g) Tout privé / aucun appelant accessible → null.
    @Test
    fun `no accessible caller anywhere returns null`() {
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                method("p1", visibility = "private") {
                    calls("com.test.A", "p2")
                }
                method("p2", visibility = "private") {
                    assigns("com.test.A", "x", rhsExpression = "1")
                }
            }
        }
        val (finder, graph) = setup(fake, setOf("com.test.A"))
        val seed = MethodKey("com.test.A", "p2()")
        // Aucun chemin public — null attendu.
        assertNull(finder.find("x", seed, targetSentinel(), graph))
    }

    // h) Choix multi-candidats : @PostConstruct depth=0 (-200) doit l'emporter
    //    sur public ordinaire depth=1 (+100) sur la même chaîne.
    @Test
    fun `min score wins when several candidates are reachable`() {
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                // public ordinaire : score = depth*100 = 100
                method("publicCaller") {
                    calls("com.test.A", "init")
                }
                // post-construct depth=0 : score = -200
                method("init", annotations = listOf("jakarta.annotation.PostConstruct")) {
                    assigns("com.test.A", "x", rhsExpression = "1")
                }
            }
        }
        val (finder, graph) = setup(fake, setOf("com.test.A"))
        val seed = MethodKey("com.test.A", "init()")
        val path = finder.find("x", seed, targetSentinel(), graph)!!
        assertEquals(InitPathKind.PUBLIC_POST_CONSTRUCT, path.kind)
        assertEquals(0, path.depth)
        assertEquals(-200, path.score)
    }

    // i) Budget profondeurGraphe : si l'entry point public est au-delà du
    //    budget, le BFS s'arrête sans candidat.
    @Test
    fun `maxGraphDepth budget cuts off the BFS`() {
        // Chaîne de 5 maillons : seed (private) → p1 → p2 → p3 → p4 (public).
        // Avec maxGraphDepth=2 le BFS ne dépasse pas p2 (depth=2), donc null.
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                method("seed", visibility = "private") {
                    assigns("com.test.A", "x", rhsExpression = "1")
                }
                method("p1", visibility = "private") { calls("com.test.A", "seed") }
                method("p2", visibility = "private") { calls("com.test.A", "p1") }
                method("p3", visibility = "private") { calls("com.test.A", "p2") }
                method("p4")                               { calls("com.test.A", "p3") }
            }
        }
        val (finder, graph) = setup(fake, setOf("com.test.A"), maxDepth = 2)
        val seed = MethodKey("com.test.A", "seed()")
        assertNull(finder.find("x", seed, targetSentinel(), graph),
            "p4 est à depth=4, dépasse maxGraphDepth=2 → aucun candidat")
    }

    // j) **Agrégation chaîne** : le scorer doit voir les externes ET les effets
    //    de bord cumulés sur TOUTE la chaîne, pas seulement sur l'entry point.
    //    C'est le piège mentionné par la consigne 4e-γ.
    @Test
    fun `external calls and side-effects are aggregated across the whole chain`() {
        val fake = fixture {
            klass("com.test.A") {
                field("target", T("int"))
                field("other1", T("int"))
                field("other2", T("int"))
                // Entry public — appelle 1 externe, assigne `other1`.
                method("publicEntry") {
                    calls("com.test.B", "ext1")             // 1 externe
                    assigns("com.test.A", "other1", rhsExpression = "1")
                    calls("com.test.A", "doInit")
                }
                // Maillon privé — appelle 1 externe, assigne `other2`.
                method("doInit", visibility = "private") {
                    calls("com.test.B", "ext2")             // 1 externe
                    assigns("com.test.A", "other2", rhsExpression = "2")
                    assigns("com.test.A", "target", rhsExpression = "3")
                }
            }
            klass("com.test.B") {
                method("ext1")
                method("ext2")
            }
        }
        val (finder, graph) = setup(fake, setOf("com.test.A"))
        val seed = MethodKey("com.test.A", "doInit()")
        val path = finder.find("target", seed, targetSentinel(), graph)!!

        // Externe : ext1 (entry) + ext2 (seed) = 2.
        assertEquals(2, path.externalCallsToStub.size,
            "externes doivent être agrégés sur les DEUX maillons")
        // Effets de bord : other1 + other2 (target exclu). Ordre via LinkedHashSet.
        assertEquals(setOf("other1", "other2"), path.sideEffects)

        // Score reconstruit : depth=1 → 100, params=0 → 0, externes=2 → 40,
        //                    sideEffects=2 → 100, returnsVoid → 0,
        //                    kind=PUBLIC_TRANSITIF → 0.
        // Total = 240.
        assertEquals(240, path.score, "le scorer doit refléter l'agrégation chaîne")
    }
}

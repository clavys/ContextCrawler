package com.contextextractor.strategy.initbloc

import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.core.model.init.MethodKey
import com.contextextractor.strategies.recursive.initbloc.CallGraphBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4e-β — vérifie le graphe d'appels INVERSE intra-SUT (§4.3).
//
// Rappel de la convention : graph[X] = méthodes qui APPELLENT X. Tous les
// tests sont écrits dans ce sens, ne pas les retourner « naturellement ».
class CallGraphBuilderTest {

    private fun build(hierarchy: Set<String>, fakeBlock: () -> com.contextextractor.fakes.FakeIntrospector) =
        CallGraphBuilder(fakeBlock()).build(hierarchy)

    // 1. Hiérarchie vide / SUT solo sans appel intra → graphe vide.
    @Test
    fun `single class with no intra-calls produces an empty graph`() {
        val fake = fixture {
            klass("com.test.A") {
                method("alone")
            }
        }
        val graph = CallGraphBuilder(fake).build(setOf("com.test.A"))
        assertTrue(graph.isEmpty(), "aucun appel intra-SUT, le graphe doit être vide")
    }

    // 2. Sens inverse vérifié explicitement : A appelle B → graph[B] contient A,
    //    et graph[A] NE contient PAS B. C'est LA propriété qu'un refactor naïf
    //    pourrait casser.
    @Test
    fun `graph is inverse caller appears in entry of callee not the other way around`() {
        val fake = fixture {
            klass("com.test.A") {
                method("a") {
                    calls("com.test.A", "b")
                }
                method("b")
            }
        }
        val graph = CallGraphBuilder(fake).build(setOf("com.test.A"))

        val a = MethodKey("com.test.A", "a()")
        val b = MethodKey("com.test.A", "b()")

        // Le SENS qui compte : appelants de b = {a}.
        assertEquals(setOf(a), graph[b], "graph[b] doit lister son appelant (a)")
        // Et surtout : graph[a] ne contient PAS b — sinon on a inversé le graphe.
        assertFalse(graph[a]?.contains(b) ?: false, "graph[a] ne doit PAS contenir b (graphe inverse)")
    }

    // 3. Le ctor comme appelant — cas piège mentionné par la consigne 4e-β.
    //    OrderService a un ctor qui appelle `init()` privée. Le BFS de §4.4
    //    doit pouvoir remonter via graph[init] et y trouver le ctor.
    @Test
    fun `constructor calling private init shows up as caller`() {
        val fake = fixture {
            klass("com.test.OrderService") {
                method("<init>", returns = T("com.test.OrderService")) {
                    param("repo", T("com.test.Repo"))
                    calls("com.test.OrderService", "init")
                }
                method("init", visibility = "private")
            }
        }
        val graph = CallGraphBuilder(fake).build(setOf("com.test.OrderService"))

        val init = MethodKey("com.test.OrderService", "init()")
        val ctor = MethodKey("com.test.OrderService", "<init>(com.test.Repo)")

        val callers = graph[init]
        assertNotNull(callers, "graph[init] doit exister")
        assertTrue(
            ctor in callers!!,
            "le ctor (canonical = ${ctor.canonical}) doit figurer comme appelant de init"
        )
        // Verrou de format : la canonical du ctor commence par "<init>("
        // — confirmé en lisant le set retourné, pas par construction.
        assertTrue(
            callers.any { it.canonical.startsWith("<init>(") },
            "au moins un appelant de init doit avoir une canonical commençant par <init>("
        )
    }

    // 4. Appel HORS hiérarchie → ignoré. Garantit qu'on ne pollue pas le graphe
    //    avec des appels à des dépendances externes (qui partiront en MOCK_EXTERNAL
    //    via un autre chemin).
    @Test
    fun `calls to a class outside the hierarchy are ignored`() {
        val fake = fixture {
            klass("com.test.OrderService") {
                method("process") {
                    calls("com.test.ExternalRepo", "save")
                }
            }
            klass("com.test.ExternalRepo") {
                method("save")
            }
        }
        val graph = CallGraphBuilder(fake).build(setOf("com.test.OrderService"))
        assertTrue(
            graph.isEmpty(),
            "ExternalRepo n'est pas dans la hiérarchie, son entrée ne doit pas exister"
        )
    }

    // 5. Appel via super (méthode héritée) — le port PSI résout au containingClass
    //    réel (la super-classe), donc le call.targetType pointe vers Base. Le
    //    filtre `targetType ∈ hierarchyFqns` doit donc accepter Base si Base est
    //    listée dans la hiérarchie.
    @Test
    fun `call to method declared on a super-class within the hierarchy is captured`() {
        val fake = fixture {
            klass("com.test.Base", isAbstract = true) {
                method("validate", visibility = "protected")
            }
            klass("com.test.Sub", superFqn = "com.test.Base") {
                method("doWork") {
                    // PSI résout `validate()` à `Base.validate` — l'adapter renseigne
                    // donc targetType = "com.test.Base".
                    calls("com.test.Base", "validate")
                }
            }
            superChain("com.test.Sub", "com.test.Base")
        }
        val graph = CallGraphBuilder(fake)
            .build(setOf("com.test.Sub", "com.test.Base"))

        val validate = MethodKey("com.test.Base", "validate()")
        val doWork = MethodKey("com.test.Sub", "doWork()")
        assertEquals(setOf(doWork), graph[validate])
    }

    // 6. Auto-appel récursif : graph[a] doit contenir a. Pas un cas pathologique
    //    pour le BFS de §4.4 (visited stoppe le cycle), mais le graphe lui-même
    //    doit représenter fidèlement la récursion sans la dédupliquer.
    @Test
    fun `recursive self-call is represented in the graph`() {
        val fake = fixture {
            klass("com.test.A") {
                method("rec") {
                    calls("com.test.A", "rec")
                }
            }
        }
        val graph = CallGraphBuilder(fake).build(setOf("com.test.A"))
        val rec = MethodKey("com.test.A", "rec()")
        assertEquals(setOf(rec), graph[rec])
    }
}

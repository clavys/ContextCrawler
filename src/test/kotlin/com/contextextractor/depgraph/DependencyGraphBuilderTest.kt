package com.contextextractor.depgraph

import com.contextextractor.core.model.depgraph.DependencyGraph
import com.contextextractor.fakes.Fixtures
import com.contextextractor.fakes.T
import com.contextextractor.fakes.Tg
import com.contextextractor.fakes.fixture
import com.contextextractor.strategies.depgraph.DependencyGraphBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.3 Étape 1 — tests du DependencyGraphBuilder.
//
// Vérifie :
//   • BFS plat depuis SUT (case91, case93)
//   • Garde-fou maxDepth
//   • Exclusion des préfixes système (java.*, kotlin.*)
//   • Conservation des frontières framework (nom gardé, intérieur non exploré)
//   • Robustesse aux types non résolvables (truncated tracking)
//   • Rendus JSON et DOT bien formés
//
// L'utilisateur peut générer ces cas via la commande IDE et les comparer
// aux assertions ci-dessous lors du dump.
class DependencyGraphBuilderTest {

    @Test
    fun `case91 OrderService — graph contains expected reachable types`() {
        val fake = Fixtures.case91()
        val builder = DependencyGraphBuilder(fake)
        val graph = builder.build("com.testproject.case91.OrderService")

        // Racine présente avec profondeur 0.
        assertTrue("com.testproject.case91.OrderService" in graph.nodes)
        assertEquals(0, graph.depthOf["com.testproject.case91.OrderService"])

        // Types directement référencés par les champs ou la signature.
        assertTrue("com.testproject.case91.DiscountRepository" in graph.nodes,
            "DiscountRepository (champ injecté) attendu dans le graphe")
        assertTrue("com.testproject.case91.DiscountCache" in graph.nodes,
            "DiscountCache (champ) attendu dans le graphe")
        assertTrue("com.testproject.case91.OrderDTO" in graph.nodes,
            "OrderDTO (return de calculate) attendu dans le graphe")
    }

    @Test
    fun `system prefixes are excluded — no java_lang_String node`() {
        val fake = Fixtures.case91()
        val builder = DependencyGraphBuilder(fake)
        val graph = builder.build("com.testproject.case91.OrderService")

        // java.lang.String est référencé partout mais doit être filtré
        // pour éviter le bruit JDK.
        assertFalse("java.lang.String" in graph.nodes,
            "Les types java.* ne doivent PAS être enregistrés (filtre bruit JDK)")
        assertFalse("java.lang.Long" in graph.nodes,
            "java.lang.Long doit être filtré")
    }

    @Test
    fun `framework prefix is kept as node but not explored`() {
        // Cas synthétique : SUT a un champ de type framework (javax.faces.X).
        val fake = fixture {
            klass("javax.faces.context.FacesContext") {
                // L'intérieur de FacesContext ne doit PAS être exploré.
                field("internalState", T("javax.faces.context.SomeInternal"))
                method("getMessageList", returns = T("javax.faces.application.Message"))
            }
            klass("javax.faces.context.SomeInternal")
            klass("javax.faces.application.Message")
            klass("com.test.SUT") {
                field("context", T("javax.faces.context.FacesContext"))
                method("target", returns = T("void"))
            }
        }
        val builder = DependencyGraphBuilder(
            fake,
            frameworkPrefixes = listOf("javax.faces.")
        )
        val graph = builder.build("com.test.SUT")

        // FacesContext apparaît comme nœud (utile pour les lookups), mais
        // ses children (SomeInternal, Message) NE doivent PAS apparaître.
        assertTrue("javax.faces.context.FacesContext" in graph.nodes,
            "Le nom du type framework doit être conservé")
        assertFalse("javax.faces.context.SomeInternal" in graph.nodes,
            "L'intérieur du framework ne doit PAS être exploré")
        assertFalse("javax.faces.application.Message" in graph.nodes,
            "Les transitifs du framework ne doivent PAS apparaître")
        // Truncation tracking : la raison est documentée.
        assertTrue(graph.truncatedAt.any { it.contains("javax.faces.context.FacesContext") },
            "La troncation framework doit être documentée")
    }

    @Test
    fun `maxDepth caps exploration with truncation reason`() {
        // Chaîne profonde : SUT → A → B → C
        val fake = fixture {
            klass("com.test.C")
            klass("com.test.B") {
                field("c", T("com.test.C"))
            }
            klass("com.test.A") {
                field("b", T("com.test.B"))
            }
            klass("com.test.SUT") {
                field("a", T("com.test.A"))
            }
        }
        val builder = DependencyGraphBuilder(fake, maxDepth = 2)
        val graph = builder.build("com.test.SUT")

        assertTrue("com.test.SUT" in graph.nodes)
        assertTrue("com.test.A" in graph.nodes)
        assertTrue("com.test.B" in graph.nodes)
        // C est à profondeur 3 → tronqué.
        assertTrue(graph.truncatedAt.any { it.contains("maxDepth") },
            "La troncation maxDepth doit être visible")
    }

    @Test
    fun `unresolved class is tracked but does not crash`() {
        // SUT référence un type que l'introspecteur ne résout pas.
        val fake = fixture {
            klass("com.test.SUT") {
                field("missingType", T("com.unknown.MissingType"))
            }
            // MissingType n'est PAS déclaré dans la fixture → resolveClass=null
        }
        val builder = DependencyGraphBuilder(fake)
        val graph = builder.build("com.test.SUT")

        // Le nom est gardé (utile pour signaler l'écart) mais marqué tronqué.
        assertTrue("com.unknown.MissingType" in graph.nodes ||
                   graph.truncatedAt.any { it.contains("com.unknown.MissingType") },
            "Type non résolu doit être tracé d'une manière ou d'une autre")
    }

    @Test
    fun `case93 OrderService — superclass AbstractCacheService is included`() {
        // case93 a une hiérarchie : OrderService extends AbstractCacheService
        val fake = Fixtures.case93()
        val builder = DependencyGraphBuilder(fake)
        val graph = builder.build("com.testproject.case93.OrderService")

        // La superclasse doit apparaître comme nœud.
        assertTrue("com.testproject.case93.AbstractCacheService" in graph.nodes,
            "La superclasse de la SUT doit être dans le graphe")
        // Et son champ `cache` doit être atteignable depuis là.
        assertTrue("com.testproject.case93.Cache" in graph.nodes,
            "Le champ `cache` (hérité de AbstractCacheService) doit être atteignable")
    }

    @Test
    fun `JSON output is well-formed and contains nodes and edges`() {
        val fake = Fixtures.case91()
        val graph = DependencyGraphBuilder(fake)
            .build("com.testproject.case91.OrderService")
        val json = graph.toJson()

        assertTrue(json.startsWith("{") && json.endsWith("}"),
            "JSON doit être bien délimité")
        assertTrue(json.contains("\"rootFqn\":\"com.testproject.case91.OrderService\""),
            "JSON doit contenir le rootFqn")
        assertTrue(json.contains("\"nodes\":["),
            "JSON doit contenir la liste des nodes")
        assertTrue(json.contains("\"edges\":{"),
            "JSON doit contenir la map des edges")
    }

    @Test
    fun `DOT output is well-formed for Graphviz`() {
        val fake = Fixtures.case91()
        val graph = DependencyGraphBuilder(fake)
            .build("com.testproject.case91.OrderService")
        val dot = graph.toDot()

        assertTrue(dot.startsWith("digraph DependencyGraph {"),
            "DOT doit commencer par l'en-tête digraph")
        assertTrue(dot.endsWith("}\n"),
            "DOT doit fermer la balise")
        assertTrue(dot.contains("\"com.testproject.case91.OrderService\" [shape=doubleoctagon"),
            "Racine doit être stylisée pour identification visuelle")
    }

    @Test
    fun `filteredByPrefix removes nodes outside the prefix`() {
        // Cas synthétique avec mix de domaines.
        val fake = fixture {
            klass("com.app.SUT") {
                field("repo", T("com.app.repo.Repository"))
                field("logger", T("org.slf4j.Logger"))
            }
            klass("com.app.repo.Repository") {
                field("entity", T("com.app.repo.Entity"))
            }
            klass("com.app.repo.Entity")
            klass("org.slf4j.Logger") {
                method("info", returns = T("void"))
            }
        }
        val graph = DependencyGraphBuilder(fake).build("com.app.SUT")
        val filtered = graph.filteredByPrefix("com.app.")

        // org.slf4j.Logger doit disparaître.
        assertFalse("org.slf4j.Logger" in filtered.nodes,
            "filteredByPrefix doit retirer les nodes hors prefix")
        // Tous les com.app.* doivent rester.
        assertTrue("com.app.SUT" in filtered.nodes)
        assertTrue("com.app.repo.Repository" in filtered.nodes)
        assertTrue("com.app.repo.Entity" in filtered.nodes)
    }

    // ── Cleanup post case 4.1 Astrea ─────────────────────────────────────────

    @Test
    fun `array types are stripped — Foo array becomes Foo`() {
        // Verrou : sur Astrea case 4.1, 17 entries (EnumType[]) polluaient
        // les `truncated` comme `(unresolved)`. Le strip des arrays les ramène
        // au type de base.
        val fake = fixture {
            klass("com.test.Enum1")
            klass("com.test.SUT") {
                // values() retourne EnumType[] — typique des enums
                method("getEnumArray", returns = T("com.test.Enum1[]"))
            }
        }
        val graph = DependencyGraphBuilder(fake).build("com.test.SUT")
        // Le type de base doit apparaître, pas la version `[]`.
        assertTrue("com.test.Enum1" in graph.nodes,
            "Le type de base de l'array doit apparaître")
        assertFalse("com.test.Enum1[]" in graph.nodes,
            "La version `[]` ne doit PAS apparaître après strip")
        assertFalse(graph.truncatedAt.any { it.contains("com.test.Enum1[]") },
            "La version `[]` ne doit PAS apparaître dans truncatedAt")
    }

    @Test
    fun `type variables are filtered — T E K V never appear`() {
        // Verrou : sur Astrea case 4.1, 6 entries (T, T[], E, L, M, R)
        // polluaient comme `(unresolved)`. Les variables de type ne sont pas
        // de vraies classes — on les filtre.
        val fake = fixture {
            klass("com.test.SUT") {
                // Méthode générique de type `<T> T identity(T)` — T n'a pas
                // de FQN package.
                method("identity", returns = T("T")) {
                    param("input", T("T"))
                }
                method("getList", returns = Tg("java.util.List", T("E")))
            }
        }
        val graph = DependencyGraphBuilder(fake).build("com.test.SUT")
        // Les single uppercase letters (type variables) ne doivent pas être
        // dans le graphe.
        assertFalse("T" in graph.nodes, "Variable de type `T` ne doit PAS apparaître")
        assertFalse("E" in graph.nodes, "Variable de type `E` ne doit PAS apparaître")
        assertFalse(graph.truncatedAt.any { it == "T (unresolved)" },
            "`T (unresolved)` ne doit PAS polluer la liste truncated")
    }

    @Test
    fun `check order — system types reached at maxDepth are silently excluded`() {
        // Verrou : sur Astrea case 4.1, `java.lang.String (maxDepth)` était
        // taggé `(maxDepth)` alors qu'il devrait être silencieusement filtré
        // comme bruit système.
        val fake = fixture {
            // Chaîne A → B → ... atteignant java.lang.String à profondeur > maxDepth
            klass("com.test.B") {
                field("name", T("java.lang.String"))
            }
            klass("com.test.A") {
                field("b", T("com.test.B"))
            }
            klass("com.test.SUT") {
                field("a", T("com.test.A"))
            }
        }
        // maxDepth=2 → java.lang.String atteint à dist=3 (au-delà du cap)
        val graph = DependencyGraphBuilder(fake, maxDepth = 2).build("com.test.SUT")
        // java.lang.String ne doit JAMAIS apparaître (ni dans nodes ni dans
        // truncated) — filtré par systemPrefixes en priorité.
        assertFalse("java.lang.String" in graph.nodes,
            "java.lang.String ne doit pas être dans nodes")
        assertFalse(graph.truncatedAt.any { it.contains("java.lang.String") },
            "java.lang.String ne doit pas polluer truncated avec `(maxDepth)`")
    }

    @Test
    fun `summary is concise and informative`() {
        val fake = Fixtures.case91()
        val graph = DependencyGraphBuilder(fake)
            .build("com.testproject.case91.OrderService")
        val summary = graph.toSummary()

        assertTrue(summary.contains("DependencyGraph"))
        assertTrue(summary.contains("root="))
        assertTrue(summary.contains("nodes="))
        assertTrue(summary.contains("edges="))
        assertTrue(summary.contains("maxDepth="))
    }
}

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

// Bug B — Filtrage des DataStructures par usage transitif.
//
// Avant le correctif : un @Autowired dropé par défaut #2 laissait quand même
// son type DTO (et ses champs transitifs) dans `# Structures de données à
// construire ». Cas réel observé : SupervisionDeltaVecModele apparaît dans
// le prompt alors qu'il n'est jamais touché par la méthode cible.
class RecursiveDeepStrategyBugBTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugb"

    @Test
    fun `DTO of dropped Autowired field is removed from dataStructures`() {
        // Setup : SUT a un @Autowired UnusedModele (jamais touché par target).
        // Avant Bug B fix, UnusedModele apparaissait dans dataStructures.
        // Après Bug B fix, il est filtré.
        val fake = fixture {
            klass("$pkg.UnusedModele") {
                field("id", T("java.lang.Long"))
                field("label", T("java.lang.String"))
                method("<init>", returns = T("$pkg.UnusedModele"))
            }
            klass("$pkg.UsedService", isInterface = true) {
                method("doIt", returns = T("java.lang.String"))
            }
            klass("$pkg.Controller") {
                field("unusedModele", T("$pkg.UnusedModele"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("svc", T("$pkg.UsedService"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return svc.doIt();") {
                    reads("$pkg.Controller", "svc")
                    calls("$pkg.UsedService", "doIt")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Controller")!!
        val target = fake.listMethodsOf("$pkg.Controller").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // UnusedModele ne doit pas figurer parmi les structures de données.
        assertFalse("$pkg.UnusedModele" in result.dataStructures.keys,
            "UnusedModele a été dropé via défaut #2 ; son type DTO ne doit pas " +
                "rester dans dataStructures. Vu: ${result.dataStructures.keys}")
        // Le champ lui-même est aussi dropé (verrou défaut #2 — sanity check).
        assertFalse("unusedModele" in result.fields.map { it.name })
    }

    @Test
    fun `DTO reachable from method parameter is kept`() {
        val fake = fixture {
            klass("$pkg.InputDTO") {
                field("name", T("java.lang.String"))
                method("<init>", returns = T("$pkg.InputDTO"))
            }
            klass("$pkg.Svc") {
                method("process", returns = T("void")) {
                    param("input", T("$pkg.InputDTO"))
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Svc")!!
        val target = fake.listMethodsOf("$pkg.Svc").single { it.name == "process" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.InputDTO" in result.dataStructures.keys,
            "InputDTO est le type d'un paramètre de la méthode cible → doit rester")
    }

    @Test
    fun `DTO returned by stubbed mock signature is kept`() {
        // Cas : un mock retourne un DTO. Le LLM doit pouvoir construire ce DTO
        // pour `when(mock.method()).thenReturn(new DTO(...))`. Le DTO doit
        // donc rester dans dataStructures.
        val fake = fixture {
            klass("$pkg.ResultDTO") {
                field("value", T("java.lang.String"))
                method("<init>", returns = T("$pkg.ResultDTO"))
            }
            klass("$pkg.Repo", isInterface = true) {
                method("find", returns = T("$pkg.ResultDTO"))
            }
            klass("$pkg.Svc2") {
                field("repo", T("$pkg.Repo"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return repo.find().toString();") {
                    reads("$pkg.Svc2", "repo")
                    calls("$pkg.Repo", "find")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Svc2")!!
        val target = fake.listMethodsOf("$pkg.Svc2").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.ResultDTO" in result.dataStructures.keys,
            "ResultDTO est returnType d'une signature stubée sur le mock Repo → doit rester")
    }

    @Test
    fun `transitive DTO chain is kept via field traversal`() {
        // ParentDTO contient un ChildDTO. Garder ParentDTO doit aussi garder
        // ChildDTO (le LLM doit construire la chaîne).
        val fake = fixture {
            klass("$pkg.ChildDTO") {
                field("name", T("java.lang.String"))
                method("<init>", returns = T("$pkg.ChildDTO"))
            }
            klass("$pkg.ParentDTO") {
                field("child", T("$pkg.ChildDTO"))
                method("<init>", returns = T("$pkg.ParentDTO"))
            }
            klass("$pkg.Svc3") {
                method("process", returns = T("$pkg.ParentDTO"))
            }
        }
        val sut = fake.resolveClass("$pkg.Svc3")!!
        val target = fake.listMethodsOf("$pkg.Svc3").single { it.name == "process" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.ParentDTO" in result.dataStructures.keys)
        assertTrue("$pkg.ChildDTO" in result.dataStructures.keys,
            "ChildDTO est référencé par un champ de ParentDTO (conservé) → BFS doit le garder")
    }
}

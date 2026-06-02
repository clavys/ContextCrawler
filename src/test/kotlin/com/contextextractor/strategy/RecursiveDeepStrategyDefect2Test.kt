package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.model.init.InitStrategy
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Défaut #2 — Filtrage des champs et mocks par usage transitif réel.
//
// Reproduit le pattern controleur Spring observé en production
// (SupervisionDeltaVecControleur) : SUT avec 6 dépendances @Autowired dont
// seules 2 sont effectivement touchées par la méthode cible. Les 4 autres
// doivent disparaître des `mocks` et `fields` (ils consommeraient inutilement
// le `maxMockCount` de Budget et pollueraient le prompt LLM).
//
// Verrou inverse : les cas existants (BLOC 3 case00, case91, case92, init
// transitif case93) doivent rester verts — testé via leurs tests dédiés.
class RecursiveDeepStrategyDefect2Test {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.defect2"

    @Test
    fun `unused Autowired dependencies are dropped from fields and mocks`() {
        val fake = fixture {
            // 6 services injectés — analogue aux 10 @Autowired du controleur réel.
            klass("$pkg.UsedService", isInterface = true) {
                method("doUsed", returns = T("java.lang.String"))
            }
            klass("$pkg.AlsoUsedService", isInterface = true) {
                method("doAlso", returns = T("java.lang.String"))
            }
            klass("$pkg.UnusedA", isInterface = true) {
                method("nope", returns = T("java.lang.String"))
            }
            klass("$pkg.UnusedB", isInterface = true) {
                method("nope", returns = T("java.lang.String"))
            }
            klass("$pkg.UnusedC", isInterface = true) {
                method("nope", returns = T("java.lang.String"))
            }
            klass("$pkg.UnusedD", isInterface = true) {
                method("nope", returns = T("java.lang.String"))
            }
            klass("$pkg.Controller") {
                field(
                    "used",
                    T("$pkg.UsedService"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
                )
                field(
                    "alsoUsed",
                    T("$pkg.AlsoUsedService"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
                )
                field(
                    "unusedA",
                    T("$pkg.UnusedA"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
                )
                field(
                    "unusedB",
                    T("$pkg.UnusedB"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
                )
                field(
                    "unusedC",
                    T("$pkg.UnusedC"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
                )
                field(
                    "unusedD",
                    T("$pkg.UnusedD"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
                )
                method(
                    "handle",
                    returns = T("java.lang.String"),
                    body = "return used.doUsed() + alsoUsed.doAlso();"
                ) {
                    reads("$pkg.Controller", "used")
                    reads("$pkg.Controller", "alsoUsed")
                    calls("$pkg.UsedService", "doUsed")
                    calls("$pkg.AlsoUsedService", "doAlso")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Controller")!!
        val target = fake.listMethodsOf("$pkg.Controller").single { it.name == "handle" }

        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val fieldNames = result.fields.map { it.name }.toSet()
        assertEquals(setOf("used", "alsoUsed"), fieldNames,
            "seuls les @Autowired effectivement touchés par handle doivent rester")

        assertTrue("$pkg.UsedService" in result.mocks.keys)
        assertTrue("$pkg.AlsoUsedService" in result.mocks.keys)
        listOf("UnusedA", "UnusedB", "UnusedC", "UnusedD").forEach { name ->
            assertFalse("$pkg.$name" in result.mocks.keys,
                "$name est @Autowired mais jamais touché → doit disparaître des mocks")
        }

        // Cohérence initProtocol/initOrder.
        assertEquals(setOf("used", "alsoUsed"), result.initProtocol.keys)
        assertEquals(setOf("used", "alsoUsed"), result.initOrder.toSet())
    }

    @Test
    fun `Autowired dropped if reached only through entry point on another path`() {
        // Variante : un champ @Autowired (config) n'est jamais touché ni par la
        // méthode cible ni par aucun chemin d'init. Doit disparaître.
        // Inverse : `repo` est touché via la méthode cible.
        val fake = fixture {
            klass("$pkg.Repo", isInterface = true) {
                method("findAll", returns = T("java.lang.String"))
            }
            klass("$pkg.Config", isInterface = true) {
                method("getValue", returns = T("java.lang.String"))
            }
            klass("$pkg.Svc") {
                field("repo", T("$pkg.Repo"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("config", T("$pkg.Config"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("query", returns = T("java.lang.String"),
                    body = "return repo.findAll();") {
                    reads("$pkg.Svc", "repo")
                    calls("$pkg.Repo", "findAll")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Svc")!!
        val target = fake.listMethodsOf("$pkg.Svc").single { it.name == "query" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("repo" in result.fields.map { it.name })
        assertFalse("config" in result.fields.map { it.name },
            "config @Autowired jamais touché → dropé")
        assertTrue("$pkg.Repo" in result.mocks.keys)
        assertFalse("$pkg.Config" in result.mocks.keys)
    }

    @Test
    fun `Autowired reached only via BLOC 7 init chain is preserved (case93-like)`() {
        // Garde-fou critique : un @Autowired touché UNIQUEMENT par les chemins
        // d'init élus en BLOC 7 (ex : start → buildCache touche loader) ne doit
        // PAS être dropé. Sans cette protection, on perdrait `loader` sur case93.
        //
        // Setup : `cache` accédé par target → CALL_POST_CONSTRUCT(init).
        //   `init` appelle `fill` qui touche `loader`.
        //   `loader` doit donc rester dans les champs grâce à la seed init.
        val fake = fixture {
            klass("$pkg.Loader", isInterface = true) {
                method("load", returns = T("java.lang.String"))
            }
            klass("$pkg.Cache") {
                field("value", T("java.lang.String"))
                method("get", returns = T("java.lang.String"))
            }
            klass("$pkg.Svc2") {
                field("loader", T("$pkg.Loader"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("cache", T("$pkg.Cache"))
                method("init", annotations = listOf("jakarta.annotation.PostConstruct")) {
                    calls("$pkg.Svc2", "fill")
                }
                method("fill", visibility = "private") {
                    reads("$pkg.Svc2", "loader")
                    assigns("$pkg.Svc2", "cache", rhsExpression = "new Cache(loader.load())")
                    calls("$pkg.Loader", "load")
                }
                method("query", returns = T("java.lang.String"),
                    body = "return cache.get();") {
                    reads("$pkg.Svc2", "cache")
                    calls("$pkg.Cache", "get")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Svc2")!!
        val target = fake.listMethodsOf("$pkg.Svc2").single { it.name == "query" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // cache est touché directement par query → conservé.
        assertTrue("cache" in result.fields.map { it.name })
        // loader n'est touché que par fill, appelée par init (entry point élu
        // pour cache). La seed init protocol attrape ce chemin.
        assertTrue("loader" in result.fields.map { it.name },
            "loader doit être conservé : touché par fill qui appartient au chemin d'init de cache")
        assertTrue("$pkg.Loader" in result.mocks.keys,
            "le mock Loader reste nécessaire pour initialiser cache via init()")
    }
}

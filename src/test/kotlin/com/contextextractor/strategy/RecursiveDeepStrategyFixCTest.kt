package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.model.init.InitStrategy
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4.1 Fix C — la branche auto-init (ex-10) est déplacée en 2bis, AVANT les
// branches 3-9 (setter / méthode publique / BFS).
//
// Vrai NPE Astrea case 4.1 (runtime, dans @BeforeEach) :
//   `dernierElementListe` et `premierElementListe` sont écrits par
//   `rechercher` (la cible) sans jamais être lus — champs output purs.
//   Mais `calculerPremierDernierElementsPage(PageEvent)` est une assignatrice
//   publique → la branche 6 (testée AVANT l'ancienne branche 10) imposait
//   CALL_PUBLIC_WITH_ARGS. Le LLM exécutait dans @BeforeEach :
//       PageEvent pageEvent = mock(PageEvent.class);
//       sut.calculerPremierDernierElementsPage(pageEvent);
//   → `evenement.getComponent()` retourne null (mock non stubbé)
//   → cast (DataTable) null puis `.getFirst()` → NullPointerException.
//
// Le step d'init était de toute façon inutile : la cible écrase la valeur.
class RecursiveDeepStrategyFixCTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.fixc"

    @Test
    fun `Fix C — write-only field with public assignor method gets IMPLICIT`() {
        // Reproduit le pattern Astrea : la cible écrit `dernierElement`,
        // une AUTRE méthode publique avec paramètre l'écrit aussi.
        val fake = fixture {
            klass("$pkg.Ctrl") {
                field("dernierElement", T("int"))
                field("premierElement", T("int"))
                method("rechercher", returns = T("void"),
                    body = "this.dernierElement = pagination.getNumeroPage(); this.premierElement = 0;") {
                    param("pagination", T("$pkg.PaginationDTO"))
                    calls("$pkg.PaginationDTO", "getNumeroPage")
                    assigns("$pkg.Ctrl", "dernierElement",
                        rhsExpression = "pagination.getNumeroPage()")
                    assigns("$pkg.Ctrl", "premierElement", rhsExpression = "0")
                }
                method("calculerPage", returns = T("void"),
                    body = "this.premierElement = evenement.getFirst(); this.dernierElement = evenement.getFirst() + evenement.getRows();") {
                    param("evenement", T("$pkg.PageEvent"))
                    calls("$pkg.PageEvent", "getFirst")
                    calls("$pkg.PageEvent", "getRows")
                    assigns("$pkg.Ctrl", "premierElement",
                        rhsExpression = "evenement.getFirst()")
                    assigns("$pkg.Ctrl", "dernierElement",
                        rhsExpression = "evenement.getFirst() + evenement.getRows()")
                }
            }
            klass("$pkg.PaginationDTO") {
                method("getNumeroPage", returns = T("java.lang.Integer"))
            }
            klass("$pkg.PageEvent") {
                method("getFirst", returns = T("int"))
                method("getRows", returns = T("int"))
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "rechercher" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        for (fieldName in listOf("dernierElement", "premierElement")) {
            val proto = result.initProtocol[fieldName] ?: continue
            val s = proto.recommendedStrategy
            assertFalse(s is InitStrategy.CALL_PUBLIC_WITH_ARGS,
                "Fix C : `$fieldName` (write-only par la cible) ne doit PAS recevoir " +
                    "CALL_PUBLIC_WITH_ARGS via `calculerPage` — le step @BeforeEach " +
                    "est inutile et NPE-ait sur le param mocké. Vu : ${s::class.simpleName}")
            assertTrue(s === InitStrategy.IMPLICIT,
                "Fix C : `$fieldName` doit être IMPLICIT (branche 2bis). " +
                    "Vu : ${s::class.simpleName}")
        }
    }

    @Test
    fun `Fix C — read-only field with public assignor method still gets init step`() {
        // Verrou inverse : un champ LU par la cible (valeur d'entrée requise)
        // doit toujours élire l'assignatrice publique — 2bis ne s'applique pas.
        val fake = fixture {
            klass("$pkg.Ctrl2") {
                field("config", T("$pkg.Config"))
                method("handle", returns = T("void"),
                    body = "this.config.run();") {
                    reads("$pkg.Ctrl2", "config")
                    calls("$pkg.Config", "run")
                }
                method("setupConfig", returns = T("void"),
                    body = "this.config = new Config();") {
                    assigns("$pkg.Ctrl2", "config", rhsExpression = "new Config()")
                }
            }
            klass("$pkg.Config") {
                method("run", returns = T("void"))
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl2")!!
        val target = fake.listMethodsOf("$pkg.Ctrl2").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val proto = result.initProtocol["config"]
        if (proto != null) {
            val s = proto.recommendedStrategy
            assertFalse(s === InitStrategy.IMPLICIT,
                "Fix C : `config` est read-only dans la cible — sa valeur d'entrée " +
                    "compte, IMPLICIT serait un NPE garanti. Vu : ${s::class.simpleName}")
        }
    }
}

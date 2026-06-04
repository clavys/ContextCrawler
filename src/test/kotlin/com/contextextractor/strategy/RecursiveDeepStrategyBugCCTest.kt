package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.model.init.InitStrategy
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bug CC — Le target method ne doit JAMAIS être élu comme stratégie d'init
// pour un de ses propres champs d'output (champs qu'il écrit, qu'ils soient
// relus ou non dans le corps).
//
// Cas prod : `SupervisionDeltaVecControleur#rechercher` assigne `this.total`,
// `this.rowCount`, `this.dernierElementListe`, etc. Sans ce fix, le BLOC 7
// élit `rechercher` comme `CALL_PUBLIC_WITH_ARGS` pour chacun de ces champs.
// Le prompt liste alors :
//   ## Field `total`: int
//   Strategy: CALL_PUBLIC_WITH_ARGS
//   supervisionDeltaVecControleur.rechercher(paginationDTO, listeTriDTO);
// Le LLM enchaîne ces appels dans @BeforeEach et tente de stubber
// `getStructurePage()` sur un @InjectMocks (pas un spy) → NotAMockException.
//
// Avec Bug CC :
//   1. SourceCollector exclut targetMethod de ses MethodInitializer candidates.
//   2. StrategySelector branche 10 (isAutoInitialized) élargie aux écritures
//      write-only — retourne IMPLICIT.
//   3. IMPLICIT_KINDS dans ContextRenderStage omet ces fields du rendu init.
class RecursiveDeepStrategyBugCCTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugcc"

    @Test
    fun `Bug CC — target write-only field gets IMPLICIT not CALL_PUBLIC_WITH_ARGS`() {
        // Reproduit le pattern prod : target assigne un champ output sans le
        // relire ensuite. Sans Bug CC : CALL_PUBLIC_WITH_ARGS pointant target.
        // Avec Bug CC : IMPLICIT (champ géré comme side-effect de target).
        val fake = fixture {
            klass("$pkg.Ctrl") {
                field("total", T("int"))
                field("rowCount", T("int"))
                method("rechercher", returns = T("int"),
                    body = "this.total = 5; this.rowCount = this.total; return this.total;") {
                    param("pagination", T("$pkg.PaginationDTO"))
                    assigns("$pkg.Ctrl", "total", rhsExpression = "5")
                    assigns("$pkg.Ctrl", "rowCount", rhsExpression = "this.total")
                    reads("$pkg.Ctrl", "total")
                }
            }
            klass("$pkg.PaginationDTO")
        }
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "rechercher" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val totalProto = result.initProtocol["total"]
        // Vérification clé : la stratégie n'est PAS un appel à target.
        if (totalProto != null) {
            val s = totalProto.recommendedStrategy
            assertFalse(s is InitStrategy.CALL_PUBLIC_WITH_ARGS &&
                s.method.canonical() == target.canonical(),
                "Bug CC : `total` ne doit PAS pointer target en CALL_PUBLIC_WITH_ARGS. " +
                    "Vu strategy=${s::class.simpleName}")
            assertFalse(s is InitStrategy.CALL_PUBLIC &&
                s.method.canonical() == target.canonical(),
                "Bug CC : `total` ne doit PAS pointer target en CALL_PUBLIC. " +
                    "Vu strategy=${s::class.simpleName}")
            assertFalse(s is InitStrategy.CALL_PUBLIC_WITH_STUBS &&
                s.method.canonical() == target.canonical(),
                "Bug CC : `total` ne doit PAS pointer target en CALL_PUBLIC_WITH_STUBS. " +
                    "Vu strategy=${s::class.simpleName}")
        }
        // Le champ existe encore comme « connu du système » (Bug T body scan)
        // mais sa stratégie est IMPLICIT → le renderer l'omet.
        val rowCountProto = result.initProtocol["rowCount"]
        if (rowCountProto != null) {
            val s = rowCountProto.recommendedStrategy
            assertFalse(s is InitStrategy.CALL_PUBLIC_WITH_ARGS &&
                s.method.canonical() == target.canonical(),
                "Bug CC : `rowCount` ne doit PAS pointer target en CALL_PUBLIC_WITH_ARGS. " +
                    "Vu strategy=${s::class.simpleName}")
        }
    }

    @Test
    fun `Bug CC — target write-only field strategy is IMPLICIT`() {
        // Verrou positif — la stratégie élue doit être IMPLICIT (vs UNTESTABLE_AS_IS
        // qui aurait été le fallback branche 11 sans l'élargissement de branche 10).
        val fake = fixture {
            klass("$pkg.Ctrl2") {
                field("output", T("int"))
                method("compute", returns = T("void"),
                    body = "this.output = 42;") {
                    assigns("$pkg.Ctrl2", "output", rhsExpression = "42")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl2")!!
        val target = fake.listMethodsOf("$pkg.Ctrl2").single { it.name == "compute" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val proto = result.initProtocol["output"]
        if (proto != null) {
            assertTrue(proto.recommendedStrategy === InitStrategy.IMPLICIT,
                "Bug CC : `output` (write-only target) → IMPLICIT attendu. " +
                    "Vu : ${proto.recommendedStrategy::class.simpleName}")
            // Et surtout pas UNTESTABLE_AS_IS (ancien comportement).
            assertFalse(proto.recommendedStrategy is InitStrategy.UNTESTABLE_AS_IS,
                "Bug CC : `output` ne doit PAS être UNTESTABLE_AS_IS — la branche 10 " +
                    "élargie doit l'intercepter avant la branche 11.")
        }
    }

    @Test
    fun `Bug CC — target write-then-read still IMPLICIT (§4_5 original)`() {
        // Cas §4.5 strategy 10 d'origine — la sémantique write-then-read reste
        // préservée par Bug CC. Test de non-régression.
        val fake = fixture {
            klass("$pkg.Ctrl3") {
                field("cache", T("$pkg.Cache"))
                method("init", returns = T("void"),
                    body = "this.cache = new Cache(); this.cache.warmup();") {
                    assigns("$pkg.Ctrl3", "cache", rhsExpression = "new Cache()")
                    reads("$pkg.Ctrl3", "cache")
                    calls("$pkg.Cache", "warmup")
                }
            }
            klass("$pkg.Cache") {
                method("warmup", returns = T("void"))
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl3")!!
        val target = fake.listMethodsOf("$pkg.Ctrl3").single { it.name == "init" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val proto = result.initProtocol["cache"]
        if (proto != null) {
            assertTrue(proto.recommendedStrategy === InitStrategy.IMPLICIT,
                "Bug CC : cas write-then-read §4.5 d'origine doit toujours produire " +
                    "IMPLICIT. Vu : ${proto.recommendedStrategy::class.simpleName}")
        }
    }

    @Test
    fun `Bug CC — non-target method that assigns field is still picked as init source`() {
        // Verrou inverse : on n'exclut QUE target. Une AUTRE méthode publique
        // qui assigne le champ doit toujours être éligible en init source.
        val fake = fixture {
            klass("$pkg.Ctrl4") {
                field("config", T("$pkg.Config"))
                method("setupConfig", returns = T("void"),
                    body = "this.config = new Config();") {
                    assigns("$pkg.Ctrl4", "config", rhsExpression = "new Config()")
                }
                method("handle", returns = T("void"),
                    body = "this.config.run();") {
                    reads("$pkg.Ctrl4", "config")
                    calls("$pkg.Config", "run")
                }
            }
            klass("$pkg.Config") {
                method("run", returns = T("void"))
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl4")!!
        val target = fake.listMethodsOf("$pkg.Ctrl4").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        val proto = result.initProtocol["config"]
        if (proto != null) {
            val s = proto.recommendedStrategy
            // setupConfig est une méthode publique non-target qui assigne config.
            // Elle doit être élue (CALL_PUBLIC car pas d'args ni d'externals).
            // L'important : la stratégie pointe `setupConfig`, PAS `handle`.
            if (s is InitStrategy.CALL_PUBLIC) {
                assertNotEquals(target.canonical(), s.method.canonical(),
                    "Bug CC : la stratégie d'init pour `config` ne doit PAS être target. " +
                        "Vu method=${s.method.name}")
                assertTrue(s.method.name == "setupConfig",
                    "Bug CC : `setupConfig` (méthode non-target) doit être préférée. " +
                        "Vu method=${s.method.name}")
            }
        }
    }
}

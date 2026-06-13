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

// V1.4.7 — Bug TT (règle 10bis) : un PARAMÈTRE de target lu via des getters qui
// ne sont pas adressables par la construction est mocké, pas construit.
//
// Vrai défaut Astrea 4.4 (re-run V1.4.6) : `MemoireSaisieSegment` (param) est lu
// via `getNumeroOrdreGr()` (discriminant du switch). Classé DATA_STRUCTURE
// [CONSTRUCTOR], le LLM le construit via le ctor à 7 args aux noms abrégés
// (`numOrdreGr`) PUIS, ne faisant pas le mapping ctor↔getter, stubbe l'objet réel
// → MissingMethodInvocationException. Fix : si un getter lu n'a ni param de ctor
// ni setter de même nom normalisé, on MOCK le param (le LLM stubbe les getters).
//
// Non-régression cruciale : `OrderRequest` (case92/93) — param lu via getId/
// getRawAmount, ctor `(id, rawAmount)` mappant par nom → reste DATA_STRUCTURE.
class ParamConstructibilityTTTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugtt"

    @Test
    fun `TT — param read via a getter not addressable by construction is MOCKED`() {
        val fake = fixture {
            // Forme MemoireSaisieSegment : ctor à arg au nom ABRÉGÉ (numOrdreGr),
            // getter getNumeroOrdreGr, aucun setter pour ce champ.
            klass("$pkg.MemoSeg") {
                field("numeroOrdreGr", T("int"))
                method("<init>", visibility = "public") {
                    param("numOrdreGr", T("int"))
                }
                method("getNumeroOrdreGr", returns = T("int"))
            }
            klass("$pkg.Ctrl", annotations = listOf("org.springframework.stereotype.Service")) {
                method("target", returns = T("void"),
                    body = "switch (memo.getNumeroOrdreGr()) { default: }") {
                    param("memo", T("$pkg.MemoSeg"))
                    calls("$pkg.MemoSeg", "getNumeroOrdreGr")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.MemoSeg" in result.mocks,
            "le param dont getNumeroOrdreGr() (ctor `numOrdreGr` abrégé, pas de setter) " +
                "n'est pas adressable par construction doit être MOCKÉ — sinon le LLM " +
                "construit puis stubbe l'objet réel. Mocks : ${result.mocks.keys}")
        assertFalse("$pkg.MemoSeg" in result.dataStructures,
            "il ne doit PAS rester en DATA_STRUCTURE. DataStructures : ${result.dataStructures.keys}")
    }

    @Test
    fun `TT non-regression — param whose getters map to ctor args stays DATA_STRUCTURE`() {
        val fake = fixture {
            // Forme OrderRequest (case92/93) : ctor (id, rawAmount) mappant par NOM
            // aux getters getId/getRawAmount → constructible sans stub.
            klass("$pkg.OrderRequest") {
                method("<init>", visibility = "public") {
                    param("id", T("java.lang.Long"))
                    param("rawAmount", T("double"))
                }
                method("getId", returns = T("java.lang.Long"))
                method("getRawAmount", returns = T("double"))
            }
            klass("$pkg.Svc", annotations = listOf("org.springframework.stereotype.Service")) {
                method("process", returns = T("void"),
                    body = "req.getId(); req.getRawAmount();") {
                    param("req", T("$pkg.OrderRequest"))
                    calls("$pkg.OrderRequest", "getId")
                    calls("$pkg.OrderRequest", "getRawAmount")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Svc")!!
        val target = fake.listMethodsOf("$pkg.Svc").single { it.name == "process" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.OrderRequest" in result.dataStructures,
            "un param dont les getters mappent par nom au ctor reste constructible " +
                "(DATA_STRUCTURE) — non-régression case92/93. " +
                "DataStructures : ${result.dataStructures.keys}")
        assertFalse("$pkg.OrderRequest" in result.mocks,
            "il ne doit PAS basculer en MOCK. Mocks : ${result.mocks.keys}")
    }
}

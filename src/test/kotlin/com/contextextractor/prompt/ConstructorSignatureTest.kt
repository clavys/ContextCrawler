package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptBuilder
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// V1.4.4 Bug LL — un DTO [CONSTRUCTOR] doit rendre les signatures EXACTES de
// ses constructeurs publics.
//
// Vrai bug Astrea 4.4 : `MemoireSaisieSegment [CONSTRUCTOR]` ne listait que
// `Fields:` → le LLM a inventé un constructeur « tous-les-champs » à 15 args
// (`Cannot resolve constructor 'MemoireSaisieSegment(int, int, null, ...)'`).
// §3.4 Phase 2 spécifiait « capturer paramètres » depuis le début — réalisé
// ici côté materializer + mapper + renderer.
class ConstructorSignatureTest {

    private val builder = PromptBuilder.defaultPipeline()
    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val pkg = "com.test.bugll"

    @Test
    fun `Bug LL — CONSTRUCTOR dto renders its exact public constructor signatures`() {
        val fake = fixture {
            klass("$pkg.MemoSegment") {
                field("numeroOrdreGr", T("int"))
                field("codeTypeSegment", T("int"))
                field("rangRelatifSegment", T("int"))
                method("<init>", visibility = "public") {
                    param("numeroOrdreGr", T("int"))
                    param("codeTypeSegment", T("int"))
                }
                method("getNumeroOrdreGr", returns = T("int"))
            }
            klass("$pkg.Service", isInterface = true) {
                method("traiter", returns = T("void")) {
                    param("memo", T("$pkg.MemoSegment"))
                }
            }
            klass("$pkg.Ctrl") {
                field("service", T("$pkg.Service"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("target", returns = T("void"),
                    body = "this.service.traiter(memo);") {
                    param("memo", T("$pkg.MemoSegment"))
                    reads("$pkg.Ctrl", "service")
                    calls("$pkg.Service", "traiter", "$pkg.MemoSegment")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "target" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        val context = builder.build(mapper.map(result)).substringBefore("=== CONSTRAINTS ===")

        val dtoSection = context.substringAfter("## $pkg.MemoSegment", "<ENTREE ABSENTE>")
            .substringBefore("## ", missingDelimiterValue = context.substringAfter("## $pkg.MemoSegment", "<ENTREE ABSENTE>"))
        assertTrue(dtoSection.contains("Available public constructors"),
            "le bloc constructeurs doit être rendu pour un [CONSTRUCTOR]. Section:\n$dtoSection")
        assertTrue(dtoSection.contains("- new MemoSegment(int numeroOrdreGr, int codeTypeSegment)"),
            "la signature EXACTE du ctor public doit être listée (sinon le LLM " +
                "invente un ctor tous-les-champs — Astrea 4.4). Section:\n$dtoSection")
        assertTrue(dtoSection.contains("do NOT invent arguments"),
            "l'interdiction explicite d'inventer des arguments doit accompagner la liste")
    }
}

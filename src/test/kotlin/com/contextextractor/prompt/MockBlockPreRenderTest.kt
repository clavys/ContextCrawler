package com.contextextractor.prompt

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.prompt.PromptBuilder
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.strategies.recursive.ContextResultTreeMapper
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bug X — Pré-rendre le bloc `@Mock private Type typeName;` dans la section
// `# Mocks`, prêt à copier-coller. En prod, le LLM produisait régulièrement :
//   PageDataDTO pageDataDTO = new PageDataDTO();
//   LigneResultatSupervisionDeltaVecDTO ligneXxx = new LigneResultatXxx();
// alors que ces types étaient dans `# Mocks` → violation R4 → erreur compile.
// Avec Bug X : le bloc Java prêt-à-copier rend l'erreur impossible à
// « inventer » — le LLM n'a qu'à le copier verbatim.
class MockBlockPreRenderTest {

    private val builder = PromptBuilder.defaultPipeline()
    private val strategy = RecursiveDeepStrategy()
    private val mapper = ContextResultTreeMapper()
    private val pkg = "com.test.bugx"

    private fun buildFor(fake: FakeIntrospector,
                         sutFqn: String, methodName: String): String {
        val sut = fake.resolveClass(sutFqn)!!
        val target = fake.listMethodsOf(sutFqn).single { it.name == methodName }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)
        return builder.build(mapper.map(result))
    }

    @Test
    fun `Bug X — mocks section starts with copy-paste-ready Java block`() {
        val fake = fixture {
            klass("$pkg.Svc", isInterface = true) {
                method("doIt", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl") {
                field("svc", T("$pkg.Svc"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.svc.doIt();") {
                    reads("$pkg.Ctrl", "svc")
                    calls("$pkg.Svc", "doIt")
                }
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl", "handle")

        // Le bloc Java pré-rendu doit être présent.
        assertTrue(output.contains("Copy these field declarations VERBATIM into the test class:"),
            "Bug X : intro du bloc copy-paste manquante. Output Mocks:\n" +
                output.substringAfter("# Mocks", "").substringBefore("# ", "").take(400))
        // La déclaration exacte doit apparaître.
        assertTrue(output.contains("@Mock\nprivate $pkg.Svc svc;"),
            "Bug X : déclaration `@Mock private Svc svc;` attendue prête à copier. " +
                "Output (section Mocks):\n" +
                output.substringAfter("# Mocks", "").substringBefore("# ", "").take(400))
    }

    @Test
    fun `Bug X — multiple mock types each get a declaration`() {
        val fake = fixture {
            klass("$pkg.SvcA", isInterface = true) {
                method("doA", returns = T("java.lang.String"))
            }
            klass("$pkg.SvcB", isInterface = true) {
                method("doB", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl2") {
                field("svcA", T("$pkg.SvcA"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("svcB", T("$pkg.SvcB"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.svcA.doA() + this.svcB.doB();") {
                    reads("$pkg.Ctrl2", "svcA")
                    reads("$pkg.Ctrl2", "svcB")
                    calls("$pkg.SvcA", "doA")
                    calls("$pkg.SvcB", "doB")
                }
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl2", "handle")

        assertTrue(output.contains("private $pkg.SvcA svcA;"),
            "Bug X : déclaration SvcA présente. Output Mocks:\n" +
                output.substringAfter("# Mocks", "").substringBefore("# ", "").take(500))
        assertTrue(output.contains("private $pkg.SvcB svcB;"),
            "Bug X : déclaration SvcB présente. Output Mocks:\n" +
                output.substringAfter("# Mocks", "").substringBefore("# ", "").take(500))
    }

    @Test
    fun `Bug X — variable name is camelCase typeName matching style rule`() {
        // CONSTRAINTS dit : "a variable of type `TypeName` MUST be named `typeName`".
        // Le bloc pré-rendu doit respecter cette convention.
        val fake = fixture {
            klass("$pkg.PageDataDTO", isInterface = true)
            klass("$pkg.Ctrl3") {
                field("pageDataDTO", T("$pkg.PageDataDTO"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("void"),
                    body = "this.pageDataDTO.toString();") {
                    reads("$pkg.Ctrl3", "pageDataDTO")
                    calls("$pkg.PageDataDTO", "toString")
                }
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl3", "handle")

        // Variable = `pageDataDTO` (premier caractère minuscule, reste verbatim).
        assertTrue(output.contains("private $pkg.PageDataDTO pageDataDTO;"),
            "Bug X : varName camelCase. Output Mocks:\n" +
                output.substringAfter("# Mocks", "").substringBefore("# ", "").take(400))
    }

    @Test
    fun `Bug X — no mock = no copy-paste block emitted`() {
        // Verrou inverse : un SUT sans mock n'émet pas le bloc.
        val fake = fixture {
            klass("$pkg.Ctrl4") {
                method("compute", returns = T("int"),
                    body = "return 42;")
            }
        }
        val output = buildFor(fake, "$pkg.Ctrl4", "compute")

        assertFalse(output.contains("Copy these field declarations VERBATIM"),
            "Bug X : pas de bloc pré-rendu si aucun mock. Output:\n${output.take(800)}")
    }
}

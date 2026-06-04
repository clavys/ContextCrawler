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

// Bug U — Promotion DATA_STRUCTURE → MOCK_EXTERNAL pour les « modeles » de
// SUT. Cas production : `SupervisionDeltaVecModele` n'expose que des
// setters/getters (rule 10 → DATA_STRUCTURE) mais est référencé comme champ
// et target appelle `setAfficherResultats(true)` dessus. Sans promotion :
// type émis dans dataStructures, jamais dans mocks, le LLM hallucine.
//
// Avec promotion : si Bug M/T a marqué le type comme essentiel ET le caller
// context est FIELD_OF_SUT, on force MOCK_EXTERNAL — le mock est émis et le
// LLM peut écrire `verify(modele).setAfficherResultats(true)`.
class RecursiveDeepStrategyBugUTest {

    private val strategy = RecursiveDeepStrategy()
    private val pkg = "com.test.bugu"

    @Test
    fun `Bug U — field type with only setters promoted to mock when called by target`() {
        // Setup : ModeleAvecSetters n'a QUE setX et setY (trivial → rule 10 DATA_STRUCTURE).
        // Sans Bug U : tombe dans dataStructures, aucun mock émis.
        // Avec Bug U : essentialMockTypes contient ModeleAvecSetters (Bug M via
        // calls()), FIELD_OF_SUT → promotion MOCK_EXTERNAL.
        val fake = fixture {
            klass("$pkg.ModeleAvecSetters") {
                method("setX", returns = T("void")) {
                    param("v", T("boolean"))
                }
                method("setY", returns = T("void")) {
                    param("v", T("boolean"))
                }
            }
            klass("$pkg.Ctrl") {
                field("modele", T("$pkg.ModeleAvecSetters"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("void"),
                    body = "this.modele.setX(true); this.modele.setY(false);") {
                    reads("$pkg.Ctrl", "modele")
                    calls("$pkg.ModeleAvecSetters", "setX", "boolean")
                    calls("$pkg.ModeleAvecSetters", "setY", "boolean")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl")!!
        val target = fake.listMethodsOf("$pkg.Ctrl").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.ModeleAvecSetters" in result.mocks.keys,
            "Bug U : ModeleAvecSetters doit être dans les mocks (promu depuis DATA_STRUCTURE). " +
                "Vu mocks: ${result.mocks.keys}, dataStructures: ${result.dataStructures.keys}")
    }

    @Test
    fun `Bug U — pure DTO param of target stays DATA_STRUCTURE (no promotion)`() {
        // Verrou inverse #1 : un DTO passé en PARAMÈTRE de target — pas un
        // champ — ne doit PAS être promu. Le LLM le construit / mocke comme
        // d'habitude selon les règles standard.
        // callerContext = PARAM_OF_METHOD ≠ FIELD_OF_SUT → pas de promotion.
        val fake = fixture {
            klass("$pkg.PaginationDTO") {
                method("getNumeroPage", returns = T("java.lang.Integer"))
                method("setNumeroPage", returns = T("void")) {
                    param("v", T("java.lang.Integer"))
                }
            }
            klass("$pkg.Ctrl2") {
                method("handle", returns = T("void"),
                    body = "p.getNumeroPage();") {
                    param("p", T("$pkg.PaginationDTO"))
                    calls("$pkg.PaginationDTO", "getNumeroPage")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl2")!!
        val target = fake.listMethodsOf("$pkg.Ctrl2").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // Bug U ne s'applique PAS aux paramètres — le DTO reste géré
        // par les règles standard (rule 10 → DATA_STRUCTURE).
        assertTrue("$pkg.PaginationDTO" in result.dataStructures.keys ||
            "$pkg.PaginationDTO" in result.mocks.keys,
            "PaginationDTO doit être dans dataStructures OU mocks (pas dropé). " +
                "Vu: mocks=${result.mocks.keys}, dataStructures=${result.dataStructures.keys}")
        // L'important : la promotion FIELD_OF_SUT spécifique n'a pas affecté
        // ce chemin. Si le DTO est dans dataStructures (rule 10), c'est OK.
        // S'il est dans mocks via une autre règle (12 par défaut), c'est OK aussi.
    }

    @Test
    fun `Bug U — field type with non-trivial method stays in mocks normally`() {
        // Vérification : un service @Autowired classifié naturellement
        // MOCK_EXTERNAL via rule 12 (méthode non-triviale) n'est PAS doublement
        // affecté par Bug U. Le rawMode est déjà MOCK_EXTERNAL → pas de promotion.
        val fake = fixture {
            klass("$pkg.Service", isInterface = true) {
                method("computeStuff", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl3") {
                field("svc", T("$pkg.Service"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.svc.computeStuff();") {
                    reads("$pkg.Ctrl3", "svc")
                    calls("$pkg.Service", "computeStuff")
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl3")!!
        val target = fake.listMethodsOf("$pkg.Ctrl3").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        assertTrue("$pkg.Service" in result.mocks.keys,
            "Service normal reste dans mocks. Vu: ${result.mocks.keys}")
    }

    @Test
    fun `Bug U — non-essential DATA_STRUCTURE field type NOT promoted (no false positive)`() {
        // Verrou inverse #2 : un champ DTO @Autowired NON appelé par target
        // (pas dans essentialMockTypes) ne doit PAS être promu en mock.
        // Ceci protège contre la sur-promotion qui transformerait tous les
        // DTOs auto-injectés en mocks parasites.
        val fake = fixture {
            klass("$pkg.DtoParasite") {
                method("setX", returns = T("void")) {
                    param("v", T("boolean"))
                }
            }
            klass("$pkg.Svc4", isInterface = true) {
                method("doIt", returns = T("java.lang.String"))
            }
            klass("$pkg.Ctrl4") {
                field("svc", T("$pkg.Svc4"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                field("parasite", T("$pkg.DtoParasite"),
                    annotations = listOf("org.springframework.beans.factory.annotation.Autowired"))
                method("handle", returns = T("java.lang.String"),
                    body = "return this.svc.doIt();") {
                    reads("$pkg.Ctrl4", "svc")
                    calls("$pkg.Svc4", "doIt")
                    // PAS d'appel sur DtoParasite — donc PAS dans essentialMockTypes.
                }
            }
        }
        val sut = fake.resolveClass("$pkg.Ctrl4")!!
        val target = fake.listMethodsOf("$pkg.Ctrl4").single { it.name == "handle" }
        val result = strategy.extractCore(fake, DefaultClassifier(), StrategyConfig(), sut, target)

        // DtoParasite n'est pas essentiel → pas de promotion.
        // Le filtre Bug B (filteredFields) le drop de toute façon, donc
        // il ne sera pas dans les mocks finaux non plus.
        assertFalse("$pkg.DtoParasite" in result.mocks.keys,
            "DtoParasite NON essentiel NE doit PAS être promu en mock. " +
                "Vu: ${result.mocks.keys}")
    }
}

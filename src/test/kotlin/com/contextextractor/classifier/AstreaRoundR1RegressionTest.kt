package com.contextextractor.classifier

import com.contextextractor.core.classifier.DefaultContextAwareClassifier
import com.contextextractor.core.classifier.ExtractionMode
import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.extractor.ResolvedType
import com.contextextractor.core.model.refs.ClassReference
import com.contextextractor.core.model.refs.UsageSite
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Phase R1-A — reproducteurs des bugs Astrea observés lors du round V1.2-R1
// sur SupervisionDeltaVecControleur (cf VALIDATION_DEFECTS.md §Round V1.2-R1).
//
// Ces tests sont écrits pour ECHOUER (red) tant que les bugs systémiques #A
// et #B ne sont pas corrigés. Quand un patch sera appliqué, ces tests
// deviendront le verrou de non-régression.
//
// **Pourquoi pas modifier ContextAwareClassifierTest existant** ? Les tests
// existants couvrent les règles 1-16 sur des cas synthétiques minimaux. Les
// cas Astrea sont des patterns COMPOSITES (multi-UsageSite agrégés) qu'il
// faut tester séparément pour ne pas brouiller la lecture.
class AstreaRoundR1RegressionTest {

    private val classifier = DefaultContextAwareClassifier()

    // ── Helpers (calqués sur ContextAwareClassifierTest) ─────────────────────

    private fun ty(fqn: String) =
        ResolvedType(rawType = fqn.substringAfterLast('.'), fqName = fqn)

    private fun desc(
        fqn: String,
        isInterface: Boolean = false,
        isEnum: Boolean = false,
        annotations: List<String> = emptyList()
    ) = ClassDescriptor(
        fqn = fqn,
        simpleName = fqn.substringAfterLast('.'),
        superFqn = null,
        isInterface = isInterface,
        isEnum = isEnum,
        annotations = annotations
    )

    private fun callerMethod(name: String = "rechercher", returns: ResolvedType = ty("void")) =
        MethodSignature(name = name, returnType = returns, parameters = emptyList(), visibility = "public")

    private fun callSite(
        ownerOfTarget: String,
        ownerOfCaller: String,
        methodName: String,
        argTypes: List<String> = emptyList()
    ) = UsageSite.AsCallTarget(
        call = MethodCall(ownerOfTarget, methodName, argTypes),
        resolvedMethod = null,
        callerOwnerFqn = ownerOfCaller,
        callerMethod = callerMethod()
    )

    private fun fieldSite(
        fieldName: String,
        typeFqn: String,
        declaringClass: String,
        annotations: List<String> = emptyList()
    ) = UsageSite.AsFieldOfSut(
        ClassField(
            name = fieldName,
            type = ty(typeFqn),
            visibility = "private",
            annotations = annotations,
            declaredIn = declaringClass
        )
    )

    private fun ref(fqn: String, descriptor: ClassDescriptor?, vararg usages: UsageSite) =
        ClassReference(fqn, descriptor, usages.toList())

    // ── Bug #A — R1-1 — field of SUT + pure getter call → DOIT être MOCK ──

    // Cas reproduit : Astrea case 4.1 SupervisionDeltaVecControleur#rechercher
    //   body: this.tableauSupervisionDeltaVecModele.getCriteresRecherche()
    //
    // tableauSupervisionDeltaVecModele est :
    //   - un field of SUT (champ injecté du controleur)
    //   - appelé uniquement via un pur getter `getCriteresRecherche()`
    //   - le type lui-même n'a que des getters/setters (POJO type)
    //
    // V1.2 observé : DATA_STRUCTURE (via Rule 14 all-trivial-methods)
    //   → manque de # Mocks → LLM stube sur le mauvais receiver → ne compile pas
    //
    // V1.2 attendu : MOCK_EXTERNAL — un field injecté DOIT être mocké même si
    //   on n'en lit que des getters. Sinon NPE au runtime de test.
    @Test
    fun `Bug A R1-1 — field of SUT with only pure-getter call must be MOCK_EXTERNAL`() {
        val pojoMethods = listOf(
            MethodSignature("getCriteresRecherche",
                returnType = ty("java.util.Map"),
                parameters = emptyList(),
                visibility = "public"),
            MethodSignature("setCriteresRecherche",
                returnType = ty("void"),
                parameters = listOf(Parameter("v", ty("java.util.Map"))),
                visibility = "public")
        )
        val r = ref(
            "fr.gouv.justice.astrea.idt.coordination.modele.TableauSupervisionDeltaVecModele",
            desc("fr.gouv.justice.astrea.idt.coordination.modele.TableauSupervisionDeltaVecModele"),
            fieldSite(
                fieldName = "tableauSupervisionDeltaVecModele",
                typeFqn = "fr.gouv.justice.astrea.idt.coordination.modele.TableauSupervisionDeltaVecModele",
                declaringClass = "fr.gouv.justice.astrea.idt.coordination.controleur.SupervisionDeltaVecControleur"
            ),
            callSite(
                ownerOfTarget = "fr.gouv.justice.astrea.idt.coordination.modele.TableauSupervisionDeltaVecModele",
                ownerOfCaller = "fr.gouv.justice.astrea.idt.coordination.controleur.SupervisionDeltaVecControleur",
                methodName = "getCriteresRecherche"
            )
        )
        assertEquals(
            ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(r, emptySet(), descriptorMethods = pojoMethods),
            "Field injecté de SUT appelé via pur getter doit être MOCK_EXTERNAL — " +
                "sinon NPE runtime (le test n'instancie pas le field). " +
                "Cf VALIDATION_DEFECTS.md R1-1 case 4.1 Astrea."
        )
    }

    // Variant R2-1 — case 4.2 SupervisionDeltaVecControleur#rechercherTypeMessage
    //   body: this.supervisionDeltaVecModele.getListeTypeMessage()
    //
    // Même cause, autre cas — confirme que c'est systémique, pas un edge case.
    @Test
    fun `Bug A R2-1 — same pattern on supervisionDeltaVecModele#getListeTypeMessage`() {
        val pojoMethods = listOf(
            MethodSignature("getListeTypeMessage",
                returnType = ty("java.util.List"),
                parameters = emptyList(),
                visibility = "public"),
            MethodSignature("setListeTypeMessage",
                returnType = ty("void"),
                parameters = listOf(Parameter("v", ty("java.util.List"))),
                visibility = "public"),
            MethodSignature("setAfficherResultats",
                returnType = ty("void"),
                parameters = listOf(Parameter("v", ty("boolean"))),
                visibility = "public")
        )
        val r = ref(
            "fr.gouv.justice.astrea.idt.coordination.modele.SupervisionDeltaVecModele",
            desc("fr.gouv.justice.astrea.idt.coordination.modele.SupervisionDeltaVecModele"),
            fieldSite(
                fieldName = "supervisionDeltaVecModele",
                typeFqn = "fr.gouv.justice.astrea.idt.coordination.modele.SupervisionDeltaVecModele",
                declaringClass = "fr.gouv.justice.astrea.idt.coordination.controleur.SupervisionDeltaVecControleur"
            ),
            callSite(
                ownerOfTarget = "fr.gouv.justice.astrea.idt.coordination.modele.SupervisionDeltaVecModele",
                ownerOfCaller = "fr.gouv.justice.astrea.idt.coordination.controleur.SupervisionDeltaVecControleur",
                methodName = "getListeTypeMessage"
            )
        )
        assertEquals(
            ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(r, emptySet(), descriptorMethods = pojoMethods),
            "Field injecté appelé via getListeTypeMessage doit être MOCK_EXTERNAL. " +
                "Cf VALIDATION_DEFECTS.md R2-1 case 4.2 Astrea."
        )
    }

    // ── Non-régression — cas où le défaut DTO doit RESTER ────────────────────

    // OrderEntity.getId() lu par target → DTO légitime (POJO instancié dans
    // le test, accédé par le SUT comme valeur de donnée).
    // CE TEST DOIT RESTER GREEN — le fix de #A ne doit pas casser ce cas.
    @Test
    fun `Non-regression — POJO read-only entity stays DATA_STRUCTURE`() {
        val pojoMethods = listOf(
            MethodSignature("getId",
                returnType = ty("java.lang.Long"),
                parameters = emptyList(),
                visibility = "public")
        )
        val r = ref(
            "com.testproject.case00_baseline.OrderEntity",
            desc("com.testproject.case00_baseline.OrderEntity"),
            // PAS de fieldSite — c'est le retour d'un repository, pas un field injecté
            callSite(
                ownerOfTarget = "com.testproject.case00_baseline.OrderEntity",
                ownerOfCaller = "com.testproject.case00_baseline.OrderService",
                methodName = "getId"
            )
        )
        assertEquals(
            ExtractionMode.DATA_STRUCTURE,
            classifier.classify(r, emptySet(), descriptorMethods = pojoMethods),
            "POJO non-injecté lu via getter doit RESTER DATA_STRUCTURE (case00 baseline)."
        )
    }

    // ── Bug R3-2 (inverse de #A) — type instancié `new` doit primer sur MOCK ──

    // Cas reproduit : Astrea case 4.3 SupervisionDeltaVecControleur#redirigerVersDetailsDeltaVec
    //   body: final ReferenceModele referenceModele = new ReferenceModele();
    //         referenceModele.setReference(referenceDTO);
    //
    // ReferenceModele est instancié dans le body ET a un setter appelé.
    // V1.2 observé : MOCK_EXTERNAL (Rule 8 fire car setter = mutator)
    //   → le @Mock injecté n'est jamais utilisé (instance locale construite)
    //
    // V1.2 attendu : DATA_STRUCTURE (Rule 11 isInstantiatedInBody devrait
    // primer sur Rule 8 — on ne peut pas mocker une instance créée par
    // le code de prod).
    // ── Bug R3-1 — type utilisé EXCLUSIVEMENT comme stub return → MOCK ──────

    // Cas reproduit : Astrea case 4.3 SupervisionDeltaVecControleur#redirigerVersDetailsDeltaVec
    //   body: sessionAstreaModele.getPageData(NavigationEnum.CONSULTATION_REFERENCE)
    //
    // PageDataDTO est :
    //   - le type de retour de getPageData(...) → AsStubReturn
    //   - ni field SUT, ni instancié dans body, ni appelé, ni sur surface target
    //   - un POJO concret avec ctors qui prennent des args (pas de no-arg ctor)
    //
    // V1.2 R1 observé : DATA_STRUCTURE (via R14 all-trivial)
    //   → LLM tente `new PageDataDTO()` → no-arg ctor inexistant → ne compile pas
    //
    // V1.2 R2-B attendu : MOCK_EXTERNAL (via R11bis nouvelle règle).
    //   LLM déclare `@Mock PageDataDTO` et l'utilise dans `thenReturn(pageDataDTO)`.
    @Test
    fun `Bug R3-1 — type used only as stub return must be MOCK_EXTERNAL`() {
        // Toutes méthodes triviales sur le POJO (getters/setters Lombok-like).
        val pageDataMethods = listOf(
            MethodSignature("getMnemnoTraitement",
                returnType = ty("java.lang.String"),
                parameters = emptyList(), visibility = "public"),
            MethodSignature("setMnemnoTraitement",
                returnType = ty("void"),
                parameters = listOf(Parameter("v", ty("java.lang.String"))),
                visibility = "public"),
            MethodSignature("getCodeGr",
                returnType = ty("java.lang.String"),
                parameters = emptyList(), visibility = "public")
        )
        val r = ref(
            "fr.gouv.justice.astrea.transverse.dto.PageDataDTO",
            desc("fr.gouv.justice.astrea.transverse.dto.PageDataDTO"),
            // Seul UsageSite : AsStubReturn — return value de getPageData() sur sessionAstreaModele
            UsageSite.AsStubReturn(
                mockOwnerFqn = "fr.gouv.justice.astrea.com.coordination.modele.SessionAstreaModele",
                mockMethod = MethodSignature("getPageData",
                    returnType = ty("fr.gouv.justice.astrea.transverse.dto.PageDataDTO"),
                    parameters = listOf(Parameter("nav", ty("fr.gouv.justice.astrea.transverse.navigation.NavigationEnum"))),
                    visibility = "public")
            )
        )
        assertEquals(
            ExtractionMode.MOCK_EXTERNAL,
            classifier.classify(r, emptySet(), descriptorMethods = pageDataMethods),
            "Type utilisé EXCLUSIVEMENT comme stub return doit être MOCK_EXTERNAL — " +
                "sinon le LLM tente new X() et ne compile pas si X n'a pas de no-arg ctor. " +
                "Cf VALIDATION_DEFECTS.md R3-1 case 4.3 Astrea."
        )
    }

    // Non-régression : un ENUM utilisé comme stub return doit RESTER DATA_STRUCTURE
    // (R9 ENUM prioritaire sur R11bis). LLM utilisera `Status.ACTIVE` au lieu de
    // mocker un enum (impossible).
    @Test
    fun `Non-regression — enum used as stub return stays DATA_STRUCTURE`() {
        val r = ref(
            "com.foo.Status",
            desc("com.foo.Status", isEnum = true),
            UsageSite.AsStubReturn(
                mockOwnerFqn = "com.foo.Service",
                mockMethod = MethodSignature("getStatus",
                    returnType = ty("com.foo.Status"),
                    parameters = emptyList(), visibility = "public")
            )
        )
        assertEquals(
            ExtractionMode.DATA_STRUCTURE,
            classifier.classify(r, emptySet()),
            "ENUM comme stub return doit rester DATA_STRUCTURE — R9 prime sur R11bis."
        )
    }

    @Test
    fun `Bug R3-2 — type instantiated in body with mutator call must be DATA_STRUCTURE not MOCK`() {
        val pojoMethods = listOf(
            MethodSignature("setReference",
                returnType = ty("void"),
                parameters = listOf(Parameter("v", ty("ReferenceDTO"))),
                visibility = "public")
        )
        val r = ref(
            "fr.gouv.justice.astrea.idt.coordination.modele.ReferenceModele",
            desc("fr.gouv.justice.astrea.idt.coordination.modele.ReferenceModele"),
            UsageSite.AsInstantiationInBody(callerMethod("redirigerVersDetailsDeltaVec")),
            callSite(
                ownerOfTarget = "fr.gouv.justice.astrea.idt.coordination.modele.ReferenceModele",
                ownerOfCaller = "fr.gouv.justice.astrea.idt.coordination.controleur.SupervisionDeltaVecControleur",
                methodName = "setReference",
                argTypes = listOf("fr.gouv.justice.astrea.idt.dto.ReferenceDTO")
            )
        )
        assertEquals(
            ExtractionMode.DATA_STRUCTURE,
            classifier.classify(r, emptySet(), descriptorMethods = pojoMethods),
            "Type créé via `new X()` dans target body doit être DATA_STRUCTURE " +
                "même si un mutator est appelé dessus. Sinon le @Mock est inutile " +
                "(instance locale = celle dans le body de prod, pas le mock). " +
                "Cf VALIDATION_DEFECTS.md R3-2 case 4.3 Astrea."
        )
    }
}

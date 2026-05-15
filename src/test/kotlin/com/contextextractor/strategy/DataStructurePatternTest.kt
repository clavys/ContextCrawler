package com.contextextractor.strategy

import com.contextextractor.core.classifier.DefaultClassifier
import com.contextextractor.core.model.ConstructionPattern
import com.contextextractor.core.model.ContextResult
import com.contextextractor.core.strategy.StrategyConfig
import com.contextextractor.strategies.recursive.RecursiveDeepStrategy
import com.contextextractor.fakes.FakeIntrospector
import com.contextextractor.fakes.FixtureBuilder
import com.contextextractor.fakes.T
import com.contextextractor.fakes.Tg
import com.contextextractor.fakes.fixture
import com.contextextractor.fakes.listMethodsOf
import com.contextextractor.core.extractor.ResolvedType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4d — couverture des 7 patterns DATA_STRUCTURE de STRATEGIE.md §3.4.
//
// Stratégie de test : pour chaque pattern, on construit un SUT minimal dont la
// méthode `target()` retourne le DTO sous test. recurseDataStructure est privée
// donc on déclenche son exécution via BLOC 6e (récursion sur le type de retour).
class DataStructurePatternTest {

    private val pkg = "com.test.dto"
    private val sutFqn = "$pkg.Service"
    private val strategy = RecursiveDeepStrategy()

    // -- Helpers --------------------------------------------------------------

    private fun buildFixture(
        returnType: ResolvedType,
        block: FixtureBuilder.() -> Unit
    ): FakeIntrospector = fixture {
        block()
        klass(sutFqn, annotations = listOf("org.springframework.stereotype.Service")) {
            method("target", returns = returnType)
        }
    }

    private fun runStrategy(fake: FakeIntrospector): ContextResult {
        val sut = fake.resolveClass(sutFqn)!!
        val targetMethod = fake.listMethodsOf(sutFqn).single { it.name == "target" }
        return strategy.extractCore(
            introspector = fake,
            classifier = DefaultClassifier(),
            config = StrategyConfig(),
            sut = sut,
            targetMethod = targetMethod
        )
    }

    // ── 1. Pattern RECORD ─────────────────────────────────────────────────────

    @Test
    fun `RECORD - components are captured as fields, no Phase 3 fields`() {
        val fake = buildFixture(T("$pkg.Money")) {
            klass("$pkg.Money", isRecord = true) {
                // Constructeur canonique = composants du record.
                method("<init>", returns = T("$pkg.Money")) {
                    param("amount", T("java.math.BigDecimal"))
                    param("currency", T("java.lang.String"))
                }
                method("amount", returns = T("java.math.BigDecimal"))
                method("currency", returns = T("java.lang.String"))
                // Champ « privé » synthétique : ne doit PAS apparaître dans
                // dataFields parce que RECORD est dans PHASE3_SKIP.
                field("amount", T("java.math.BigDecimal"))
                field("currency", T("java.lang.String"))
            }
        }
        val result = runStrategy(fake)
        val ds = result.dataStructures["$pkg.Money"] ?: error("Money attendu en dataStructures")
        assertEquals(ConstructionPattern.RECORD, ds.pattern)
        assertEquals(listOf("amount", "currency"), ds.fields.map { it.name })
        // Pas de doublon : Phase 3 sautée pour RECORD, les champs viennent du ctor.
        assertEquals(2, ds.fields.size)
    }

    // ── 2. Pattern BUILDER (annotation @lombok.Builder) ──────────────────────

    @Test
    fun `BUILDER - lombok Builder annotation triggers BUILDER pattern with synth builderInfo`() {
        val fake = buildFixture(T("$pkg.UserBuilt")) {
            klass("$pkg.UserBuilt", annotations = listOf("lombok.Builder")) {
                field("name", T("java.lang.String"))
                field("age", T("int"))
                method("<init>", returns = T("$pkg.UserBuilt")) // ctor synthétique
            }
        }
        val result = runStrategy(fake)
        val ds = result.dataStructures["$pkg.UserBuilt"]!!
        assertEquals(ConstructionPattern.BUILDER, ds.pattern)
        // Phase 3 capture aussi les champs (BUILDER n'est pas dans PHASE3_SKIP).
        assertEquals(setOf("name", "age"), ds.fields.map { it.name }.toSet())
        // builderInfo synthétisé à partir des champs (pas de classe builder explicite).
        assertNotNull(ds.builderInfo)
        assertEquals(setOf("name", "age"), ds.builderInfo!!.methods.map { it.name }.toSet())
    }

    // ── 3. Pattern BUILDER (méthode statique builder() explicite) ─────────────

    @Test
    fun `BUILDER - explicit static builder() drives builderInfo from the dedicated builder class`() {
        val fake = buildFixture(T("$pkg.Order")) {
            klass("$pkg.OrderBuilder") {
                method("id", returns = T("$pkg.OrderBuilder")) { param("id", T("java.lang.Long")) }
                method("note", returns = T("$pkg.OrderBuilder")) { param("note", T("java.lang.String")) }
                method("build", returns = T("$pkg.Order"))
            }
            klass("$pkg.Order") {
                field("id", T("java.lang.Long"))
                field("note", T("java.lang.String"))
                method("builder", returns = T("$pkg.OrderBuilder"), isStatic = true)
                method("<init>", returns = T("$pkg.Order"))
            }
        }
        val result = runStrategy(fake)
        val ds = result.dataStructures["$pkg.Order"]!!
        assertEquals(ConstructionPattern.BUILDER, ds.pattern)
        val info = ds.builderInfo ?: error("builderInfo attendu")
        assertEquals("$pkg.OrderBuilder", info.builderClass)
        // `build()` exclu, deux setters fluents capturés.
        assertEquals(setOf("id", "note"), info.methods.map { it.name }.toSet())
    }

    // ── 4. Pattern CONSTRUCTOR via @JsonCreator ───────────────────────────────

    @Test
    fun `CONSTRUCTOR - JsonCreator constructor triggers CONSTRUCTOR pattern, fields from class not ctor`() {
        val fake = buildFixture(T("$pkg.Address")) {
            klass("$pkg.Address") {
                field("street", T("java.lang.String"))
                field("zip", T("java.lang.String"))
                method("<init>", returns = T("$pkg.Address")) // no-arg
                method(
                    "<init>",
                    returns = T("$pkg.Address"),
                    annotations = listOf("com.fasterxml.jackson.annotation.JsonCreator")
                ) {
                    param("street", T("java.lang.String"))
                    param("zip", T("java.lang.String"))
                }
            }
        }
        val result = runStrategy(fake)
        val ds = result.dataStructures["$pkg.Address"]!!
        assertEquals(ConstructionPattern.CONSTRUCTOR, ds.pattern)
        // Phase 2 ne duplique pas les params ; Phase 3 fait foi.
        assertEquals(setOf("street", "zip"), ds.fields.map { it.name }.toSet())
        assertEquals(2, ds.fields.size)
    }

    // ── 5. Pattern CONSTRUCTOR via constructeur public à params (sans marqueur) ─

    @Test
    fun `CONSTRUCTOR - plain public ctor with params and no other markers`() {
        val fake = buildFixture(T("$pkg.Coord")) {
            klass("$pkg.Coord") {
                field("x", T("int"))
                field("y", T("int"))
                method("<init>", returns = T("$pkg.Coord")) {
                    param("x", T("int"))
                    param("y", T("int"))
                }
            }
        }
        val result = runStrategy(fake)
        val ds = result.dataStructures["$pkg.Coord"]!!
        assertEquals(ConstructionPattern.CONSTRUCTOR, ds.pattern)
    }

    // ── 6. Pattern SETTER_BASED ──────────────────────────────────────────────

    @Test
    fun `SETTER_BASED - no-arg ctor and setters triggers SETTER_BASED pattern`() {
        val fake = buildFixture(T("$pkg.Form")) {
            klass("$pkg.Form") {
                field("title", T("java.lang.String"))
                field("body", T("java.lang.String"))
                method("<init>", returns = T("$pkg.Form")) // no-arg only
                method("setTitle") { param("title", T("java.lang.String")) }
                method("setBody") { param("body", T("java.lang.String")) }
                method("getTitle", returns = T("java.lang.String"))
                method("getBody", returns = T("java.lang.String"))
            }
        }
        val result = runStrategy(fake)
        val ds = result.dataStructures["$pkg.Form"]!!
        assertEquals(ConstructionPattern.SETTER_BASED, ds.pattern)
        assertEquals(setOf("title", "body"), ds.fields.map { it.name }.toSet())
    }

    // ── 7. Pattern ENUM ──────────────────────────────────────────────────────

    @Test
    fun `ENUM - constants are exhaustively captured, no Phase 4 recursion`() {
        val fake = buildFixture(T("$pkg.Status")) {
            // Référence à un type complexe via le « champ » de l'enum — il ne
            // doit PAS être capturé en dataStructures (pas de Phase 4).
            klass("$pkg.MetadataMarker") {
                field("name", T("java.lang.String"))
                method("<init>", returns = T("$pkg.MetadataMarker"))
            }
            klass(
                "$pkg.Status",
                isEnum = true,
                enumValues = listOf("PENDING", "ACTIVE", "ARCHIVED")
            ) {
                // Champ d'instance d'enum (Java permet) — Phase 3 sautée.
                field("marker", T("$pkg.MetadataMarker"))
            }
        }
        val result = runStrategy(fake)
        val ds = result.dataStructures["$pkg.Status"]!!
        assertEquals(ConstructionPattern.ENUM, ds.pattern)
        assertEquals(listOf("PENDING", "ACTIVE", "ARCHIVED"), ds.enumValues)
        // Vigilance #3 : enum exhaustif et pas de Phase 4 — MetadataMarker
        // ne doit JAMAIS apparaître dans dataStructures via cet enum.
        assertFalse("$pkg.MetadataMarker" in result.dataStructures.keys,
            "ENUM ne doit pas déclencher Phase 4 sur ses champs")
        assertTrue(ds.fields.isEmpty(), "Phase 3 sautée pour ENUM")
    }

    // ── 8. Pattern SEALED ────────────────────────────────────────────────────

    @Test
    fun `SEALED - permitted subclasses are recursed and shared field type is visited once`() {
        val fake = buildFixture(T("$pkg.Payment")) {
            // Currency partagé entre les deux sous-classes — VisitRegistry doit
            // empêcher la double visite (vigilance #2).
            klass("$pkg.Currency") {
                field("code", T("java.lang.String"))
                method("<init>", returns = T("$pkg.Currency"))
                method("getCode", returns = T("java.lang.String"))
            }
            klass("$pkg.CashPayment") {
                field("currency", T("$pkg.Currency"))
                field("amount", T("double"))
                method("<init>", returns = T("$pkg.CashPayment"))
            }
            klass("$pkg.CardPayment") {
                field("currency", T("$pkg.Currency"))
                field("cardNumber", T("java.lang.String"))
                method("<init>", returns = T("$pkg.CardPayment"))
            }
            klass(
                "$pkg.Payment",
                isSealed = true,
                permittedSubclasses = listOf("$pkg.CashPayment", "$pkg.CardPayment")
            )
        }
        val result = runStrategy(fake)
        val ds = result.dataStructures["$pkg.Payment"]!!
        assertEquals(ConstructionPattern.SEALED, ds.pattern)
        assertEquals(listOf("$pkg.CashPayment", "$pkg.CardPayment"), ds.sealedSubs)

        // Les deux sous-classes sont bien récurées.
        assertTrue("$pkg.CashPayment" in result.dataStructures.keys)
        assertTrue("$pkg.CardPayment" in result.dataStructures.keys)
        // Currency, partagé par les deux subs, n'est visité qu'une fois.
        assertTrue("$pkg.Currency" in result.dataStructures.keys)
        assertEquals(1, result.dataStructures.values.count { it.fqName == "$pkg.Currency" })
    }

    // ── 9. Pattern STATIC_FACTORY ─────────────────────────────────────────────

    @Test
    fun `STATIC_FACTORY - of-method triggers STATIC_FACTORY, factories captured, fields from Phase 3`() {
        val fake = buildFixture(T("$pkg.Page")) {
            klass("$pkg.Page") {
                field("number", T("int"))
                field("size", T("int"))
                method("<init>", returns = T("$pkg.Page"))
                method("of", returns = T("$pkg.Page"), isStatic = true) {
                    param("number", T("int"))
                    param("size", T("int"))
                }
            }
        }
        val result = runStrategy(fake)
        val ds = result.dataStructures["$pkg.Page"]!!
        assertEquals(ConstructionPattern.STATIC_FACTORY, ds.pattern)
        assertEquals(1, ds.factoryMethods.size)
        assertEquals("of", ds.factoryMethods[0].name)
        assertEquals(2, ds.factoryMethods[0].parameters.size)
        // Phase 3 alimente les fields normalement.
        assertEquals(setOf("number", "size"), ds.fields.map { it.name }.toSet())
    }

    // ── 10. Vigilance #1 : @Builder + @JsonCreator → @Builder gagne ───────────

    @Test
    fun `Pattern priority - Builder annotation wins over JsonCreator constructor`() {
        val fake = buildFixture(T("$pkg.Hybrid")) {
            klass("$pkg.Hybrid", annotations = listOf("lombok.Builder")) {
                field("a", T("java.lang.String"))
                method(
                    "<init>",
                    returns = T("$pkg.Hybrid"),
                    annotations = listOf("com.fasterxml.jackson.annotation.JsonCreator")
                ) {
                    param("a", T("java.lang.String"))
                }
            }
        }
        val result = runStrategy(fake)
        val ds = result.dataStructures["$pkg.Hybrid"]!!
        // Spec §3.4 : @Builder est testé AVANT @JsonCreator. Verrou explicite.
        assertEquals(ConstructionPattern.BUILDER, ds.pattern)
    }

    // ── 11. Phase 4 - DTO imbriqué ───────────────────────────────────────────

    @Test
    fun `Phase 4 - nested DTO field is recursed`() {
        val fake = buildFixture(T("$pkg.Outer")) {
            klass("$pkg.Inner") {
                field("value", T("java.lang.String"))
                method("<init>", returns = T("$pkg.Inner"))
                method("getValue", returns = T("java.lang.String"))
            }
            klass("$pkg.Outer") {
                field("inner", T("$pkg.Inner"))
                method("<init>", returns = T("$pkg.Outer"))
                method("getInner", returns = T("$pkg.Inner"))
            }
        }
        val result = runStrategy(fake)
        assertTrue("$pkg.Outer" in result.dataStructures.keys)
        assertTrue("$pkg.Inner" in result.dataStructures.keys,
            "Phase 4 doit récurer sur les champs DTO complexes")
    }

    // ── 12. Phase 4 - List<DTO> ──────────────────────────────────────────────

    @Test
    fun `Phase 4 - generic List of DTO recurses on type-arg`() {
        val fake = buildFixture(T("$pkg.Bag")) {
            klass("$pkg.Item") {
                field("name", T("java.lang.String"))
                method("<init>", returns = T("$pkg.Item"))
                method("getName", returns = T("java.lang.String"))
            }
            klass("$pkg.Bag") {
                field("items", Tg("java.util.List", T("$pkg.Item")))
                method("<init>", returns = T("$pkg.Bag"))
                method("getItems", returns = Tg("java.util.List", T("$pkg.Item")))
            }
        }
        val result = runStrategy(fake)
        assertTrue("$pkg.Bag" in result.dataStructures.keys)
        assertTrue("$pkg.Item" in result.dataStructures.keys,
            "Phase 4 doit creuser dans les type-args des collections")
        assertNull(result.dataStructures["java.util.List"],
            "Le type brut java.util.List ne doit pas apparaître comme DTO")
    }

    // ── 13. Phase 4 - cycle stoppé par VisitRegistry ─────────────────────────

    @Test
    fun `Phase 4 - DTO cycle A-B-A is stopped by VisitRegistry`() {
        val fake = buildFixture(T("$pkg.NodeA")) {
            klass("$pkg.NodeA") {
                field("b", T("$pkg.NodeB"))
                method("<init>", returns = T("$pkg.NodeA"))
                method("getB", returns = T("$pkg.NodeB"))
            }
            klass("$pkg.NodeB") {
                field("a", T("$pkg.NodeA"))
                method("<init>", returns = T("$pkg.NodeB"))
                method("getA", returns = T("$pkg.NodeA"))
            }
        }
        val result = runStrategy(fake)
        // Pas d'infinité : on a exactement deux entrées, aucune duplication.
        assertEquals(2, result.dataStructures.values.count { it.fqName.startsWith("$pkg.Node") })
        assertTrue("$pkg.NodeA" in result.dataStructures.keys)
        assertTrue("$pkg.NodeB" in result.dataStructures.keys)
    }

    // ── 14. Phase 3 - annotations de validation capturées ─────────────────────

    @Test
    fun `Phase 3 - validation annotations are captured and required reflects NotNull-NotBlank`() {
        val fake = buildFixture(T("$pkg.Profile")) {
            klass("$pkg.Profile") {
                field(
                    "email",
                    T("java.lang.String"),
                    annotations = listOf("jakarta.validation.constraints.NotBlank")
                )
                field(
                    "bio",
                    T("java.lang.String"),
                    annotations = listOf("jakarta.validation.constraints.Size")
                )
                field(
                    "name",
                    T("java.lang.String") // pas d'annotation
                )
                method("<init>", returns = T("$pkg.Profile"))
            }
        }
        val result = runStrategy(fake)
        val ds = result.dataStructures["$pkg.Profile"]!!
        val byName = ds.fields.associateBy { it.name }
        assertEquals(
            listOf("jakarta.validation.constraints.NotBlank"),
            byName["email"]!!.validationAnnotations
        )
        assertTrue(byName["email"]!!.required, "@NotBlank implique required=true")
        assertEquals(
            listOf("jakarta.validation.constraints.Size"),
            byName["bio"]!!.validationAnnotations
        )
        assertFalse(byName["bio"]!!.required, "@Size n'implique pas required=true")
        assertTrue(byName["name"]!!.validationAnnotations.isEmpty())
        assertFalse(byName["name"]!!.required)
    }
}

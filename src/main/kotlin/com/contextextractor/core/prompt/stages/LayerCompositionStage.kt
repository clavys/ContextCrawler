package com.contextextractor.core.prompt.stages

import com.contextextractor.core.model.ContextNode
import com.contextextractor.core.model.MetaKeys
import com.contextextractor.core.prompt.PromptContext
import com.contextextractor.core.prompt.PromptStage
import com.contextextractor.core.prompt.meta.LayerKind

// Troisième stage du pipeline — ARCHITECTURE.md §7, PROMPT_FORMAT.md §"Full
// Prompt Structure". Assemble les 5 layers en un seul markdown :
//
//     [SYSTEM]
//
//     === CONTEXT ===
//     [CONTEXT]
//
//     === ADDITIONAL INSTRUCTIONS ===     ← seulement si USER_ENRICHMENT non vide
//     [USER_ENRICHMENT]
//
//     === CONSTRAINTS ===
//     [CONSTRAINTS]
//
//     === INSTRUCTION ===
//     [INSTRUCTION]
//
// **Templates par défaut** : V1 livre des templates statiques inline (cf
// `companion object DefaultTemplates`). L'étape 6 brancera un `TemplateLoader`
// qui pourra surcharger via `.contextextractor.yml` — le seam est le constructeur
// `Templates(...)`. Tant qu'aucune layer n'est pré-remplie par l'appelant
// (typiquement pour USER_ENRICHMENT), les valeurs par défaut sont utilisées.
//
// **Substitutions** dans CONSTRAINTS + INSTRUCTION (PROMPT_FORMAT.md ligne 256) :
//   * `[TargetClassName]`            → nom court de la classe SUT
//   * `[targetMethodName]`           → nom de la méthode cible (sans args)
//   * `[same package as target class]` → package du SUT
//   * bloc `{IF tree.tronque == true} … {END IF}` → conservé seulement si
//     `tree.truncated == true` ; sinon supprimé intégralement
//   * `{raisonsTroncature}`          → liste jointe des raisons (vivant
//     uniquement à l'intérieur du bloc IF)
//
// **Convention markers `=== X ===`** : les séparateurs vivent ICI, pas dans
// les templates. Ça permet à l'utilisateur de remplacer un template sans
// devoir penser au marker — et garantit qu'un template oublié de marker
// ne casse pas l'assemblage final.
class LayerCompositionStage(
    private val templates: Templates = Templates.defaults()
) : PromptStage {

    override val id: String = "layer-composition"

    override fun apply(ctx: PromptContext): PromptContext {
        val placeholders = buildPlaceholders(ctx)

        // Layers déjà fournies par l'appelant (typiquement USER_ENRICHMENT
        // depuis le dialog) ont priorité. Sinon → templates par défaut.
        val systemText = ctx.layers[LayerKind.SYSTEM] ?: templates.system
        val contextText = ctx.layers[LayerKind.CONTEXT]
            ?: error("LayerCompositionStage: layer CONTEXT manquante. " +
                "ContextRenderStage doit s'exécuter avant ce stage.")
        val userEnrichment = ctx.layers[LayerKind.USER_ENRICHMENT]?.takeIf { it.isNotBlank() }
        val constraintsText = applyTemplate(
            ctx.layers[LayerKind.CONSTRAINTS] ?: templates.constraints,
            placeholders,
            ctx.tree.truncated,
            ctx.tree.truncationReasons
        )
        val instructionText = applyTemplate(
            ctx.layers[LayerKind.INSTRUCTION] ?: templates.instruction,
            placeholders,
            ctx.tree.truncated,
            ctx.tree.truncationReasons
        )

        val sb = StringBuilder()
        sb.appendLine(systemText.trimEnd())
        sb.appendLine()
        sb.appendLine("=== CONTEXT ===")
        sb.appendLine(contextText.trimEnd())
        sb.appendLine()
        if (userEnrichment != null) {
            sb.appendLine("=== ADDITIONAL INSTRUCTIONS ===")
            sb.appendLine(userEnrichment.trimEnd())
            sb.appendLine()
        }
        sb.appendLine("=== CONSTRAINTS ===")
        sb.appendLine(constraintsText.trimEnd())
        sb.appendLine()
        sb.appendLine("=== INSTRUCTION ===")
        sb.appendLine(instructionText.trimEnd())

        ctx.finalText = sb.toString()
        return ctx
    }

    // ── Substitution helpers ─────────────────────────────────────────────────

    private fun buildPlaceholders(ctx: PromptContext): Map<String, String> {
        val root: ContextNode = ctx.tree.root
        val classFqn = root.title.substringBefore('#')
        val pkg = classFqn.substringBeforeLast('.', missingDelimiterValue = "")
        val shortName = classFqn.substringAfterLast('.', missingDelimiterValue = classFqn)
        val canonical = root.metadata[MetaKeys.METHOD_CANONICAL].orEmpty()
        val methodName = canonical.substringBefore('(', missingDelimiterValue = canonical)
        return mapOf(
            "[TargetClassName]" to shortName,
            "[targetMethodName]" to methodName,
            "[same package as target class]" to pkg
        )
    }

    private fun applyTemplate(
        template: String,
        placeholders: Map<String, String>,
        truncated: Boolean,
        reasons: List<String>
    ): String {
        var text = expandTruncationBlock(template, truncated, reasons)
        for ((needle, value) in placeholders) {
            text = text.replace(needle, value)
        }
        return text
    }

    // Bloc `{IF tree.tronque == true} … {END IF}` : conservé si truncated,
    // supprimé sinon. Le contenu peut référencer `{raisonsTroncature}`. Le
    // bloc peut s'étaler sur plusieurs lignes — on consomme aussi les sauts
    // de ligne adjacents pour éviter des trous visuels dans la sortie.
    private fun expandTruncationBlock(
        template: String,
        truncated: Boolean,
        reasons: List<String>
    ): String {
        val match = TRUNCATION_REGEX.find(template) ?: return template
        val body = match.groupValues[1]
        val replacement = if (truncated) {
            body.replace("{raisonsTroncature}", reasons.joinToString(", "))
                .trimEnd('\n')
        } else {
            ""
        }
        // Supprime aussi les sauts de ligne immédiatement avant/après le bloc
        // pour éviter des doubles vides quand on retire la branche.
        val before = template.substring(0, match.range.first).trimEnd('\n')
        val after = template.substring(match.range.last + 1).trimStart('\n')
        return buildString {
            append(before)
            if (replacement.isNotEmpty()) {
                append('\n')
                append(replacement)
            }
            if (after.isNotEmpty()) {
                append('\n')
                append(after)
            }
        }
    }

    // ── Default templates (V1 inline; étape 6 ajoutera l'override YAML) ──────

    data class Templates(
        val system: String,
        val constraints: String,
        val instruction: String
    ) {
        companion object {
            fun defaults(): Templates = Templates(
                system = DEFAULT_SYSTEM,
                constraints = DEFAULT_CONSTRAINTS,
                instruction = DEFAULT_INSTRUCTION
            )
        }
    }

    companion object {
        private val TRUNCATION_REGEX = Regex(
            """\{IF\s+tree\.tronque\s*==\s*true}([\s\S]*?)\{END\s+IF}""",
            RegexOption.MULTILINE
        )

        // Baseline provider-agnostic — PROMPT_FORMAT.md §"Section 1 — SYSTEM".
        // Bug L : remontée des 4 règles les plus violées en haut du SYSTEM avec
        // marqueurs [R1]..[R4]. Les règles répétées dans CONSTRAINTS deviennent
        // une checklist de vérification (action verbs : « scan », « verify »).
        private val DEFAULT_SYSTEM = """
            You are an expert Java unit test writer.

            Output ONLY raw valid Java code. No explanation, no markdown, no code fences.
            Just the raw content of the .java file, starting with "package ...".
            Stack: Java 11, JUnit 5 (org.junit.jupiter.api), Mockito 4.x, AssertJ.

            ═══ CRITICAL RULES — violated most often, read FIRST ═══
            [R1] @Test required on EVERY test method (org.junit.jupiter.api.Test).
                 Missing @Test → silent skip by JUnit → 0-coverage report.
            [R2] NO comments inside the code — no `//`, no `/* */`, no Javadoc.
                 Identifiers must be self-explanatory.
            [R3] Test class and test methods MUST be package-private (no `public`
                 keyword) — JUnit 5 does not require it.
            [R4] NO `new TypeName(...)` for any type listed under `# Mocks` in
                 CONTEXT. Declare `@Mock TypeName typeName;` instead.
            ═══════════════════════════════════════════════════════
        """.trimIndent()

        // PROMPT_FORMAT.md §"Section 4 — CONSTRAINTS" — texte verbatim,
        // y compris le bloc `{IF tree.tronque == true}` géré par
        // expandTruncationBlock().
        //
        // Bug L : nouvel ordre par criticité —
        //   1. Critical rules (verification checklist) — recopie [R1]..[R4]
        //   2. Test class (structure attendue)
        //   3. Style (strict)
        //   4. Anti-hallucination contract
        //   5. Imports verbatim
        //   6. Required static imports
        //   7. Hard prohibitions (INVALID/VALID)
        //   8. Initialization / Coverage / Wrapping / Untestable / Truncation /
        //      Compilation contract
        private val DEFAULT_CONSTRAINTS = """
            # Critical rules — verification checklist (re-check BEFORE output)
            Before returning the Java file, scan it line-by-line and verify:
            - [R1] EVERY test method MUST be annotated `@Test` (org.junit.jupiter.api.Test).
                   A method without `@Test` is invisible to JUnit and the test is silently
                   skipped — this is the most common reason for a 0-coverage report.
                   Scan: every `void xxx()` declaration must have `@Test` on the line above.
            - [R2] NO comments anywhere in the file — no `//`, no `/* */`, no Javadoc on test methods.
                   Search for `//`, `/*`, `*/` and remove EVERY match (including TODOs).
            - [R3] Test class and test methods MUST be package-private (no `public` keyword).
                   Search for `public` on the class line and on every test method — remove all.
            - [R4] NO `new TypeName(...)` for any type listed under `# Mocks` in CONTEXT.
                   Search every `new ` and verify the type is NOT in `# Mocks`.
                   If it is, replace with `@Mock TypeName typeName;` (see Hard prohibitions).

            # Test class
            - Generate ONE single Java 11 test class
            - Class name: [TargetClassName]Test
            - Package: [same package as target class]
            - Use @ExtendWith(MockitoExtension.class)
            - Mocks via @Mock; the class under test via @InjectMocks (unless explicit construction is required)
            - Assertions: AssertJ ONLY (assertThat...)
            - Stubbing: Mockito ONLY (when/thenReturn/thenThrow)

            # Style (strict)
            - NO comments anywhere in the file — no `//`, no `/* */`, no Javadoc on test methods
            - Test class and test methods MUST be package-private (no `public` keyword)
            - Field declarations may keep `private` for clarity but `public` is forbidden on tests
            - Variable naming: a variable of type `TypeName` MUST be named `typeName`
              (first letter lowercased, rest verbatim). Example: `PageDataDTO pageDataDTO;`,
              `LigneResultatSupervisionDeltaVecDTO ligneResultatSupervisionDeltaVecDTO;`.
              No abbreviations, no `dto`, no `obj`, no single-letter names.
            - Method naming: camelCase only — NO underscore `_`, NO hyphen `-` in method names.
              Example: `redirigerVersDetailsDeltaVecNominal()`, not `rediriger_vers_details_nominal()`.

            # Anti-hallucination contract (strict)
            - NEVER stub a method on the class under test (via `doReturn(...).when(...).xxx()`)
              unless `xxx` appears explicitly in the CONTEXT section. Methods listed under
              "Methods to stub via spy" are the ONLY class-under-test methods you may stub.
            - NEVER invent helper methods on the class under test (no `cut.getXxx()` unless
              `getXxx` is in CONTEXT). If a field value is needed, mock the field's type instead.

            # Imports — strict FQN copy (anti-hallucination)
            - For every type referenced in CONTEXT, copy its FQN VERBATIM into an import.
              CONTEXT line `## fr.x.y.z.MyService` → `import fr.x.y.z.MyService;`
            - NEVER shorten or substitute the package path by analogy with another type.
              EXAMPLE: if CONTEXT lists `fr.gouv.justice.idt.service.local.pp.MyService`
              and another type lives in `fr.gouv.justice.idt.dto.OtherDTO`, you MUST NOT
              import `fr.gouv.justice.idt.dto.MyService` — that's a hallucination.
            - When in doubt, locate the type's `##` header in CONTEXT and copy its
              full path character-by-character.

            # Required static imports (strict — non-compilable otherwise)
            - For EVERY Mockito static method you use, add the matching `import static`.
              Common pairs you MUST cover when used:
                * `when(...)`        → `import static org.mockito.Mockito.when;`
                * `verify(...)`      → `import static org.mockito.Mockito.verify;`
                * `doReturn(...)`    → `import static org.mockito.Mockito.doReturn;`
                * `doAnswer(...)`    → `import static org.mockito.Mockito.doAnswer;`
                * `doThrow(...)`     → `import static org.mockito.Mockito.doThrow;`
                * `doNothing()`      → `import static org.mockito.Mockito.doNothing;`
                * `spy(...)`         → `import static org.mockito.Mockito.spy;`
                * `mock(...)`        → `import static org.mockito.Mockito.mock;`
                * `eq(...)`          → `import static org.mockito.ArgumentMatchers.eq;`
                * `any(...)`         → `import static org.mockito.ArgumentMatchers.any;`
                * `anyBoolean()`     → `import static org.mockito.ArgumentMatchers.anyBoolean;`
                * `anyInt()`         → `import static org.mockito.ArgumentMatchers.anyInt;`
                * `anyLong()`        → `import static org.mockito.ArgumentMatchers.anyLong;`
                * `anyString()`      → `import static org.mockito.ArgumentMatchers.anyString;`
            - For AssertJ: `import static org.assertj.core.api.Assertions.assertThat;`
              (and `Assertions.fail` if needed for UNTESTABLE_AS_IS or TODO bodies).

            # Hard prohibitions
            - NO ReflectionTestUtils, NO setAccessible, NO Field manipulation
            - NO @SpringBootTest, NO @WebMvcTest, NO @DataJpaTest, NO context loading
            - NO instantiation of types listed under "Mocks" in CONTEXT — this is
              the most violated rule. Verify each type used in `new TypeName(...)`
              is NOT in `# Mocks`. If it is, declare it as `@Mock` instead.
                * INVALID: `PageDataDTO pageDataDTO = new PageDataDTO();`
                  when `# Mocks` lists `## fr.x.PageDataDTO` — even with a no-args
                  ctor, you MUST declare `@Mock PageDataDTO pageDataDTO;` and let
                  Mockito create it.
                * VALID:   `@Mock PageDataDTO pageDataDTO;` then use the field
                  directly in `when(...).thenReturn(pageDataDTO)`.
            - NO invocation of method signatures NOT listed in CONTEXT
            - NO real network/database/filesystem access

            # Initialization protocol
            - The init protocol described in CONTEXT is PRESCRIPTIVE
            - Apply it in @BeforeEach in the listed order
            - Do NOT skip steps even if they look redundant

            # Coverage scope
            - Cover the nominal path of [targetMethodName]
            - Cover each conditional branch listed in CONTEXT
            - Cover each exception listed in CONTEXT

            # Checked exceptions on test methods (Bug Y)
            - If CONTEXT lists `throws (declared): ExceptionType`, EVERY test method
              that calls the target MUST declare `throws ExceptionType` on its
              signature, OR catch it inside the method body.
            - Java will REFUSE to compile a test method that calls a target
              declaring a checked exception without handling it.
              EXAMPLE invalid (will not compile):
                @Test
                void rechercher_nominal() {
                    sut.rechercher(...);  // ← compile error: unreported exception
                }
              EXAMPLE valid:
                @Test
                void rechercher_nominal() throws AstreaFonctionnelleException {
                    sut.rechercher(...);
                }
            - If MULTIPLE exceptions are declared, list them all comma-separated:
              `throws AstreaFonctionnelleException, OtherException`.

            # Typed Collections in stubs (Bug Z)
            - `Collections.emptyMap()` / `Collections.emptyList()` return raw
              `Map<K,V>` / `List<E>` — Java's inference often FAILS to match a
              parameterised return type in `when(...).thenReturn(...)`.
            - When stubbing a method whose generic return is `Map<String, Foo>`
              or `List<Foo>`, use one of:
                * Explicit type witness: `Collections.<String, Foo>emptyMap()`
                * Constructor: `new HashMap<String, Foo>()` / `new ArrayList<Foo>()`
                * `Map.of()` / `List.of()` (Java 9+) with concrete keys/values
              EXAMPLE invalid (compile error: thenReturn cannot infer):
                when(mock.getCriteres()).thenReturn(Collections.emptyMap());
              EXAMPLE valid:
                when(mock.getCriteres()).thenReturn(Collections.<String, CritereDTO>emptyMap());

            # Verifying calls that receive in-body `new` instances (Bug AA)
            - When the target body creates an object via `new Type()` and passes
              it to a stubbed method, you CANNOT pass `new Type()` to `verify(...)`.
              Mockito compares arguments via `equals()` — most domain classes
              inherit `Object.equals()` which is IDENTITY-based, so the test's
              instance is NEVER equal to the production instance.
            - Use `any(Type.class)` matcher (or `ArgumentCaptor` to assert details).
              EXAMPLE invalid (passes compile, fails at runtime):
                verify(conversationModele).putModele(SUT.class, new ReferenceModele());
              EXAMPLE valid:
                verify(conversationModele).putModele(eq(SUT.class), any(ReferenceModele.class));
            - Note: when ANY matcher is used for one arg, ALL args must use
              matchers (Mockito rule) — wrap literals in `eq(...)`.

            # Building instances for stub return values (Bug DD)
            - When a stub returns a generic container like `List<ElementXxx>` or
              `Map<String, ElementYyy>`, you need instances of the element type
              to populate that container. If the element type is NOT listed
              under `# Mocks` (because it was evicted by the mock budget, or
              because the algorithm decided it is non-essential), DO NOT call
              `new ElementXxx()`. Most domain classes have NO no-args constructor
              — they require fields/IDs/labels that you do not have access to.
            - Use `Mockito.mock(Type.class)` AD-HOC inside the test body to
              fabricate an instance. This works for ANY class (even ones without
              a public no-args constructor) and bypasses the need to know the
              real constructor signature.
              EXAMPLE invalid (runtime failure: NoSuchMethodError or compile error):
                List<ElementsListeDeroulante> elements = new ArrayList<>();
                elements.add(new ElementsListeDeroulante());  // ← ctor requires (String, String)
              EXAMPLE valid:
                List<ElementsListeDeroulante> elements = new ArrayList<>();
                elements.add(mock(ElementsListeDeroulante.class));
                elements.add(mock(ElementsListeDeroulante.class));
            - This rule also applies to types mentioned in the truncation reasons
              under "drop mock XYZ" — those types are still mockable ad-hoc;
              they were only removed from the `@Mock` budget. Same recipe:
              `mock(XYZ.class)`.
            - If you just need a non-empty list/map to satisfy a `hasSize(N)`
              or `isNotEmpty()` assertion, prefer `mock(T.class)` over `new T()`
              for every element.

            # Wrapping rules for return types
            - Optional<T>          -> Optional.of(...) or Optional.empty()
            - CompletableFuture<T> -> CompletableFuture.completedFuture(...)
            - Mono<T> / Flux<T>    -> Mono.just(...) / Flux.just(...)

            # Untestable fields
            - If a field appears with strategy UNTESTABLE_AS_IS:
              - Generate a test method named [methodName]_TODO_untestable
              - Body: fail("Test cannot be completed without refactoring the class under test.");
              - This single fail() call is the only allowed body — no comment, no Javadoc

            {IF tree.tronque == true}
            # Truncated context
            - WARNING: extracted context is partial.
              Reasons: {raisonsTroncature}
            - Generate the test using the available context only.
            - Leave a single `fail("TODO truncated context");` in any test method whose
              coverage depends on the truncated portion.
            {END IF}

            # Compilation contract
            - The generated file MUST compile with Java 11 without modification
            - If a dependency is non-mockable (static, final, private constructor):
              skip the test method (do not generate it) — comments are forbidden
            - Goal: the test compiles and passes the nominal path
              Edge cases beyond CONTEXT may be left as a single `fail("TODO");` body
        """.trimIndent()

        private val DEFAULT_INSTRUCTION = """
            Generate the complete Java test class for method [targetMethodName]
            in class [TargetClassName]. Follow all constraints above.
            Start directly with "package ..." — no introduction, no explanation.
        """.trimIndent()
    }
}

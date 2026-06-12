package com.contextextractor.core.prompt.constraints

// Règles UNIVERSELLES Java / JUnit 5 / Mockito / AssertJ — applicables quel
// que soit le LLM cible. Cf Phase 5 (RAPPORT_CONTEXT §9.9).
//
// **Inclus** :
//   • Critical rules verification checklist (R1..R4 verrouillés en haut)
//   • Test class structure (extension, mocks, injection)
//   • Style strict (no comments, package-private)
//   • Anti-hallucination contract (no stubbing/inventing of class under test)
//   • Imports verbatim
//   • Required static imports Mockito/AssertJ
//   • Hard prohibitions (Reflection, Spring context loading, etc.)
//   • Initialization protocol / Coverage scope
//   • Wrapping rules (Optional, Mono/Flux, CompletableFuture)
//   • Untestable fields fallback
//   • Truncation block (placeholder vivant — substitution dans LayerCompositionStage)
//   • Compilation contract
//
// **Exclus** (déplacés vers [QwenTuningConstraints]) :
//   • Bug Y — Checked exceptions on test methods
//   • Bug Z — Typed Collections in stubs
//   • Bug AA — Verifying calls receiving in-body new instances
//   • Bug DD — Building instances for stub return values
//
// Ces 4 sections sont des patches observés sur Qwen 3.6 35B — d'autres LLM
// peuvent ne pas en avoir besoin (gaspillage de tokens, signal noyé).
object BaseConstraints {

    val TEXT: String = """
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
               If it is, declare it as `@Mock TypeName typeName;` (see Hard prohibitions).
        - [R5] When `# Class under test instantiation` shows `@InjectMocks` on the SUT
               field, you MUST replicate it VERBATIM on the SUT declaration in the test
               class. Add `import org.mockito.InjectMocks;` to the imports.
               NEVER instantiate the SUT manually via `sut = new SutClass(...)` in
               @BeforeEach — `@InjectMocks` + `@ExtendWith(MockitoExtension.class)`
               handles instantiation and field injection automatically. Manual
               construction breaks injection and causes NPE at runtime, or makes you
               invent setters that don't exist on the SUT.
               EXAMPLE invalid (NPE at runtime — mocks never injected):
                 private MyService myService;
                 @BeforeEach void init() { myService = new MyService(); }
               EXAMPLE valid:
                 @InjectMocks private MyService myService;

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
          EXCEPTION (V1.4.1) — when CONTEXT prescribes an explicit variable name
          (the `# Mocks` VERBATIM block, or `# Inherited fields requiring @Mock
          by name`), that name WINS over the type-derived rule. NEVER declare a
          SECOND mock of the same type under the type-derived name "to be safe":
          @InjectMocks disambiguates by field NAME, so your stubs would land on
          the duplicate mock that is never injected — NPE at runtime.
        - Method naming: camelCase only — NO underscore `_`, NO hyphen `-` in method names.
          Example: `redirigerVersDetailsDeltaVecNominal()`, not `rediriger_vers_details_nominal()`.

        # Anti-hallucination contract (strict)
        - NEVER stub a method on the class under test (via `doReturn(...).when(...).xxx()`)
          unless `xxx` appears explicitly in the CONTEXT section. Methods listed under
          "Methods to stub via spy" are the ONLY class-under-test methods you may stub.
        - NEVER invent helper methods on the class under test (no `cut.getXxx()` unless
          `getXxx` is in CONTEXT). If a field value is needed, mock the field's type instead.
        - V1.4 — methods listed under `# Internal sub-methods` annotated with
          `[protected]` or `[package-private]` CANNOT be called, stubbed, or
          verified from the test class (the test is generated in a fresh package
          by default). The compiler will refuse `doReturn/doNothing/verify(sut).xxx(...)`
          on a non-public inherited method. Treat them as INFORMATIONAL ONLY —
          use them to understand observable side effects to assert on the SUT state.
          INVALID:  `verify(sut).protectedMethod(eq(arg), anyInt());`
                    `doNothing().when(sut).protectedMethod(any(), anyInt());`
          VALID:    rely on observable mutations the SUT performs AFTER the call
                    (e.g. `verify(mockA).businessCall(...)`, `assertThat(sut.getField())...`).

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
        - For Mockito annotations (NON-static, regular imports):
            * `@Mock`         → `import org.mockito.Mock;`
            * `@InjectMocks`  → `import org.mockito.InjectMocks;`
            * `@Captor`       → `import org.mockito.Captor;`
            * `@ExtendWith(MockitoExtension.class)` → `import org.junit.jupiter.api.extension.ExtendWith;`
              and `import org.mockito.junit.jupiter.MockitoExtension;`

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
        - **CRITICAL** — This protocol applies AFTER `@InjectMocks` has constructed
          the SUT and injected the `@Mock` fields. It covers ONLY the residual
          wiring (setters, post-construct calls) that cannot be done via field
          injection. Do NOT use this protocol as an excuse to replace `@InjectMocks`
          with a manual `new SutClass(...)` constructor call (see [R5]).
        - When a field is listed here with strategy `SETTER` and the SUT has a
          public setter, call the setter on the SUT (which was created by
          `@InjectMocks`). Do NOT invent setters that aren't shown in the protocol.

        # Coverage scope
        - Cover the nominal path of [targetMethodName]
        - Cover each conditional branch listed in CONTEXT
        - Cover each exception listed in CONTEXT

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
}

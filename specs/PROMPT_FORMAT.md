# Prompt Format — ContextCrawler

> This file defines the meta-prompt structure wrapping the extracted context.
> It is **LLM-agnostic**: the same structure is used for any provider; only
> the `[SYSTEM]` template is tuned per LLM.
>
> The `[CONTEXT]` section content is defined in **STRATEGIE.md section 6**
> and is rendered by `ContextRenderStage` from the `ContextTree`.

---

## Target LLM

The prompt structure below is provider-agnostic. Each provider can have its
own optimized `[SYSTEM]` template loaded from `templates/system/`.

| Provider          | Default model              | System template          |
|-------------------|----------------------------|--------------------------|
| Anthropic (V1)    | `claude-sonnet-4-6`        | `system/claude.md`       |
| Claude Code CLI   | (uses local subscription)  | `system/claude.md`       |
| Qwen via wrapper  | `qwen-3.5`                 | `system/qwen.md`         |
| OpenAI (future)   | `gpt-4`                    | `system/openai.md`       |
| Ollama (future)   | configurable               | `system/ollama.md`       |

The active provider is selected in **Settings → ContextCrawler → LLM Backend**.
The active system template is loaded based on the selected provider.

**Generated test code language**: Comments in French, code in English.
**Imposed stack**: Java 11 + JUnit 5 + Mockito + AssertJ.

---

## Full Prompt Structure

```
[SYSTEM]
[CONTEXT]            ← rendered from ContextTree — see STRATEGIE.md §6
[USER_ENRICHMENT]    ← optional, provided by the user before sending
[CONSTRAINTS]
[INSTRUCTION]
```

The full prompt is assembled by `LayerCompositionStage` after all stages
have populated their respective layers in the `PromptContext`.

---

### Section 1 — SYSTEM

Defines the LLM role and non-negotiable output rules.
Loaded from `templates/system/<provider>.md` based on active provider.

**Default content** (provider-agnostic baseline):

```
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
```

Per-provider templates may add tuning (e.g., Qwen-specific wording, Claude
XML hints, etc.) but must preserve the same output contract.

**Bug L — pourquoi cette structure ?**
Les 4 règles [R1]..[R4] correspondent aux violations les plus fréquentes
observées sur du vrai code production (`SupervisionDeltaVecControleur`).
Remontées en haut du SYSTEM avec marqueurs visuels (`═══`, `[Rn]`), elles
sont relues par le LLM AVANT le bloc CONTEXT. CONSTRAINTS les répète sous
forme de checklist de vérification (verbes d'action : « Scan », « Search »)
pour pousser un audit avant l'output.

---

### Section 2 — CONTEXT

Rendered by `ContextRenderStage` from the `ContextTree`.
**Format fully defined in STRATEGIE.md section 6.**
This file does NOT duplicate that format — it only describes how the
rendered context is wrapped within the full prompt.

```
=== CONTEXT ===
[rendered ContextTree — see STRATEGIE.md §6]
```

---

### Section 3 — USER_ENRICHMENT

Optional free-text zone the user can fill in the dialog/Tool Window
**before** sending the prompt to the LLM.

Displayed in the UI as:

```
Additional instructions (optional):
┌─────────────────────────────────────────────────┐
│ e.g. "Use BDDMockito style"                     │
│      "The method throws if id is null"          │
│      "Don't mock UserRepository, use H2"        │
└─────────────────────────────────────────────────┘
```

If the user fills this field, it is injected into the prompt as:

```
=== ADDITIONAL INSTRUCTIONS ===
[user free text verbatim]
```

If the field is empty, this section is omitted entirely from the prompt.

---

### Section 4 — CONSTRAINTS

Explicit generation rules injected after context and user enrichment.
**Static template, in English.** Includes the no-reflection rule and
all guardrails that previously lived in STRATEGIE.md §6.

```
=== CONSTRAINTS ===

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

# Wrapping rules for return types
- Optional<T>          → Optional.of(...) or Optional.empty()
- CompletableFuture<T> → CompletableFuture.completedFuture(...)
- Mono<T> / Flux<T>    → Mono.just(...) / Flux.just(...)

# Untestable fields
- If a field appears with strategy UNTESTABLE_AS_IS:
  - Generate a test method named [methodName]_TODO_untestable
  - Body: fail("Test cannot be completed without refactoring the class under test.");
  - This single fail() call is the only allowed body — no comment, no Javadoc

# Truncated context
{IF tree.tronque == true}
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
```

---

### Section 5 — INSTRUCTION

Short and direct final instruction.
**Static template, in English.**

```
=== INSTRUCTION ===
Generate the complete Java test class for method [targetMethodName]
in class [TargetClassName]. Follow all constraints above.
Start directly with "package ..." — no introduction, no explanation.
```

---

## Special case — UNTESTABLE_AS_IS at SUT level

If the strategy returns `UNTESTABLE_AS_IS` for the **whole SUT**
(diagnosticTestabilite.testable == false), do NOT send any prompt to the LLM.
Display directly in the dialog / Tool Window:

```
⚠️ Cette méthode ne peut pas être testée automatiquement.

Champs bloquants : [list of blocking field names]

Raisons :
  [list from diagnosticTestabilite.raisons]

Pistes de refactoring :
  [list from diagnosticTestabilite.pistesRefacto]
```

Note: this differs from a single field marked UNTESTABLE_AS_IS — in that case,
the prompt IS sent and the LLM generates a test with a TODO method for that
specific field (see CONSTRAINTS § Untestable fields).

---

## Special case — Truncated context

If `ContextTree.tronque == true`, the prompt is still sent but with the
truncation warning injected into [CONSTRAINTS] (see template above).

The user is also notified in the UI:

```
ℹ️ Contexte tronqué : [raisonsTroncature]
   Le test généré sera basé sur le contexte partiel.
   Vérifie manuellement les zones non explorées.
```

---

## Implementation notes for PromptBuilder

### Stage responsibilities

| Stage                      | Reads from         | Writes to                  |
|----------------------------|--------------------|----------------------------|
| `ContextRenderStage`       | `ContextTree`      | layers[CONTEXT]            |
| `MetaPromptComposeStage`   | each layer         | resolves `{{>partials}}`   |
| `LayerCompositionStage`    | layers + templates | finalText                  |
| `CleanupStage`             | finalText          | finalText (cleaned)        |

### Template organization

```
templates/
├── system/
│   ├── claude.md          ← Anthropic-specific tuning
│   ├── qwen.md            ← Qwen-specific tuning
│   ├── openai.md          ← future
│   └── default.md         ← provider-agnostic fallback
├── constraints/
│   └── default.md         ← the CONSTRAINTS template above
├── instruction/
│   └── default.md         ← the INSTRUCTION template above
└── partials/              ← reusable fragments (see ARCHITECTURE.md §7)
```

### Layer assembly rules

- `[SYSTEM]`, `[CONSTRAINTS]`, `[INSTRUCTION]` are static templates with
  `[placeholders]` replaced at runtime by `LayerCompositionStage`
- `[CONTEXT]` is rendered by `ContextRenderStage` per STRATEGIE.md §6
- `[USER_ENRICHMENT]` is injected only if non-blank — never inject empty section
- Sections are assembled in order by `LayerCompositionStage`
- Final cleanup (remove double blank lines, trim) is done by `CleanupStage`
- The `PromptBuilder` does NOT know about the LLM — it only produces a `String`

### Configuration hooks

The user can override any template via `.contextextractor.yml`:

```yaml
prompt:
  templates:
    system: my-templates/system-custom.md
    constraints: my-templates/constraints-strict.md
  layers:
    instruction: |
      Generate ONLY the @Test methods, not the class declaration.
```

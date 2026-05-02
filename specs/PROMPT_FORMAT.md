# Prompt Format — ContextCrawler

> This file defines the meta-prompt structure wrapping the extracted context.
> The full prompt is sent to the target LLM (Qwen 3.5).
> The [CONTEXT] section content is defined in STRATEGIE.md section 6.
> Generated test code comments are in French.

---

## Target LLM

- **Model**: Qwen 3.5
- **Access**: Claude Code API wrapper OR cloud API (configurable in Settings)
- **Generated code comments language**: French
- **Imposed stack**: Java 11 + JUnit 5 + Mockito

---

## Full Prompt Structure

```
[SYSTEM]
[CONTEXT]          ← rendered from ContextTree — see STRATEGIE.md section 6
[USER_ENRICHMENT]  ← optional, provided by the user before sending
[CONSTRAINTS]
[INSTRUCTION]
```

---

### Section 1 — SYSTEM

Defines the LLM role and non-negotiable output rules.
**Must be in English** — optimized for Qwen 3.5.

```
You are an expert Java unit test writer.
Output ONLY raw valid Java code. No explanation, no markdown, no code fences.
Just the raw content of the .java file, starting with "package ...".
Stack: Java 11, JUnit 5 (org.junit.jupiter.api), Mockito 4.x.
All comments inside the generated code must be written in French.
```

---

### Section 2 — CONTEXT

Placeholder — content is fully defined in **STRATEGIE.md section 6**.
Rendered from the `ContextTree` produced by the active strategy.

```
=== CONTEXT ===
[rendered ContextTree — see STRATEGIE.md section 6]
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
**Must be in English.**

```
=== CONSTRAINTS ===
- Generate ONE single Java 11 test class
- Class name: [TargetClassName]Test
- Package: [same package as target class]
- Use @ExtendWith(MockitoExtension.class)
- Mock all dependencies listed in CONTEXT with @Mock
- Inject via @InjectMocks or constructor based on detected pattern
- Test the nominal path of [targetMethodName] only
- The test must compile with Java 11 without any modification
- If a dependency is non-mockable (static, final, private): add a comment
  // ATTENTION: [reason] — manual mock required
- Goal: the test compiles and passes; the developer completes coverage later
```

---

### Section 5 — INSTRUCTION

Short and direct final instruction.
**Must be in English.**

```
=== INSTRUCTION ===
Generate the complete Java test class for method [targetMethodName]
in class [TargetClassName]. Follow all constraints above.
Start directly with "package ..." — no introduction, no explanation.
```

---

## Special case — UNTESTABLE_AS_IS

If the strategy returns `UNTESTABLE_AS_IS`, do NOT send any prompt to the LLM.
Display directly in the dialog / Tool Window:

```
⚠️ Cette méthode ne peut pas être testée automatiquement.

Raison : [raison détectée par la stratégie]
  ex: "Dépendance statique non-mockable : DatabaseConnection.getInstance()"
  ex: "Constructeur privé sans factory method"

Recommandation : [suggestion de refactoring]
```

---

## Implementation notes for PromptBuilder

- `SYSTEM`, `CONSTRAINTS` and `INSTRUCTION` are static templates
  with `[placeholders]` replaced at runtime
- `CONTEXT` is rendered by `ContextRenderStage` from the `ContextTree`
- `USER_ENRICHMENT` is injected only if non-blank — never inject an empty section
- Sections are assembled in order by `LayerCompositionStage`
- Final cleanup (remove double blank lines, trim) is done by `CleanupStage`
- The `PromptBuilder` does NOT know about the LLM — it only produces a `String`
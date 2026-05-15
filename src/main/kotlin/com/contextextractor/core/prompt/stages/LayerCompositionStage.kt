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
        private val DEFAULT_SYSTEM = """
            You are an expert Java unit test writer.
            Output ONLY raw valid Java code. No explanation, no markdown, no code fences.
            Just the raw content of the .java file, starting with "package ...".
            Stack: Java 11, JUnit 5 (org.junit.jupiter.api), Mockito 4.x, AssertJ.
            All comments inside the generated code must be written in French.
        """.trimIndent()

        // PROMPT_FORMAT.md §"Section 4 — CONSTRAINTS" — texte verbatim,
        // y compris le bloc `{IF tree.tronque == true}` géré par
        // expandTruncationBlock().
        private val DEFAULT_CONSTRAINTS = """
            # Test class
            - Generate ONE single Java 11 test class
            - Class name: [TargetClassName]Test
            - Package: [same package as target class]
            - Use @ExtendWith(MockitoExtension.class)
            - Mocks via @Mock; SUT via @InjectMocks (unless explicit construction is required)
            - Assertions: AssertJ ONLY (assertThat...)
            - Stubbing: Mockito ONLY (when/thenReturn/thenThrow)

            # Initialization protocol
            - The init protocol described in CONTEXT is PRESCRIPTIVE
            - Apply it in @BeforeEach in the listed order
            - Do NOT skip steps even if they look redundant

            # Coverage scope
            - Cover the nominal path of [targetMethodName]
            - Cover each conditional branch listed in CONTEXT
            - Cover each exception listed in CONTEXT

            # Hard prohibitions
            - NO ReflectionTestUtils, NO setAccessible, NO Field manipulation
            - NO @SpringBootTest, NO @WebMvcTest, NO @DataJpaTest, NO context loading
            - NO instantiation of types listed under "Mocks" in CONTEXT
            - NO invocation of method signatures NOT listed in CONTEXT
            - NO real network/database/filesystem access

            # Wrapping rules for return types
            - Optional<T>          -> Optional.of(...) or Optional.empty()
            - CompletableFuture<T> -> CompletableFuture.completedFuture(...)
            - Mono<T> / Flux<T>    -> Mono.just(...) / Flux.just(...)

            # Untestable fields
            - If a field appears with strategy UNTESTABLE_AS_IS:
              - Generate a test method named [methodName]_TODO_untestable
              - Body: fail("Test impossible à compléter sans refactor du SUT.");
              - Add a Javadoc comment quoting the reason and refactoring hints from CONTEXT

            {IF tree.tronque == true}
            # Truncated context
            - WARNING: extracted context is partial.
              Reasons: {raisonsTroncature}
            - Generate the test using the available context only.
            - Add a TODO comment listing what was truncated.
            {END IF}

            # Compilation contract
            - The generated file MUST compile with Java 11 without modification
            - If a dependency is non-mockable (static, final, private constructor):
              add a comment: // ATTENTION: [reason] - manual mock required
            - Goal: the test compiles and passes the nominal path
              Edge cases beyond CONTEXT may be left as TODO comments
        """.trimIndent()

        private val DEFAULT_INSTRUCTION = """
            Generate the complete Java test class for method [targetMethodName]
            in class [TargetClassName]. Follow all constraints above.
            Start directly with "package ..." — no introduction, no explanation.
        """.trimIndent()
    }
}

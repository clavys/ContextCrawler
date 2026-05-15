package com.contextextractor.core.prompt.stages

import com.contextextractor.core.model.ContextNode
import com.contextextractor.core.model.MetaKeys
import com.contextextractor.core.model.NodeKind
import com.contextextractor.core.prompt.PromptContext
import com.contextextractor.core.prompt.PromptStage
import com.contextextractor.core.prompt.meta.LayerKind

// Premier stage du pipeline de prompt — STRATEGIE.md §6.
//
// Lit le `ContextTree` (déjà construit par RecursiveDeepStrategy +
// ContextResultTreeMapper) et produit le contenu Markdown qui sera injecté
// dans la layer `[CONTEXT]` du prompt final. Ne touche pas aux autres layers
// (SYSTEM, CONSTRAINTS, INSTRUCTION, etc.) : c'est le rôle des stages suivants.
//
// **Format strict** : chaque section Markdown suit la structure §6 ligne par
// ligne. Toute déviation ferait dévier les assertions binaires de
// EXPECTED_PROMPTS.md (test-project) — donc tout changement de template
// passe par §6 d'abord.
//
// **Cas UNTESTABLE_AS_IS** : §6 ligne 1108-1116 spécifie un bloc TODO
// explicite (commentaire `// STOP`, raison, pistes, méthode test marquée
// `_TODO_untestable`). Ce cas est verrouillé par un test dédié — un champ
// non testable doit produire ce bloc, jamais une absence silencieuse.
class ContextRenderStage : PromptStage {

    override val id: String = "context-render"

    override fun apply(ctx: PromptContext): PromptContext {
        val sb = StringBuilder()
        val tree = ctx.tree
        val root = tree.root

        renderClassHeader(sb, root)
        renderTargetMethodSection(sb, root)
        renderInstantiationPlaceholder(sb, root)
        renderInitProtocol(sb, tree.ofKind(NodeKind.FIELD))
        renderMocks(sb, tree.ofKind(NodeKind.MOCK))
        renderInternalMethods(sb, tree.ofKind(NodeKind.INTERNAL_METHOD))
        renderDataStructures(sb, tree.ofKind(NodeKind.DATA_STRUCTURE))
        renderStaticCalls(sb, tree.ofKind(NodeKind.CUSTOM))

        ctx.layers[LayerKind.CONTEXT] = sb.toString().trimEnd() + "\n"
        return ctx
    }

    // ── # Classe sous test ───────────────────────────────────────────────────

    private fun renderClassHeader(sb: StringBuilder, root: ContextNode) {
        // root.title = "${classFqn}#${canonical}" — on prend la classe avant le #.
        val classFqn = root.title.substringBefore('#')
        sb.appendLine("# Classe sous test")
        sb.appendLine(classFqn)
        // Hiérarchie cherchée par metadata du nœud HIERARCHY (un seul, enfant direct).
        val hierarchy = root.children.firstOrNull { it.kind == NodeKind.HIERARCHY }
        if (hierarchy != null) {
            val levels = hierarchy.metadata[MetaKeys.HIERARCHY_LEVELS].orEmpty()
            if (levels.isNotEmpty()) sb.appendLine("Hiérarchie : $levels")
        }
        sb.appendLine()
    }

    // ── # Méthode cible ──────────────────────────────────────────────────────

    private fun renderTargetMethodSection(sb: StringBuilder, root: ContextNode) {
        sb.appendLine("# Méthode cible")
        val canonical = root.metadata[MetaKeys.METHOD_CANONICAL].orEmpty()
        val returnType = root.metadata[MetaKeys.METHOD_RETURN_TYPE].orEmpty()
        sb.appendLine("$returnType $canonical")
        // STRATEGIE.md §6 — bloc « Code source » après la signature, frontière
        // ouverte §3.1 (SUT_BOOTSTRAP). Conditionnel : si le port n'a pas pu
        // lire le corps (port stub / cas dégradé §8bis), on ne rend rien plutôt
        // que d'émettre un bloc ```java vide.
        val body = root.metadata[MetaKeys.METHOD_BODY].orEmpty()
        if (body.isNotEmpty()) {
            sb.appendLine("Code source :")
            sb.appendLine("```java")
            sb.appendLine(body.trim())
            sb.appendLine("```")
        }
        // Champs étendus (throws / exceptions / branches / sources non-déterministes)
        // viendront quand l'introspector les exposera (cf RecursiveDeepStrategy
        // BLOC 2 note : « extension PSI dédiée requise »). Pas d'output vide.
        sb.appendLine()
    }

    // ── # Instanciation du SUT ───────────────────────────────────────────────

    private fun renderInstantiationPlaceholder(sb: StringBuilder, root: ContextNode) {
        // Le détail (ctor sélectionné, super args) n'est pas encore porté dans
        // les nœuds — il vit dans ContextResult.instantiationPlan, non mappé en
        // V1 (pas de NodeKind.CONSTRUCTOR construit par le mapper). Sortie
        // minimale pour respecter la structure §6.
        sb.appendLine("# Instanciation du SUT")
        sb.appendLine("new ${root.title.substringBefore('#')}()")
        sb.appendLine()
    }

    // ── # Protocole d'initialisation des champs ──────────────────────────────

    private fun renderInitProtocol(sb: StringBuilder, fields: List<ContextNode>) {
        // §6 : « À EXÉCUTER DANS L'ORDRE ci-dessous ». L'ordre vient de
        // `ContextTree.ofKind(FIELD)` qui reflète l'ordre d'enfants du root —
        // lui-même produit par le mapper en suivant `ContextResult.initOrder`
        // (tri §4.7).
        //
        // Filtre §6 : on n'affiche QUE les champs dont la stratégie n'est pas
        // CONSTRUCTOR/IMPLICIT/MOCKITO_INJECT_MOCKS (gérés implicitement).
        // On garde l'en-tête s'il reste au moins un champ visible.
        val visible = fields.filter { it.metadata[MetaKeys.INIT_STRATEGY_KIND] !in IMPLICIT_KINDS }
        if (visible.isEmpty()) return

        sb.appendLine("# Protocole d'initialisation des champs")
        sb.appendLine("À EXÉCUTER DANS L'ORDRE ci-dessous, dans @BeforeEach.")
        sb.appendLine()

        for (field in visible) {
            renderFieldInitBlock(sb, field)
        }
    }

    private fun renderFieldInitBlock(sb: StringBuilder, field: ContextNode) {
        val type = field.metadata[MetaKeys.FIELD_TYPE_FQN].orEmpty()
        val kind = field.metadata[MetaKeys.INIT_STRATEGY_KIND].orEmpty()
        sb.appendLine("## Champ `${field.title}` : $type")
        sb.appendLine("Stratégie : $kind")

        when (kind) {
            "SETTER" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                sb.appendLine("sut.$method(mockOf$type);")
            }
            "CALL_POST_CONSTRUCT" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                sb.appendLine("sut.$method();   // @PostConstruct")
            }
            "CALL_PUBLIC" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                sb.appendLine("sut.$method();")
            }
            "CALL_PUBLIC_WITH_STUBS" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                val stubs = field.metadata[MetaKeys.INIT_STUBS].orEmpty()
                sb.appendLine("// Stubber d'abord :")
                stubs.split(", ").forEach {
                    sb.appendLine("when($it).thenReturn(...);")
                }
                sb.appendLine("// Puis :")
                sb.appendLine("sut.$method();")
            }
            "CALL_PUBLIC_WITH_ARGS" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                val args = field.metadata[MetaKeys.INIT_ARGS].orEmpty()
                sb.appendLine("sut.$method($args);  // construire selon section \"Structures\"")
            }
            "CALL_PUBLIC_TRANSITIVE" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                val chain = field.metadata[MetaKeys.INIT_CALL_CHAIN].orEmpty()
                val effects = field.metadata[MetaKeys.INIT_SIDE_EFFECTS].orEmpty()
                val stubs = field.metadata[MetaKeys.INIT_STUBS].orEmpty()
                val args = field.metadata[MetaKeys.INIT_ARGS].orEmpty()
                sb.appendLine("// Cette méthode publique assigne `${field.title}` via la chaîne :")
                sb.appendLine("//    $chain")
                if (effects.isNotEmpty()) sb.appendLine("// Effets de bord à connaître : $effects")
                if (stubs.isNotEmpty()) {
                    sb.appendLine("// Stubs requis avant l'appel :")
                    stubs.split(", ").forEach { sb.appendLine("// - $it") }
                }
                sb.appendLine("sut.$method($args);")
            }
            "CALL_SAME_PACKAGE" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                sb.appendLine("// Place le test dans le même package que le SUT")
                sb.appendLine("sut.$method();")
            }
            "UNTESTABLE_AS_IS" -> renderUntestableBlock(sb, field)
        }
        sb.appendLine()
    }

    // §6 lignes 1108-1116 — bloc TODO obligatoire pour UNTESTABLE_AS_IS.
    // Verrouillé par ContextRenderStageTest : un champ non testable DOIT
    // produire ce bloc, jamais être omis silencieusement.
    private fun renderUntestableBlock(sb: StringBuilder, field: ContextNode) {
        val reason = field.metadata[MetaKeys.INIT_REASON].orEmpty()
        val hints = field.metadata[MetaKeys.INIT_REFACTOR_HINTS].orEmpty()
        sb.appendLine("// STOP : ce champ ne peut pas être initialisé sans refactor.")
        sb.appendLine("// Raison : $reason")
        if (hints.isNotEmpty()) {
            sb.appendLine("// Pistes :")
            hints.split('\n').forEach { sb.appendLine("//   - $it") }
        }
        sb.appendLine("// Génère un test marqué TODO :")
        sb.appendLine("@Test")
        sb.appendLine("void ${field.title}_TODO_untestable() {")
        sb.appendLine("    fail(\"Test impossible à compléter sans refactor du SUT. Voir docstring.\");")
        sb.appendLine("}")
    }

    // ── # Mocks ──────────────────────────────────────────────────────────────

    private fun renderMocks(sb: StringBuilder, mocks: List<ContextNode>) {
        if (mocks.isEmpty()) return
        sb.appendLine("# Mocks (annoter @Mock {typeDeclare})")
        for (mock in mocks) {
            val declared = mock.metadata[MetaKeys.MOCK_DECLARED_TYPE].orEmpty()
            val concrete = mock.metadata[MetaKeys.MOCK_CONCRETE_TYPE].orEmpty()
            sb.appendLine("## $declared  (concret : $concrete)")
            val sigs = mock.metadata[MetaKeys.MOCK_SIGNATURES].orEmpty()
            if (sigs.isNotEmpty()) {
                sb.appendLine("Méthodes à stubber :")
                sigs.split('\n').forEach { sb.appendLine("- $it") }
            }
        }
        sb.appendLine()
    }

    // ── # Sous-méthodes internes ─────────────────────────────────────────────

    private fun renderInternalMethods(sb: StringBuilder, internals: List<ContextNode>) {
        if (internals.isEmpty()) return
        sb.appendLine("# Sous-méthodes internes (information seulement, ne pas mocker)")
        for (m in internals) {
            sb.appendLine("## ${m.title}")
            // STRATEGIE.md §6 + §3.2 — corps source des méthodes intra-SUT.
            // Frontière fermée pour MOCK_EXTERNAL (§3.3 « STOP ») mais les internes
            // sont par construction intra-SUT (cf RecursiveDeepStrategy §3.2 +
            // enrichInternalLogicsWithDownstream qui ne synthétise que pour la
            // hiérarchie SUT). Conditionnel pour les cas dégradés.
            val body = m.metadata[MetaKeys.INTERNAL_METHOD_BODY].orEmpty()
            if (body.isNotEmpty()) {
                sb.appendLine("Code source :")
                sb.appendLine("```java")
                sb.appendLine(body.trim())
                sb.appendLine("```")
            }
            val summaries = m.metadata["internalCallSummaries"].orEmpty()
            if (summaries.isNotEmpty()) {
                sb.appendLine("- appels-clés :")
                summaries.split('\n').forEach { sb.appendLine("  - $it") }
            }
        }
        sb.appendLine()
    }

    // ── # Structures de données ──────────────────────────────────────────────

    private fun renderDataStructures(sb: StringBuilder, dtos: List<ContextNode>) {
        if (dtos.isEmpty()) return
        sb.appendLine("# Structures de données à construire")
        for (dto in dtos) {
            val pattern = dto.metadata[MetaKeys.DTO_PATTERN].orEmpty()
            sb.appendLine("## ${dto.title} [$pattern]")
            val fields = dto.metadata[MetaKeys.DTO_FIELDS].orEmpty()
            if (fields.isNotEmpty()) sb.appendLine("Champs : $fields")
        }
        sb.appendLine()
    }

    // ── # Appels statiques ───────────────────────────────────────────────────

    private fun renderStaticCalls(sb: StringBuilder, statics: List<ContextNode>) {
        // Filtre uniquement les nœuds CUSTOM portant un staticClass — d'autres
        // CUSTOM pourraient apparaître à terme (extension par stratégies tierces).
        val staticOnly = statics.filter { it.metadata.containsKey("staticClass") }
        if (staticOnly.isEmpty()) return
        sb.appendLine("# Appels statiques utilisateur détectés")
        sb.appendLine("À mocker via Mockito.mockStatic({classe}.class) :")
        for (sc in staticOnly) {
            sb.appendLine("- ${sc.title}")
        }
        sb.appendLine()
    }

    companion object {
        // Stratégies « implicites » §6 ligne 1069 — non rendues dans le bloc
        // « # Protocole d'initialisation des champs » car gérées au moment de
        // `new SUT(...)` (BLOC 4) ou par Mockito.@InjectMocks.
        private val IMPLICIT_KINDS = setOf(
            "CONSTRUCTOR", "IMPLICIT", "IMPLICIT_VIA_CONSTRUCTOR", "MOCKITO_INJECT_MOCKS"
        )
    }
}

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
        // Bug R — nom de variable dérivé du nom court du SUT (camelCase) pour
        // remplacer l'ancien `sut` dans les patterns suggérés. Cohérent avec
        // la règle « variable de type TypeName MUST be named typeName ».
        val sutClassFqn = root.title.substringBefore('#')
        val sutVarName = sutClassFqn.substringAfterLast('.')
            .replaceFirstChar { it.lowercase() }

        renderClassHeader(sb, root)
        renderTargetMethodSection(sb, root)
        // Bug V — l'instantiation doit refléter le mode d'injection. Si au moins
        // un champ est MOCKITO_INJECT_MOCKS, le LLM doit utiliser @InjectMocks
        // (pas `new SUT()` + setters inventés). Cas concret : prompt produit
        // `controleur = spy(new SUT()); controleur.setSvc(...)` — les setters
        // n'existent pas, ne compile pas. La fix : annoncer @InjectMocks dès
        // la section instantiation, et le pattern spy s'enchaîne dessus.
        val fields = tree.ofKind(NodeKind.FIELD)
        val hasInjectMocksFields = fields.any {
            it.metadata[MetaKeys.INIT_STRATEGY_KIND] == "MOCKITO_INJECT_MOCKS"
        }
        renderInstantiationSection(sb, root, hasInjectMocksFields, sutVarName)
        renderInitProtocol(sb, fields, sutVarName)
        renderMocks(sb, tree.ofKind(NodeKind.MOCK))
        // Défaut #1 — §3.2bis. On sépare les internes normales des frontières
        // STUB_VIA_SPY et on rend chaque catégorie dans sa section dédiée.
        val internals = tree.ofKind(NodeKind.INTERNAL_METHOD)
        val (stubViaSpy, regularInternals) = internals.partition {
            it.metadata[MetaKeys.STUB_VIA_SPY] == "true"
        }
        renderInternalMethods(sb, regularInternals)
        renderStubViaSpyMethods(sb, stubViaSpy, sutVarName)
        renderDataStructures(sb, tree.ofKind(NodeKind.DATA_STRUCTURE))
        renderStaticCalls(sb, tree.ofKind(NodeKind.CUSTOM))

        ctx.layers[LayerKind.CONTEXT] = sb.toString().trimEnd() + "\n"
        return ctx
    }

    // ── # Class under test ───────────────────────────────────────────────────

    private fun renderClassHeader(sb: StringBuilder, root: ContextNode) {
        // root.title = "${classFqn}#${canonical}" — on prend la classe avant le #.
        val classFqn = root.title.substringBefore('#')
        sb.appendLine("# Class under test")
        sb.appendLine(classFqn)
        // Hiérarchie cherchée par metadata du nœud HIERARCHY (un seul, enfant direct).
        val hierarchy = root.children.firstOrNull { it.kind == NodeKind.HIERARCHY }
        if (hierarchy != null) {
            val levels = hierarchy.metadata[MetaKeys.HIERARCHY_LEVELS].orEmpty()
            if (levels.isNotEmpty()) sb.appendLine("Hierarchy: $levels")
        }
        sb.appendLine()
    }

    // ── # Target method ──────────────────────────────────────────────────────

    private fun renderTargetMethodSection(sb: StringBuilder, root: ContextNode) {
        sb.appendLine("# Target method")
        val canonical = root.metadata[MetaKeys.METHOD_CANONICAL].orEmpty()
        val returnType = root.metadata[MetaKeys.METHOD_RETURN_TYPE].orEmpty()
        sb.appendLine("$returnType $canonical")
        // STRATEGIE.md §6 — bloc « Source code » après la signature, frontière
        // ouverte §3.1 (SUT_BOOTSTRAP). Conditionnel : si le port n'a pas pu
        // lire le corps (port stub / cas dégradé §8bis), on ne rend rien plutôt
        // que d'émettre un bloc ```java vide.
        val body = root.metadata[MetaKeys.METHOD_BODY].orEmpty()
        if (body.isNotEmpty()) {
            sb.appendLine("Source code:")
            sb.appendLine("```java")
            sb.appendLine(body.trim())
            sb.appendLine("```")
        }
        // §3.1 BLOC 2 / §6 — éléments structurels du corps. Chaque puce n'est
        // émise que si la metadata existe (le mapper ne la pose pas pour une
        // liste vide) ⇒ aucune puce parasite. Le corps en clair ci-dessus reste
        // la source primaire ; ces puces sont des indices ciblés pour le LLM.
        renderBullet(sb, "throws (declared)", root.metadata[MetaKeys.METHOD_THROWS_DECLARED])
        renderBullet(sb, "exceptions thrown in body", root.metadata[MetaKeys.METHOD_THROWN_BODY])
        renderBullet(sb, "exceptions caught", root.metadata[MetaKeys.METHOD_CAUGHT])
        renderBullet(sb, "non-deterministic sources", root.metadata[MetaKeys.METHOD_NON_DETERMINISTIC])
        renderBullet(sb, "expected lambdas", root.metadata[MetaKeys.METHOD_LAMBDAS])
        // Branches : une par ligne — la condition peut contenir des virgules.
        val branches = root.metadata[MetaKeys.METHOD_BRANCHES].orEmpty()
        if (branches.isNotEmpty()) {
            sb.appendLine("- branches:")
            branches.split('\n').forEach { sb.appendLine("  - $it") }
        }
        sb.appendLine()
    }

    // Puce `- {label}: {value}` — émise seulement si `value` est non vide.
    private fun renderBullet(sb: StringBuilder, label: String, value: String?) {
        if (!value.isNullOrEmpty()) sb.appendLine("- $label: $value")
    }

    // ── # Class under test instantiation ─────────────────────────────────────

    private fun renderInstantiationSection(
        sb: StringBuilder,
        root: ContextNode,
        hasInjectMocksFields: Boolean,
        sutVarName: String
    ) {
        sb.appendLine("# Class under test instantiation")
        val classFqn = root.title.substringBefore('#')
        // §3.6 — paramètres réels du constructeur sélectionné (BLOC 4).
        val ctorParams = root.metadata[MetaKeys.SUT_CTOR_PARAMS].orEmpty()
        val superArgs = root.metadata[MetaKeys.SUT_SUPER_ARGS].orEmpty()
        if (hasInjectMocksFields) {
            // Bug V — pattern Mockito standard. @InjectMocks construit la SUT
            // et injecte les @Mock par reflection sur les champs @Autowired —
            // PAS de setters à inventer. Si STUB_VIA_SPY est aussi nécessaire,
            // la section dédiée renvoie `$sutVarName = spy($sutVarName);` qui
            // wrap l'instance déjà créée.
            sb.appendLine("```java")
            sb.appendLine("@InjectMocks")
            sb.appendLine("private $classFqn $sutVarName;")
            sb.appendLine("```")
            if (ctorParams.isNotEmpty()) {
                sb.appendLine("Constructor signature (Mockito picks it automatically): " +
                    "`new $classFqn($ctorParams)`")
            }
        } else {
            // Pas de champ @Autowired → instanciation explicite légitime.
            sb.appendLine("new $classFqn($ctorParams)")
        }
        if (superArgs.isNotEmpty()) sb.appendLine("super($superArgs)")
        sb.appendLine()
    }

    // ── # Field initialization protocol ──────────────────────────────────────

    private fun renderInitProtocol(
        sb: StringBuilder,
        fields: List<ContextNode>,
        sutVarName: String
    ) {
        // §6 : « EXECUTE IN ORDER below ». L'ordre vient de
        // `ContextTree.ofKind(FIELD)` qui reflète l'ordre d'enfants du root —
        // lui-même produit par le mapper en suivant `ContextResult.initOrder`
        // (tri §4.7).
        //
        // Filtre §6 : on n'affiche QUE les champs dont la stratégie n'est pas
        // CONSTRUCTOR/IMPLICIT/MOCKITO_INJECT_MOCKS (gérés implicitement).
        // On garde l'en-tête s'il reste au moins un champ visible.
        val visible = fields.filter { it.metadata[MetaKeys.INIT_STRATEGY_KIND] !in IMPLICIT_KINDS }
        if (visible.isEmpty()) return

        sb.appendLine("# Field initialization protocol")
        sb.appendLine("EXECUTE IN ORDER below, inside @BeforeEach.")
        sb.appendLine()

        for (field in visible) {
            renderFieldInitBlock(sb, field, sutVarName)
        }
    }

    private fun renderFieldInitBlock(
        sb: StringBuilder,
        field: ContextNode,
        sutVarName: String
    ) {
        val type = field.metadata[MetaKeys.FIELD_TYPE_FQN].orEmpty()
        val kind = field.metadata[MetaKeys.INIT_STRATEGY_KIND].orEmpty()
        sb.appendLine("## Field `${field.title}`: $type")
        sb.appendLine("Strategy: $kind")

        when (kind) {
            "SETTER" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                sb.appendLine("$sutVarName.$method(mockOf$type);")
            }
            "CALL_POST_CONSTRUCT" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                sb.appendLine("$sutVarName.$method();   // @PostConstruct")
            }
            "CALL_PUBLIC" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                sb.appendLine("$sutVarName.$method();")
            }
            "CALL_PUBLIC_WITH_STUBS" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                val stubs = field.metadata[MetaKeys.INIT_STUBS].orEmpty()
                sb.appendLine("// Stub first:")
                stubs.split(", ").forEach {
                    sb.appendLine("when($it).thenReturn(...);")
                }
                sb.appendLine("// Then:")
                sb.appendLine("$sutVarName.$method();")
            }
            "CALL_PUBLIC_WITH_ARGS" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                val args = field.metadata[MetaKeys.INIT_ARGS].orEmpty()
                val paramCalls = field.metadata[MetaKeys.INIT_PARAM_CALLS].orEmpty()
                val methodBody = field.metadata[MetaKeys.INIT_METHOD_BODY].orEmpty()
                // R3-B (Phase 2 bis, option c) — Rendre le source body de la
                // méthode d'init dans un bloc markdown ```java (cohérent avec
                // `# Internal sub-methods`). Le LLM lit le code et identifie
                // tout seul les getters à stuber sur les mocks des params.
                // Plus robuste que l'heuristique paramCallsToStub (PSI peine
                // sur l'héritage). N.B. les ``` ne sont pas des commentaires
                // Java au sens R2 — c'est un délimiteur de bloc dans le prompt.
                if (methodBody.isNotBlank()) {
                    sb.appendLine("Source body of `$method` (stub any getters called on the param mock(s) BEFORE the call below):")
                    sb.appendLine("```java")
                    sb.appendLine(methodBody.trim())
                    sb.appendLine("```")
                }
                // R3-B (Phase 2) — Heuristique paramCallsToStub : conserve le
                // signal si fonctionne (cas simples sans héritage PSI fragile).
                // Rendu en texte simple, pas en commentaire Java.
                if (paramCalls.isNotEmpty()) {
                    sb.appendLine("Detected getters on param mock(s) (may be incomplete — check body above):")
                    paramCalls.split(", ").forEach {
                        sb.appendLine("  - when($it).thenReturn(...)  // pick a sensible non-null value")
                    }
                }
                sb.appendLine("$sutVarName.$method($args);  // construct per the \"Data structures\" section")
            }
            "CALL_PUBLIC_TRANSITIVE" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                val chain = field.metadata[MetaKeys.INIT_CALL_CHAIN].orEmpty()
                val effects = field.metadata[MetaKeys.INIT_SIDE_EFFECTS].orEmpty()
                val stubs = field.metadata[MetaKeys.INIT_STUBS].orEmpty()
                val args = field.metadata[MetaKeys.INIT_ARGS].orEmpty()
                sb.appendLine("// This public method assigns `${field.title}` via the chain:")
                sb.appendLine("//    $chain")
                if (effects.isNotEmpty()) sb.appendLine("// Known side effects: $effects")
                if (stubs.isNotEmpty()) {
                    sb.appendLine("// Required stubs before the call:")
                    stubs.split(", ").forEach { sb.appendLine("// - $it") }
                }
                sb.appendLine("$sutVarName.$method($args);")
            }
            "CALL_SAME_PACKAGE" -> {
                val method = field.metadata[MetaKeys.INIT_METHOD_NAME].orEmpty()
                sb.appendLine("// Place the test in the same package as the class under test")
                sb.appendLine("$sutVarName.$method();")
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
        sb.appendLine("// STOP: this field cannot be initialized without refactoring.")
        sb.appendLine("// Reason: $reason")
        if (hints.isNotEmpty()) {
            sb.appendLine("// Hints:")
            hints.split('\n').forEach { sb.appendLine("//   - $it") }
        }
        sb.appendLine("// Generate a test marked TODO:")
        sb.appendLine("@Test")
        sb.appendLine("void ${field.title}_TODO_untestable() {")
        sb.appendLine("    fail(\"Test cannot be completed without refactoring the class under test. See docstring.\");")
        sb.appendLine("}")
    }

    // ── # Mocks ──────────────────────────────────────────────────────────────

    private fun renderMocks(sb: StringBuilder, mocks: List<ContextNode>) {
        if (mocks.isEmpty()) return
        sb.appendLine("# Mocks (annotate with @Mock {declaredType})")
        // Bug X — bloc Java prêt à copier-coller. Le LLM était observé en
        // production faisant `new PageDataDTO()` ou `new LigneResultatDTO()`
        // alors que ces types étaient dans `# Mocks` — violation R4. La cause :
        // le LLM devait inventer la déclaration. En pré-rendant le bloc ici,
        // il n'a plus qu'à copier — et `# Hard prohibitions` interdit toujours
        // le `new`, donc double verrou.
        sb.appendLine("Copy these field declarations VERBATIM into the test class:")
        sb.appendLine("```java")
        for (mock in mocks) {
            val declared = mock.metadata[MetaKeys.MOCK_DECLARED_TYPE].orEmpty()
            val varName = declared.substringAfterLast('.')
                .replaceFirstChar { it.lowercase() }
            sb.appendLine("@Mock")
            sb.appendLine("private $declared $varName;")
        }
        sb.appendLine("```")
        sb.appendLine()
        for (mock in mocks) {
            val declared = mock.metadata[MetaKeys.MOCK_DECLARED_TYPE].orEmpty()
            val concrete = mock.metadata[MetaKeys.MOCK_CONCRETE_TYPE].orEmpty()
            val varName = declared.substringAfterLast('.')
                .replaceFirstChar { it.lowercase() }
            sb.appendLine("## $declared  (concrete: $concrete)")
            val sigs = mock.metadata[MetaKeys.MOCK_SIGNATURES].orEmpty()
            if (sigs.isNotEmpty()) {
                // Bug BB — ancrer le nom de la variable dans le header ET dans
                // chaque ligne. Cas concret production : deux types
                // `SupervisionDeltaVecModele` et `TableauSupervisionDeltaVecModele`
                // sont listés consécutivement ; le LLM scannait `Methods to stub:`
                // et attribuait `getCriteresRecherche` au mauvais type
                // (`supervisionDeltaVecModele.getCriteresRecherche()` → erreur
                // compile : méthode inexistante). Préfixer chaque ligne par
                // le nom de variable élimine l'ambiguïté quel que soit l'angle
                // de scan du LLM.
                sb.appendLine("Methods to stub on `$varName`:")
                sigs.split('\n').forEach { sig ->
                    sb.appendLine("- $varName.$sig")
                }
            }
        }
        sb.appendLine()
    }

    // ── # Internal sub-methods ───────────────────────────────────────────────

    private fun renderInternalMethods(sb: StringBuilder, internals: List<ContextNode>) {
        if (internals.isEmpty()) return
        sb.appendLine("# Internal sub-methods (informational only, do not mock)")
        for (m in internals) {
            sb.appendLine("## ${m.title}")
            // STRATEGIE.md §6 + §3.2 — corps source des méthodes intra-SUT.
            // Frontière fermée pour MOCK_EXTERNAL (§3.3 « STOP ») mais les internes
            // sont par construction intra-SUT (cf RecursiveDeepStrategy §3.2 +
            // enrichInternalLogicsWithDownstream qui ne synthétise que pour la
            // hiérarchie SUT). Conditionnel pour les cas dégradés.
            val body = m.metadata[MetaKeys.INTERNAL_METHOD_BODY].orEmpty()
            if (body.isNotEmpty()) {
                sb.appendLine("Source code:")
                sb.appendLine("```java")
                sb.appendLine(body.trim())
                sb.appendLine("```")
            }
            // §6 — exceptions de la sous-méthode (frontière intra-SUT ouverte).
            renderBullet(sb, "throws", m.metadata[MetaKeys.INTERNAL_METHOD_THROWN])
            renderBullet(sb, "catches", m.metadata[MetaKeys.INTERNAL_METHOD_CAUGHT])
            val summaries = m.metadata["internalCallSummaries"].orEmpty()
            if (summaries.isNotEmpty()) {
                sb.appendLine("- key calls:")
                summaries.split('\n').forEach { sb.appendLine("  - $it") }
            }
        }
        sb.appendLine()
    }

    // ── # Methods to stub via spy (framework boundary, §3.2bis) ─────────────
    //
    // Une frontière framework descend dans `javax.faces.*`, `javax.servlet.*`
    // ou un autre cadre non-mockable raisonnablement. Le LLM doit créer un spy
    // de la classe sous test et stub la méthode avec `doAnswer(null)` (ou
    // `doReturn(...)` si la méthode retourne une primitive) — il ne doit
    // JAMAIS laisser s'exécuter ce code, sinon il aura besoin d'initialiser
    // tout l'écosystème framework (FacesContext, NavigationHandler…).

    private fun renderStubViaSpyMethods(
        sb: StringBuilder,
        methods: List<ContextNode>,
        sutVarName: String
    ) {
        if (methods.isEmpty()) return
        sb.appendLine("# Methods to stub via spy (framework boundary)")
        for (m in methods) {
            sb.appendLine("## ${m.title}")
            val prefixes = m.metadata[MetaKeys.STUB_VIA_SPY_PREFIXES].orEmpty()
            if (prefixes.isNotEmpty()) {
                sb.appendLine("- Reason: descends into $prefixes")
            }
            sb.appendLine("- Expected pattern in @BeforeEach:")
            sb.appendLine("  ```java")
            // Extrait le nom de méthode du title (format "$classFqn#$canonical").
            // Le canonical contient déjà les types d'argument entre parenthèses.
            val canonical = m.title.substringAfter('#')
            val methodName = canonical.substringBefore('(')
            val argTypes = canonical.substringAfter('(').substringBefore(')')
            val anyExpr = if (argTypes.isEmpty()) "" else {
                argTypes.split(',').joinToString(", ") { "any(${it.trim()}.class)" }
            }
            // Bug O — valeur de retour cohérente avec la signature stubée.
            // L'ancien `doReturn(/* TODO */)` poussait le LLM à inventer un type
            // (vu en production : `doReturn(pageDataDTO)` sur une méthode qui
            // retourne String → WrongTypeOfReturnValue runtime).
            // Règle : `doAnswer(invocation -> null)` est sûr pour void et tout
            // type objet ; pour les primitives on émet une constante typée.
            val returnType = m.metadata[MetaKeys.METHOD_RETURN_TYPE].orEmpty()
            val doExpr = when (returnType) {
                "boolean", "java.lang.Boolean" -> "doReturn(false)"
                "byte", "short", "int", "long", "float", "double",
                "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
                "java.lang.Long", "java.lang.Float", "java.lang.Double" -> "doReturn(0)"
                "char", "java.lang.Character" -> "doReturn('\\u0000')"
                else -> "doAnswer(invocation -> null)"
            }
            sb.appendLine("  $sutVarName = spy($sutVarName);")
            sb.appendLine("  $doExpr.when($sutVarName).$methodName($anyExpr);")
            sb.appendLine("  ```")
            sb.appendLine("- Do NOT explore the body — closed test boundary.")
        }
        sb.appendLine()
    }

    // ── # Data structures ────────────────────────────────────────────────────

    private fun renderDataStructures(sb: StringBuilder, dtos: List<ContextNode>) {
        if (dtos.isEmpty()) return
        sb.appendLine("# Data structures to construct")
        for (dto in dtos) {
            val pattern = dto.metadata[MetaKeys.DTO_PATTERN].orEmpty()
            sb.appendLine("## ${dto.title} [$pattern]")
            val fields = dto.metadata[MetaKeys.DTO_FIELDS].orEmpty()
            if (fields.isNotEmpty()) sb.appendLine("Fields: $fields")
            // R3-A — affiche les constantes ENUM disponibles. Sans ça, le LLM
            // hallucine (ex Astrea case 4.1 : `OrdreTriEnum.ASC` au lieu de
            // `ASCENDANT`). N'est rendu que pour les nœuds de pattern ENUM.
            val enumValues = dto.metadata[MetaKeys.DTO_ENUM_VALUES].orEmpty()
            if (enumValues.isNotEmpty()) sb.appendLine("Values: $enumValues")
        }
        sb.appendLine()
    }

    // ── # Static calls ───────────────────────────────────────────────────────

    private fun renderStaticCalls(sb: StringBuilder, statics: List<ContextNode>) {
        // Filtre uniquement les nœuds CUSTOM portant un staticClass — d'autres
        // CUSTOM pourraient apparaître à terme (extension par stratégies tierces).
        val staticOnly = statics.filter { it.metadata.containsKey("staticClass") }
        if (staticOnly.isEmpty()) return
        sb.appendLine("# Detected user static calls")
        sb.appendLine("Mock via Mockito.mockStatic({class}.class):")
        for (sc in staticOnly) {
            sb.appendLine("- ${sc.title}")
        }
        sb.appendLine()
    }

    companion object {
        // Stratégies « implicites » §6 ligne 1069 — non rendues dans le bloc
        // « # Field initialization protocol » car gérées au moment de
        // `new ClassName(...)` (BLOC 4) ou par Mockito.@InjectMocks.
        private val IMPLICIT_KINDS = setOf(
            "CONSTRUCTOR", "IMPLICIT", "IMPLICIT_VIA_CONSTRUCTOR", "MOCKITO_INJECT_MOCKS"
        )
    }
}

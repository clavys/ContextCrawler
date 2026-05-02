# Architecture — ContextExtractor (plugin IntelliJ)

> Outil IntelliJ qui extrait le contexte d'une méthode Java pour générer
> des tests unitaires sans erreur de compilation, via un prompt optimisé.

---

## 1. Principes directeurs

1. **Hexagonal léger (ports & adapters)** — le cœur (`core/`) ne dépend
   *jamais* de PSI/IntelliJ. PSI vit uniquement dans `adapters/psi/`.
   Conséquence : tu testes la logique en pur JUnit, sans `IntelliJ TestCase`.
2. **Un seul modèle de contexte** — pas de duplication entre modèle d'extraction
   et modèle de rendu, pas de mapper intermédiaire. Un seul
   `ContextTree` typé, navigable, extensible.
3. **Strategy *vraiment* pluggable** — registre + extension point IntelliJ.
   Ajouter une stratégie = 1 classe + 1 entrée XML, zéro modif du cœur.
4. **Pipeline de prompt** — le builder n'est plus un god-object,
   c'est une chaîne de `PromptStage` (chacun a une responsabilité).
5. **Config en couches** — defaults < settings IDE < `.contextextractor.yml`
   versionné par projet, tout est mergeable.
6. **Async + cancellable** — toute extraction tourne dans un
   `ReadAction.nonBlocking()` annulable, pour ne pas freezer l'IDE.
7. **Convention de langue** — Code Kotlin **en anglais** (classes, méthodes,
   variables, fichiers, packages). **Commentaires inline en français** —
   le "quoi" est en anglais, le "pourquoi" est en français. Templates de
   prompt et messages utilisateur en français. Logs techniques en anglais.
   Les data classes et termes "métier" qui apparaissent en français dans
   STRATEGIE.md (`ContexteResultat`, `methodeCible`, `champs`) sont du
   pseudo-code de spec — l'implémentation Kotlin utilise les versions
   anglaises (`ContextResult`, `targetMethod`, `fields`).
   Voir CLAUDE.md "Convention de langue" pour le tableau complet.

---

## 2. Vue d'ensemble en couches

```
┌─────────────────────────────────────────────────────────────┐
│  ide/         IntelliJ glue : Action, Settings, ToolWindow  │
├─────────────────────────────────────────────────────────────┤
│  strategies/  Implémentations concrètes (Recursive, Diff…)  │
├─────────────────────────────────────────────────────────────┤
│  adapters/    PSI Java, Clients LLM, Loader templates…      │
├─────────────────────────────────────────────────────────────┤
│  core/        Modèle, interfaces (ports), pipeline prompt   │
│               ► AUCUNE dépendance IntelliJ ◄                │
└─────────────────────────────────────────────────────────────┘
```

Règle d'or : les flèches d'import ne vont **que vers le bas**.
`core/` ne connaît personne, `ide/` connaît tout le monde.

---

## 3. Arborescence de packages

```
com.contextextractor
├── core/                                ◄── pure Kotlin, testable
│   ├── model/
│   │   ├── ContextNode.kt               (sealed hierarchy)
│   │   ├── ContextTree.kt               (root + index by id/kind)
│   │   ├── NodeKind.kt                  (enum)
│   │   └── nodes/                       (TargetMethod, Mock, DataStruct…)
│   ├── extractor/                       ◄── PORT
│   │   ├── CodeIntrospector.kt          (interface, langage-agnostique)
│   │   ├── Symbol.kt
│   │   ├── ClassDescriptor.kt
│   │   └── MethodSignature.kt
│   ├── classifier/
│   │   ├── ClassClassifier.kt           (interface)
│   │   ├── DefaultClassifier.kt
│   │   └── ClassificationRule.kt        (règles personnalisables)
│   ├── strategy/
│   │   ├── ContextStrategy.kt           (interface)
│   │   ├── StrategyInput.kt
│   │   ├── StrategyRegistry.kt
│   │   └── StrategyConfig.kt
│   ├── prompt/
│   │   ├── PromptBuilder.kt             (orchestrateur du pipeline)
│   │   ├── PromptContext.kt             (état du pipeline)
│   │   ├── PromptStage.kt               (interface)
│   │   ├── stages/
│   │   │   ├── ContextRenderStage.kt
│   │   │   ├── MetaPromptComposeStage.kt
│   │   │   ├── LayerCompositionStage.kt
│   │   │   └── CleanupStage.kt
│   │   ├── template/
│   │   │   ├── TemplateEngine.kt        (Mustache-like + includes)
│   │   │   ├── TemplateLoader.kt        (interface)
│   │   │   └── PartialResolver.kt
│   │   └── meta/
│   │       ├── MetaPrompt.kt
│   │       └── LayerKind.kt             (ROLE, CONTEXT, INSTRUCTIONS, TASK, CUSTOM)
│   ├── llm/                             ◄── PORT
│   │   ├── LlmClient.kt                 (interface)
│   │   ├── PromptRequest.kt
│   │   └── PromptResponse.kt
│   └── config/
│       ├── ContextExtractorConfig.kt
│       ├── ConfigSource.kt              (interface)
│       └── LayeredConfig.kt             (merge de plusieurs sources)
│
├── adapters/                            ◄── implémentations
│   ├── psi/
│   │   ├── JavaPsiIntrospector.kt       (implémente CodeIntrospector)
│   │   ├── PsiToSymbolMapper.kt
│   │   └── internal/                    (visiteurs, helpers)
│   ├── llm/
│   │   ├── ClaudeClient.kt
│   │   ├── OpenAiClient.kt
│   │   └── OllamaClient.kt              (local, optionnel)
│   ├── template/
│   │   ├── ClasspathTemplateLoader.kt   (templates par défaut)
│   │   └── FileSystemTemplateLoader.kt  (templates utilisateur)
│   └── config/
│       ├── IntellijSettingsSource.kt    (PersistentStateComponent)
│       └── YamlProjectConfigSource.kt   (.contextextractor.yml)
│
├── strategies/                          ◄── stratégies = applications du cœur
│   ├── recursive/
│   │   ├── RecursiveDeepStrategy.kt     (ton algo actuel, refactorisé)
│   │   ├── modes/                       (SutBootstrap, InternalLogic, MockExternal, DataStructure)
│   │   └── visitors/
│   ├── shallow/
│   │   └── ShallowMethodStrategy.kt     (méthode + 1 niveau de calls)
│   └── diff/
│       └── GitDiffStrategy.kt           (futur)
│
├── ide/                                 ◄── couche IntelliJ
│   ├── action/
│   │   ├── ExtractContextAction.kt
│   │   └── GenerateTestAction.kt
│   ├── settings/
│   │   ├── ContextExtractorConfigurable.kt
│   │   ├── ContextExtractorState.kt
│   │   └── ui/                          (panneaux Swing)
│   ├── toolwindow/
│   │   ├── ContextPreviewToolWindow.kt
│   │   └── PromptPreviewPanel.kt
│   └── service/
│       ├── ContextExtractorService.kt   (Project service, point d'entrée)
│       └── ProjectConfigService.kt
│
└── infrastructure/
    ├── logging/ExtractorLogger.kt
    └── di/ServiceFactory.kt             (mince, juste le câblage)
```

---

## 4. Cœur du modèle : `ContextTree` unifié

Modèle unique partagé entre toutes les stratégies d'extraction et tous
les renderers de prompt :

```kotlin
sealed interface ContextNode {
    val id: String                   // clé unique stable (ex: "method:com.X#foo")
    val kind: NodeKind
    val title: String
    val children: List<ContextNode>
    val metadata: Map<String, String>
}

enum class NodeKind {
    HIERARCHY, TARGET_METHOD, FIELD, CONSTRUCTOR, SETTER,
    INTERNAL_METHOD, MOCK, DATA_STRUCTURE, EXCEPTION,
    DOCUMENTATION, GIT_DIFF, CUSTOM
}

data class ContextTree(
    val root: ContextNode,
    private val index: Map<String, ContextNode>,
    private val byKind: Map<NodeKind, List<ContextNode>>
) {
    fun byId(id: String) = index[id]
    fun ofKind(kind: NodeKind) = byKind[kind].orEmpty()
    fun walk(): Sequence<ContextNode> = sequence { /* DFS */ }
}
```

> **Note importante — `NodeKind` vs `Mode`**
>
> `NodeKind` (ce fichier) et `Mode` (STRATEGIE.md §2.1) sont **deux concepts distincts** :
>
> - **`Mode`** est une **décision contextuelle** prise pendant la récursion :
    >   "comment dois-je traiter ce type ici ?". Le même type peut être `MOCK_EXTERNAL`
    >   à un endroit et `DATA_STRUCTURE` à un autre selon le contexte d'appel.
>
> - **`NodeKind`** est une **étiquette stable** d'un nœud dans l'arbre final :
    >   "qu'est-ce que ce nœud représente ?". Une fois le nœud créé, son kind ne change plus.
>
> Mapping typique de `Mode` vers `NodeKind` :
>
> | Mode (extraction)   | NodeKind (rendu)              |
> |---------------------|-------------------------------|
> | `SUT_BOOTSTRAP`     | `TARGET_METHOD` + `FIELD`s    |
> | `INTERNAL_LOGIC`    | `INTERNAL_METHOD`             |
> | `MOCK_EXTERNAL`     | `MOCK`                        |
> | `DATA_STRUCTURE`    | `DATA_STRUCTURE`              |
> | `SYSTEM_IGNORE`     | (aucun nœud créé)             |
> | `FUNCTIONAL_LAMBDA` | `MOCK` (avec metadata lambda) |
> | `CONTAINER`         | `DATA_STRUCTURE`              |
>
> La conversion se fait dans `RecursiveDeepStrategy` au moment de la création
> du nœud. C'est le seul endroit qui connaît les deux taxonomies.

Avantage : tu n'as plus besoin du mapper. Le rendu Markdown se fait
directement par un *visitor* sur l'arbre, branche par branche.

---

## 5. Le port `CodeIntrospector` (clé pour la testabilité)

Tout ce que la stratégie a besoin de savoir sur le code passe par cette
interface. PSI n'apparaît **jamais** dans `core/`.

```kotlin
interface CodeIntrospector {
    fun resolveSymbolAt(file: SourceFile, offset: Int): Symbol?
    fun findEnclosingMethod(symbol: Symbol): MethodSignature?
    fun resolveClass(fqn: String): ClassDescriptor?
    fun listMethodCalls(method: MethodSignature): List<MethodCall>
    fun listFieldAccesses(method: MethodSignature): List<FieldAccess>
    fun listSuperClasses(cls: ClassDescriptor): List<ClassDescriptor>
    fun readMethodBody(method: MethodSignature): String
    fun listAnnotations(target: AnnotatedTarget): List<AnnotationRef>
}
```

V1 : seul `JavaPsiIntrospector` (adapter PSI) implémente ça.
V2 (Kotlin) : `KotlinPsiIntrospector` s'ajoute, **rien ne bouge dans `core/`**.

Pour les tests, tu écris un `FakeIntrospector` en mémoire — finis les
`HeavyIdeaTestCase` qui mettent 30s à démarrer.

---

## 6. Strategy registry

```kotlin
interface ContextStrategy {
    val id: String                   // "recursive-deep", "shallow", "git-diff"…
    val displayName: String
    val description: String
    fun extract(input: StrategyInput): ContextTree
}

data class StrategyInput(
    val introspector: CodeIntrospector,
    val classifier: ClassClassifier,
    val cursor: CursorLocation,
    val config: StrategyConfig
)
```

Côté IntelliJ, exposer un **extension point** :

```xml
<extensionPoint name="contextStrategy"
                interface="com.contextextractor.core.strategy.ContextStrategy"
                dynamic="true"/>
```

Du coup ton `RecursiveDeepStrategy` se déclare en XML et le `StrategyRegistry`
le découvre tout seul. Tu peux livrer d'autres stratégies en plugins
séparés plus tard.

---

## 7. Le mix de méta-prompts (tu hésitais entre les 3 options)

Voici **le mix** qui prend le meilleur des 3 :

```
┌──────────── PromptBuilder.build() ─────────────┐
│                                                │
│  ContextRenderStage                            │
│    ContextTree → Markdown des nœuds            │
│    → remplit la layer CONTEXT                  │
│                                                │
│  MetaPromptComposeStage                        │
│    Résout {{>partial}} dans CHAQUE layer       │
│    (option 1 : templates imbriqués)            │
│                                                │
│  LayerCompositionStage                         │
│    Assemble ROLE + CONTEXT + INSTRUCTIONS      │
│    + TASK en un seul markdown                  │
│    (option 3 : layers first-class)             │
│                                                │
│  CleanupStage                                  │
│    Vire les blocs {{#hasX}} vides, doubles     │
│    sauts de ligne, etc.                        │
│                                                │
│  └─ pipeline = chaîne (option 2 light)         │
└────────────────────────────────────────────────┘
```

```kotlin
interface PromptStage {
    val id: String
    fun apply(ctx: PromptContext): PromptContext
}

data class PromptContext(
    val tree: ContextTree,
    val layers: MutableMap<LayerKind, String> = mutableMapOf(),
    val metaSlots: MutableMap<String, String> = mutableMapOf(),
    var finalText: String? = null
)

class PromptBuilder(private val stages: List<PromptStage>) {
    fun build(tree: ContextTree, cfg: PromptConfig): String {
        var ctx = PromptContext(tree)
        stages.forEach { ctx = it.apply(ctx) }
        return ctx.finalText ?: error("Pipeline a oublié finalText")
    }
}
```

Ce design te permet :
- **Réutiliser** des fragments via `{{>junit5-header}}` dans n'importe quel template
- **Recomposer** un prompt en réordonnant les layers ou en ajoutant un stage
- **Plug** un stage utilisateur (ex: `LengthGuardStage` qui tronque si > N tokens)

> **Voir aussi** : la structure complète du prompt produit par ce pipeline
> est définie dans **PROMPT_FORMAT.md** (sections SYSTEM, CONTEXT,
> USER_ENRICHMENT, CONSTRAINTS, INSTRUCTION). Le rendu spécifique de la
> layer CONTEXT est défini dans **STRATEGIE.md section 6**.

### Format des templates utilisateur

`templates/deep-unit-test.md` :

```markdown
{{>partials/header-junit5}}

# Méthode à tester
{{layer:CONTEXT}}

# Consignes
{{layer:INSTRUCTIONS}}

{{>partials/footer-no-mock-static}}
```

`templates/partials/header-junit5.md` est un fragment réutilisable.
Le `PartialResolver` les charge depuis classpath ou disque (templates
custom de l'utilisateur).

---

## 8. Configuration en couches

```kotlin
interface ConfigSource {
    val priority: Int                            // bigger = override
    fun load(): Map<String, Any>
}

class LayeredConfig(private val sources: List<ConfigSource>) {
    private val merged: Map<String, Any> by lazy { /* deep merge by priority */ }
    fun <T> get(key: String, type: Class<T>): T?
}
```

Sources livrées :
- `DefaultsConfigSource` (priorité 0) — defaults compilés
- `IntellijSettingsSource` (priorité 10) — `PersistentStateComponent`
- `YamlProjectConfigSource` (priorité 20) — `.contextextractor.yml` racine projet

`.contextextractor.yml` exemple :

```yaml
strategy: recursive-deep
templates:
  dir: .ai/templates                # custom templates dir
  default: deep-unit-test
classification:
  mockSuffixes: [Service, Repository, Gateway]
  dataSuffixes: [DTO, Entity, Request, Response, Command]
llm:
  provider: claude                  # or openai, ollama, none
  model: claude-sonnet-4-6
  temperature: 0.2
prompt:
  layers:
    role: partials/role-senior-tester
    instructions: |
      - Cas nominal + cas limites
      - Vérifie les side-effects sur les champs
```

---

## 9. Mode dual : copier OU appeler le LLM

```kotlin
interface LlmClient {
    val id: String
    suspend fun complete(req: PromptRequest): PromptResponse
}

class GenerateTestAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val service = e.project!!.service<ContextExtractorService>()
        val tree   = service.extract(e)
        val prompt = service.buildPrompt(tree)

        when (val mode = service.config.outputMode) {
            OutputMode.COPY     -> PromptCopyDialog(project, prompt).show()
            OutputMode.LLM_CALL -> service.llm.complete(prompt) { response ->
                                       service.writeTestFile(response, e)
                                   }
            OutputMode.ASK      -> showChoiceDialog(...)
        }
    }
}
```

V1 : `ClaudeClient` + `OpenAiClient`. Clé API stockée via
`PasswordSafe` (jamais en YAML).

---

## 10. Diagramme du flux complet

```
┌─ User: curseur dans une méthode + Alt+G ─────────────────┐
│                                                          │
│  ExtractContextAction (ide/action)                       │
│    │                                                     │
│    └─► ContextExtractorService.extract(event)            │
│         │                                                │
│         ├─► LayeredConfig.resolve()                      │
│         │     ◄─ Defaults + IntellijSettings + YAML      │
│         │                                                │
│         ├─► JavaPsiIntrospector  ◄── seul point couplé   │
│         │     (adapters/psi)        à PSI                │
│         │                                                │
│         ├─► StrategyRegistry.get(cfg.strategyId)         │
│         │      │                                         │
│         │      ▼                                         │
│         │   RecursiveDeepStrategy.extract(input)         │
│         │      → ContextTree                             │
│         │                                                │
│         └─► PromptBuilder.build(tree, cfg.prompt)        │
│              │ (pipeline de PromptStages)                │
│              ▼                                           │
│         String prompt                                    │
│                                                          │
│   selon cfg.outputMode :                                 │
│     ├─► PromptCopyDialog       (COPY)                    │
│     └─► LlmClient.complete()   (LLM_CALL)                │
│           → écriture test file                           │
└──────────────────────────────────────────────────────────┘
```

---

## 12. Roadmap suggérée (ordre d'implémentation)

1. **Squelette `core/`** : modèle `ContextTree`, ports `CodeIntrospector`,
   `ContextStrategy`, `LlmClient`, `ConfigSource`. *Aucune dépendance IntelliJ.*
   **+ test-project** : `build.gradle.kts` qui compile, `case00_baseline` +
   `case91-95` + `case96_degraded` + `EXPECTED_PROMPTS.md`.
2. **Tests unitaires `core/`** avec un `FakeIntrospector` — valide le design
   sur `case00` (baseline) et au moins 1 cas dégradé (§8bis).
3. **Adapter PSI** : `JavaPsiIntrospector` qui passe les tests d'intégration.
4. **Stratégie récursive** : implémenter `RecursiveDeepStrategy` selon
   l'algorithme de STRATEGIE.md §3. Doit gérer les cas dégradés de §8bis
   sans crasher.
5. **Pipeline prompt** : `ContextRenderStage` (rend la layer CONTEXT selon
   STRATEGIE.md §6) + `LayerCompositionStage` (assemble le wrapper complet
   selon PROMPT_FORMAT.md), puis `MetaPromptComposeStage` (vrai gain).
6. **Settings IDE** + `.contextextractor.yml`.
7. **Mode COPY** : valider sur `case00 + case92 + case93` à 100% sur le
   vrai code Java de `test-project/` avant de passer à l'étape 8.
8. **Mode LLM_CALL** (`AnthropicApiClient` + `ClaudeCodeClient`) +
   **Tool window** : preview live du `ContextTree` + du prompt.

---

## 13. Choix UX user-friendly

- **Preview vivant** : tool window à gauche qui montre le `ContextTree`
  et le prompt en temps réel quand le curseur bouge.
- **Action contextuelle** : clic droit dans une méthode → "Generate Test
  Context" / "Generate Test (LLM)".
- **Notification non-bloquante** quand un test est généré (pas un Dialog).
- **Diff preview** avant d'écrire le fichier de test.
- **Hot-reload des templates** : si l'utilisateur édite un `.md` dans
  son `templates/`, le cache est invalidé.

---

## 14. Ce qu'on a *volontairement* mis hors scope V1

- Multi-langage (architecture prête, mais un seul adapter)
- Stratégies "shallow" et "git-diff" (placeholders)
- Pipeline LLM avancé (retries exponentiels, fallback, streaming)
- Mode "fix the test" (relancer avec les erreurs de compil en feedback)

---

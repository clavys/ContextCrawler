# Architecture — ContextExtractor (plugin IntelliJ)

> Outil IntelliJ qui extrait le contexte d'une méthode Java pour générer
> des tests unitaires sans erreur de compilation, via un prompt optimisé.

---

## 1. Principes directeurs

1. **Hexagonal léger (ports & adapters)** — le cœur (`core/`) ne dépend
   *jamais* de PSI/IntelliJ. PSI vit uniquement dans `adapters/psi/`.
   Conséquence : tu testes la logique en pur JUnit, sans `IntelliJ TestCase`.
2. **Un seul modèle de contexte** — fini la duplication
   `RecursiveContextResult` + `GenericContextModel` + mapper. Un seul
   `ContextTree` typé, navigable, extensible.
3. **Strategy *vraiment* pluggable** — registre + extension point IntelliJ.
   Ajouter une stratégie = 1 classe + 1 entrée XML, zéro modif du cœur.
4. **Pipeline de prompt** — le builder n'est plus un god-object,
   c'est une chaîne de `PromptStage` (chacun a une responsabilité).
5. **Config en couches** — defaults < settings IDE < `.contextextractor.yml`
   versionné par projet, tout est mergeable.
6. **Async + cancellable** — toute extraction tourne dans un
   `ReadAction.nonBlocking()` annulable, pour ne pas freezer l'IDE.

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

Remplace `RecursiveContextResult` **et** `GenericContextModel` :

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

## 11. Migration depuis ton prototype

| Existant                          | Devient                              | Action                        |
|-----------------------------------|--------------------------------------|-------------------------------|
| `RecursiveContextResult`          | `ContextTree` + nœuds typés          | Suppression du mapper         |
| `GenericContextModel`             | idem                                 | Fusion                        |
| `RecursiveContextResultMapper`    | Visitor de rendu Markdown            | Devient `ContextRenderStage`  |
| `ContextStrategy` (inutilisée)    | Réellement implémentée + registry    | Hook avec extension point     |
| `ContextSearcher`                 | `ContextExtractorService`            | Renommage + DI                |
| `PsiScanner`                      | `JavaPsiIntrospector` (adapter)      | Refacto derrière l'interface  |
| `ClassResolver`                   | méthode dans `JavaPsiIntrospector`   | Fusion                        |
| `classify()` (×2)                 | `DefaultClassifier`                  | Une seule source de vérité    |
| `UniversalPromptGenerator`        | `PromptBuilder` + `PromptStage`s     | Découpage en pipeline         |
| `TemplateManager`                 | `ClasspathTemplateLoader` + cache    | Pas de singleton              |
| `GetContextAction`                | `ExtractContextAction` slim          | Toute la logique → service    |
| `System.err.println`              | `Logger` IntelliJ                    | Remplacement                  |

---

## 12. Roadmap suggérée (ordre d'implémentation)

1. **Squelette `core/`** : modèle `ContextTree`, ports `CodeIntrospector`,
   `ContextStrategy`, `LlmClient`, `ConfigSource`. *Aucune dépendance IntelliJ.*
2. **Tests unitaires `core/`** avec un `FakeIntrospector` — valide le design.
3. **Adapter PSI** : `JavaPsiIntrospector` qui passe les tests d'intégration.
4. **Stratégie récursive** portée vers la nouvelle interface (réutilise
   ton algorithme actuel, mais dans le nouveau contrat).
5. **Pipeline prompt** : `ContextRenderStage` + `LayerCompositionStage`
   (pour égaler l'existant), puis `MetaPromptComposeStage` (vrai gain).
6. **Settings IDE** + `.contextextractor.yml`.
7. **Mode COPY** (égaler l'existant), puis **mode LLM_CALL** (Claude d'abord).
8. **Tool window** : preview live du `ContextTree` + du prompt.

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

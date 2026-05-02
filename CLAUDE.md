# ContextCrawler — Plugin IntelliJ

> Plugin IntelliJ en Kotlin qui analyse le contexte d'une méthode Java
> et génère un prompt optimisé pour qu'un LLM produise un test JUnit 5
> qui **compile** et passe sur le chemin nominal.

---

## Stack technique

- **Kotlin** 2.x (pas de Java dans le plugin lui-même)
- **IntelliJ Platform SDK** : platformVersion = `"261"` (IntelliJ 2026.1)
- **Gradle 9.2.1** + Kotlin DSL (`build.gradle.kts`)
- **gradle-intellij-plugin** (Jetbrains) pour `runIde` et le sandbox
- **JUnit 5 + Mockito + AssertJ** — stack des tests générés (pas des tests du plugin)
- **JUnit 5** — pour les tests unitaires du `core/` du plugin

---

## Langue du code
Tout le code, commentaires, noms de classes, méthodes et variables
doivent être en anglais. Les réponses peuvent être en français.

---

## Specs — LIS CES FICHIERS EN PREMIER, dans cet ordre

```
specs/ARCHITECTURE.md      → architecture hexagonale, modèle ContextTree,
                             couches, roadmap d'implémentation (section 12)
specs/STRATEGIE.md         → algorithme complet de récupération de contexte,
                             taxonomie des modes, pseudo-code par mode,
                             APIs PSI clés, pièges, format du prompt de sortie
specs/PROMPT_FORMAT.md     → structure exacte du prompt final envoyé au LLM,
                             exemple complet, cas spéciaux, règles de rendu
```

**Règle absolue** : lis les trois fichiers intégralement avant d'écrire
la moindre ligne de code. Toutes les décisions d'architecture y sont
justifiées.

---

## Règles d'architecture (résumé des invariants)

1. `core/` ne contient **aucune** dépendance IntelliJ/PSI.
   Les flèches d'import vont uniquement vers le bas :
   `ide/` → `strategies/` → `adapters/` → `core/`
2. Un seul modèle de contexte : `ContextTree` (pas de mapper intermédiaire).
3. Toute extraction tourne dans un `ReadAction.nonBlocking()` annulable.
4. Les clés API LLM sont stockées via `PasswordSafe`, jamais en YAML.
5. La stratégie récursive respecte le `Budget` défini dans `STRATEGIE.md`
   (profondeurMax, nbDtoMax, nbMocksMax, tokensEstimésMax).

---

## Stratégie de test — feedback loop sans sandbox

Le `core/` est pur Kotlin sans dépendance IntelliJ :
**teste-le avec de vrais JUnit 5, sans `IntelliJ TestCase`**.

| Couche | Comment tester |
|--------|---------------|
| `core/` (modèle, ports, pipeline) | JUnit 5 pur — pas de sandbox |
| `strategies/recursive/` | JUnit 5 via `FakeIntrospector` (voir ci-dessous) |
| `adapters/psi/` | Tests d'intégration IntelliJ (`LightJavaCodeInsightTestCase`) |
| `ide/` (actions, UI) | Manuel via `runIde` |

### FakeIntrospector
Crée `FakeIntrospector : CodeIntrospector` en mémoire dans `src/test/`.
Il doit couvrir les 5 cas concrets de `STRATEGIE.md` sections 9.1 à 9.5 :
- 9.1 `@PostConstruct` prioritaire
- 9.2 méthode publique avec arguments
- 9.3 chaîne transitive
- 9.4 auto-init dans la méthode cible
- 9.5 cas non-testable (`UNTESTABLE_AS_IS`)

Chaque cas devient un test JUnit qui vérifie que le `ContextTree` produit
et le prompt généré correspondent au comportement attendu décrit dans les specs.

---

## Projet de test Java — auto-évaluation sur du vrai code

Un mini projet Java est versionné dans `test-project/` à la racine du repo.
Il représente exactement les 5 cas de `STRATEGIE.md` sections 9.1 à 9.5
sous forme de vraies classes Java que le plugin analysera via PSI.

### Structure
```
test-project/
└── src/main/java/com/testproject/
    ├── case91/   → toutes les classes nécessaires pour @PostConstruct prioritaire
    │              (service, dépendances, DTOs, interfaces...)
    ├── case92/   → toutes les classes nécessaires pour méthode publique avec arguments
    │              (service, repository, entités, DTOs, interfaces...)
    ├── case93/   → toutes les classes nécessaires pour chaîne transitive
    │              (plusieurs services enchaînés, DTOs, enums, interfaces...)
    ├── case94/   → toutes les classes nécessaires pour auto-init dans la méthode cible
    │              (service, dépendances lazy, DTOs...)
    └── case95/   → toutes les classes nécessaires pour UNTESTABLE_AS_IS
                   (dépendances statiques, constructeurs complexes, etc.)
```

Chaque package `caseXX/` doit être **réaliste** — inclure autant de classes
(DTOs, interfaces, super-classes, enums, repositories) que nécessaire pour
que la stratégie récursive ait un vrai graphe de dépendances à crawler.
Ne pas se limiter à une seule classe par cas.

### Fichier EXPECTED_PROMPTS.md
Crée `test-project/EXPECTED_PROMPTS.md` qui décrit pour chaque classe :
- La méthode cible à analyser
- Le `ContextTree` attendu (résumé)
- Les sections clés attendues dans le prompt généré

Ce fichier sert de référence pour comparer le prompt réel reçu de l'utilisateur.

### Workflow d'auto-évaluation aux étapes 7 et 8

Quand le plugin est prêt à être testé sur du vrai code :
1. Lance `./gradlew runIde`
2. Dans le sandbox IntelliJ qui s'ouvre, ouvre `test-project/` comme projet Java
3. **Demande à l'utilisateur** de placer le curseur dans la méthode cible
   et de déclencher l'action (Alt+G), puis de coller le prompt généré dans le terminal
4. Compare le prompt reçu avec `test-project/EXPECTED_PROMPTS.md`
5. Identifie les écarts, corrige le code, relance `runIde`, itère

**Ne jamais passer à l'étape suivante sans avoir validé au moins les cas 9.2 et 9.3
sur le vrai code Java de `test-project/`.**

---

## Règle de travail — une étape à la fois

Implémente **une étape à la fois** selon la roadmap de `ARCHITECTURE.md`
section 12. **Arrête-toi après chaque étape, compile, lance les tests,
et attends ma validation avant de passer à la suivante.**

### Étape 1 — Squelette `core/` (interfaces uniquement) + test-project
Crée toutes les interfaces et data classes du `core/` :
`ContextNode`, `ContextTree`, `NodeKind`, `CodeIntrospector`,
`ContextStrategy`, `StrategyInput`, `StrategyRegistry`,
`LlmClient`, `PromptRequest`, `PromptResponse`,
`PromptStage`, `PromptContext`, `PromptBuilder`,
`ConfigSource`, `LayeredConfig`, `ContextExtractorConfig`.
**Pas d'implémentation PSI. Pas d'adapter. Pas d'IDE glue.**

Crée également :
- Le `test-project/` avec toutes les classes Java nécessaires pour les cas 9.1 à 9.5
  (DTOs, interfaces, super-classes, enums, repositories — autant que chaque cas l'exige)
- `test-project/EXPECTED_PROMPTS.md` avec les prompts attendus pour chaque cas
- Un test ArchUnit qui vérifie que `core/` n'importe jamais de classes `com.intellij..`

Résultat attendu : `./gradlew compileKotlin` passe sans erreur.

### Étape 2 — FakeIntrospector + tests unitaires `core/`
Implémente `FakeIntrospector` et écris les tests JUnit 5 pour les
5 cas des sections 9.1–9.5. Valide que le design du `core/` est testable.
Résultat attendu : `./gradlew test` passe, tous les tests verts.

### Étape 3 — Adapter PSI (`JavaPsiIntrospector`)
Implémente `JavaPsiIntrospector` qui satisfait l'interface `CodeIntrospector`.
Utilise uniquement les APIs PSI listées dans `STRATEGIE.md` section 7.1.
Respecte les pièges section 7.2 (toujours `getCanonicalText()`, ReadAction, etc.).
Résultat attendu : tests d'intégration PSI passent.

### Étape 4 — Stratégie récursive
Porte l'algorithme de `STRATEGIE.md` vers `RecursiveDeepStrategy`.
Utilise `FakeIntrospector` pour les tests, pas PSI directement.
Résultat attendu : les 5 cas de test produisent le `ContextTree` attendu.

### Étape 5 — Pipeline prompt
Implémente les 4 `PromptStage` :
`ContextRenderStage` → `MetaPromptComposeStage` →
`LayerCompositionStage` → `CleanupStage`.
Le prompt produit doit respecter le format de `STRATEGIE.md` section 6.
Résultat attendu : tests JUnit sur le prompt généré pour chaque cas 9.x.

### Étape 6 — Settings IDE + config YAML
`IntellijSettingsSource`, `YamlProjectConfigSource`, `LayeredConfig` assemblé.
UI Settings minimale (juste les champs essentiels V1).

### Étape 7 — Mode COPY + validation sur test-project
`ExtractContextAction` + `PromptCopyDialog`.

Workflow de validation :
1. `./gradlew runIde`
2. Ouvre `test-project/` dans le sandbox
3. Demande à l'utilisateur de tester chaque cas (9.1 à 9.5) et de coller le prompt généré
4. Compare avec `test-project/EXPECTED_PROMPTS.md`
5. Itère jusqu'à ce que les cas 9.2 et 9.3 soient conformes aux specs

Résultat attendu : les prompts générés correspondent aux prompts attendus pour au moins 4 cas sur 5.

### Étape 8 — Mode LLM_CALL + Tool Window
`GenerateTestAction`, `ContextPreviewToolWindow`.
Implémente **deux** `LlmClient` au choix dans les Settings :

**Option A — `AnthropicApiClient`**
- Appel direct à `api.anthropic.com/v1/messages`
- Clé API stockée via `PasswordSafe`
- Modèle configurable (ex: `claude-opus-4-5`)

**Option B — `ClaudeCodeClient`**
- Délègue à la CLI Claude Code installée localement via `ProcessBuilder`
- Utilise le flag `-p` (print mode) : `claude -p "<prompt>"`
- Aucune clé API à gérer — utilise l'abonnement Claude de l'utilisateur
- Vérifie que `claude` est disponible dans le PATH au démarrage

```kotlin
// Exemple ClaudeCodeClient
val process = ProcessBuilder("claude", "-p", prompt)
    .redirectErrorStream(true)
    .start()
val response = process.inputStream.bufferedReader().readText()
```

L'utilisateur choisit le client actif dans Settings → ContextCrawler → LLM Backend.

---

## Ce qui est hors scope V1 (ne pas implémenter)

- Multi-langage (Kotlin source à analyser) — architecture prête, un seul adapter
- Stratégies `shallow` et `git-diff` — créer les fichiers placeholder vides
- Pipeline LLM avancé (retries, fallback, streaming)
- Mode "fix the test" (feedback boucle avec erreurs de compilation)
- `OpenAiClient` et `OllamaClient` — interfaces seulement, pas d'implémentation
- `ClaudeCodeClient` streaming — V1 utilise le mode print `-p` uniquement

---

## Si une spec est ambiguë

Pose la question **avant** d'écrire le code. Ne fais pas d'hypothèse
silencieuse sur le comportement de l'algorithme récursif ou sur le
format du prompt — les deux sont précisément définis dans les specs.
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

## Convention de langue

| Élément                                           | Langue   |
|---------------------------------------------------|----------|
| Code Kotlin (classes, méthodes, variables)        | Anglais  |
| Fichiers (noms, packages)                         | Anglais  |
| Commentaires inline dans le code Kotlin           | Français |
| Code Java généré (classes, méthodes)              | Anglais  |
| Commentaires dans les tests générés               | Interdits (zéro commentaire) |
| Specs (`.md`)                                     | Français |
| Messages d'erreur visibles par l'utilisateur      | Français |
| Logs techniques                                   | Anglais  |
| Réponses de Claude Code dans le chat              | Français |

Cette séparation découple la convention API (anglais, lisible par les outils
et toute la communauté) du contenu éditorial (français, langue de travail).
Le "quoi" est en anglais, le "pourquoi" est en français.

---

## Specs — LIS CES FICHIERS EN PREMIER, dans cet ordre

```
specs/ARCHITECTURE.md      → architecture hexagonale, modèle ContextTree,
                             couches, roadmap d'implémentation (section 12)
specs/STRATEGIE.md         → algorithme complet de récupération de contexte
                             0bis : glossaire des termes
                             2.1  : taxonomie des modes (Mode enum)
                             3.x  : pseudo-code par mode
                             4.x  : Bloc 7 — protocole d'init (avec diagramme 4.0)
                             6    : format de rendu du ContextTree (layer CONTEXT)
                             7    : APIs PSI clés et pièges
                             8bis : gestion des cas dégradés
                             9.x  : exemples concrets de référence
specs/PROMPT_FORMAT.md     → wrapper LLM-agnostic du prompt final
                             [SYSTEM][CONTEXT][USER_ENRICHMENT][CONSTRAINTS][INSTRUCTION]
                             Templates par provider (Claude, Qwen, OpenAI)
                             Cas spéciaux (UNTESTABLE_AS_IS, tronqué)
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

Un mini projet Java versionné dans `test-project/` représente les cas de
référence de `STRATEGIE.md` sous forme de vraies classes Java analysées
via PSI.

### Pré-requis : test-project doit compiler

Le projet doit avoir son propre `build.gradle.kts` avec les dépendances
nécessaires. **Sans ça, PSI ne résoudra aucun type et tous les tests
échoueront silencieusement** — l'éditeur affichera des erreurs rouges
partout et `findClass(fqn)` retournera systématiquement `null`.

### Structure
```
test-project/
├── build.gradle.kts                   ← OBLIGATOIRE
├── settings.gradle.kts
├── README.md                          ← comment lancer chaque cas manuellement
├── EXPECTED_PROMPTS.md                ← oracle de validation (voir ci-dessous)
└── src/main/java/com/testproject/
    ├── case00_baseline/               ← BASELINE : service @Autowired + 1 repo
    │                                     valide le pipeline complet hors Bloc 7
    ├── case91/                        ← @PostConstruct prioritaire (STRATEGIE §9.1)
    ├── case92/                        ← Méthode publique avec arguments (§9.2)
    ├── case93/                        ← Chaîne transitive (§9.3)
    ├── case94/                        ← Auto-init dans methodeCible (§9.4)
    ├── case95/                        ← UNTESTABLE_AS_IS (§9.5)
    └── case96_degraded/               ← Cas dégradés (STRATEGIE §8bis)
                                          imports cassés, types manquants, etc.
```

### Dépendances minimum dans `build.gradle.kts`

```
- org.springframework:spring-context              (pour @Service, @Repository...)
- jakarta.annotation:jakarta.annotation-api       (pour @PostConstruct)
- org.projectlombok:lombok                        (annotations Lombok)
- jakarta.persistence:jakarta.persistence-api     (entités JPA)
- org.springframework.data:spring-data-commons    (CrudRepository...)
```

Chaque package `caseXX/` doit être **réaliste** — inclure suffisamment de
classes (DTOs, interfaces, super-classes, enums, repositories) pour que
la stratégie récursive ait un vrai graphe de dépendances à crawler.
Ne pas se limiter à une seule classe par cas.

### Marquage de la méthode cible

Chaque méthode à analyser doit être annotée par un commentaire :
```java
// @TestTarget
public OrderDTO calculate(Long orderId) { ... }
```
Ce marqueur permet d'identifier sans ambiguïté la méthode à utiliser
pour chaque cas, et facilitera l'automatisation future (V1.5).

### Fichier EXPECTED_PROMPTS.md — format

Crée `test-project/EXPECTED_PROMPTS.md` avec une entrée par cas suivant
ce format **strict** :

```markdown
## case91 — @PostConstruct prioritaire

**Méthode cible** : `OrderService#calculate(Long orderId)`

**Assertions sur le ContextTree produit** :
- [ ] `targetMethod.signature.nom == "calculate"`
- [ ] `champs` contient `{nom: "cache", type: "DiscountCache"}`
- [ ] `protocoleInit["cache"].strategieRecommandee` est `CALL_POST_CONSTRUCT`
- [ ] `protocoleInit["cache"].strategieRecommandl'étapeee.methode.nom == "init"`
- [ ] `mocks` contient `DiscountRepository`
- [ ] `mocks` ne contient PAS `DiscountCache` (auto-construit)

**Assertions sur le prompt rendu (layer CONTEXT)** :
- [ ] La section CONTEXT contient `## Champ \`cache\``
- [ ] Cette section indique `Stratégie : CALL_POST_CONSTRUCT`
- [ ] Le code suggéré contient `sut.init();`
- [ ] La section `# Mocks` liste `DiscountRepository`
- [ ] La section `# Mocks` ne liste PAS `DiscountCache`
```

Ces assertions sont des **cases à cocher binaires** — pas de comparaison
textuelle. Si une assertion échoue, l'écart est explicite et reproductible.

### Workflow d'auto-évaluation aux étapes 7 et 8

#### Mode manuel (V1)
1. Lance `./gradlew runIde`
2. Dans le sandbox IntelliJ qui s'ouvre, ouvre `test-project/` comme projet Java
3. **Vérifie que le projet compile sans erreur rouge dans l'éditeur**
   (sinon PSI échouera silencieusement)
4. Pour chaque cas (00, 91 → 96) :
   a. Place le curseur sur la méthode marquée `// @TestTarget`
   b. Déclenche l'action ContextCrawler (Alt+G)
   c. Colle le prompt généré dans le terminal
   d. Compare avec les assertions de `EXPECTED_PROMPTS.md` pour ce cas
5. Coche les assertions validées, identifie les écarts
6. Corrige le code du plugin, relance `runIde`, itère

#### Critères de validation par étape

- **Étape 7 — Mode COPY** : au moins `case00 + case92 + case93` doivent
  passer **100%** de leurs assertions. Ce sont les 3 cas du chemin critique :
    - `case00` valide le pipeline complet (extraction baseline)
    - `case92` valide le Bloc 7 typique (méthode publique)
    - `case93` valide le BFS transitif (cas le plus complexe)
- **Étape 8 — Mode LLM_CALL** : tous les cas 91-95 doivent passer 80%
  de leurs assertions. `case96_degraded` doit produire un message d'erreur
  clair sans crasher le plugin.

### Critère absolu

**Ne jamais passer à l'étape suivante sans avoir validé `case00 + case92 + case93`
sur le vrai code Java de `test-project/`.** Ces 3 cas couvrent le chemin
critique du plugin et garantissent qu'aucune régression silencieuse n'est
introduite.

---

## Règle de travail — une étape à la fois

Implémente **une étape à la fois** selon la roadmap de `ARCHITECTURE.md`
section 12. **Arrête-toi après chaque étape, compile, lance les tests,
et attends ma validation avant de passer à la suivante.**

### Étape 1 — Squelette `core/` (interfaces uniquement) + test-project
Crée toutes les interfaces et data classes du `core/` :
`ContextNode`, `ContextTree`, `NodeKind`, `CodeIntrospector`,
`ContextStrategy`, `StrategyInput`, `StrategyRegistry`, `Budget`,
`ClassClassifier`, `DefaultClassifier` (stub vide),
`LlmClient`, `PromptRequest`, `PromptResponse`,
`PromptStage`, `PromptContext`, `PromptBuilder`,
`ConfigSource`, `LayeredConfig`, `ContextExtractorConfig`.
**Pas d'implémentation PSI. Pas d'adapter. Pas d'IDE glue.**

**Comportement attendu pour les classes "stub" à l'étape 1** :
- `DefaultClassifier.classify()` → `throw NotImplementedError("Implemented at step 4")`
- Toute autre classe avec corps de méthode non trivial → même pattern
- L'objectif : la compilation passe, mais aucun appel runtime ne réussit

**Nettoyage du projet existant** :
- Supprimer `MyToolWindowFactory.kt` et `MyMessageBundle.kt` s'ils existent
  (squelettes du wizard JetBrains qui ne suivent pas l'arborescence cible)

**Convention de nommage** : utiliser exclusivement la table de traduction
FR → EN définie dans **ARCHITECTURE.md §3bis**. Si un terme français
de STRATEGIE.md n'y figure pas, l'ajouter à la table avant de coder.

Crée également `test-project/` avec :
- `build.gradle.kts` + `settings.gradle.kts` qui compilent sans erreur
- Les dépendances minimum (spring-context, jakarta.annotation, lombok
  avec annotationProcessor, jakarta.persistence, spring-data-commons)
- `case00_baseline/` — service @Autowired + 1 repo + 1 DTO (3-4 classes)
- `case91/` — @PostConstruct prioritaire (4-6 classes)
- `case92/` — Méthode publique avec arguments (4-6 classes)
- `case93/` — Chaîne transitive (6-8 classes — cas critique)
- `case94/` — Auto-init dans methodeCible (3-5 classes)
- `case95/` — UNTESTABLE_AS_IS (3-4 classes)
- `case96_degraded/` — Cas dégradés §8bis (2-3 classes)
  Le code doit COMPILER mais contenir des constructions limites pour PSI
  (générique non bornable, référence circulaire entre 2 classes, etc.)
- `EXPECTED_PROMPTS.md` avec les assertions binaires pour chaque cas
- `README.md` qui explique comment lancer chaque cas manuellement

**Règle qualitative pour chaque case** : au moins 1 DTO + 1 dépendance
à mocker, sinon le test ne valide pas le pipeline complet.

Ajoute aussi un test ArchUnit qui vérifie que `core/` n'importe jamais
de classes `com.intellij..` :
```kotlin
@Test
fun `core has no IntelliJ dependencies`() {
    Classes.that().resideInAPackage("..core..")
        .should().notDependOnClassesThat().resideInAPackage("com.intellij..")
        .check(importedClasses)
}
```

Résultat attendu :
- `./gradlew compileKotlin` passe sans erreur
- `./gradlew :test-project:compileJava` passe sans erreur (PSI pourra résoudre)
- Le test ArchUnit passe

### Étape 2 — FakeIntrospector + tests unitaires `core/`
Implémente `FakeIntrospector` et écris les tests JUnit 5 pour :
- `case00_baseline` (extraction baseline sans Bloc 7)
- Les 5 cas des sections 9.1-9.5
- Au moins 1 cas dégradé (type non résolvable de §8bis)

Valide que le design du `core/` est testable sans PSI réel.
Résultat attendu : `./gradlew test` passe, tous les tests verts.

### Étape 3 — Adapter PSI (`JavaPsiIntrospector`)
Implémente `JavaPsiIntrospector` qui satisfait l'interface `CodeIntrospector`.
Utilise uniquement les APIs PSI listées dans `STRATEGIE.md` section 7.1.
Respecte les pièges section 7.2 (toujours `getCanonicalText()`, ReadAction, etc.).

**Prérequis ajouté à l'étape 3** :
Ajouter `listFields(cls: ClassDescriptor): List<ClassField>` au port
`CodeIntrospector` avant l'implémentation PSI. `ClassField` porte au minimum
`name, type, visibility, annotations, declaredIn`. La méthode est requise par
STRATEGIE.md §3.1 BLOC 1 (« Champs de la SUT et héritage ») dès l'étape 4 ;
ajouter au port maintenant évite un refactor de FakeIntrospector + adapter PSI
plus tard.

**Séparation test / integrationTest** :
- `src/test/kotlin/` — JUnit 5 pur, pas de classes IntelliJ TestCase. Source
  set standard `test`, lancé par `./gradlew test`.
- `src/integrationTest/kotlin/` — JUnit 4 + IntelliJ TestCase. Source set custom
  `integrationTest` avec `testFramework(Platform)` ajouté via le paramètre
  `configurationName = "integrationTestImplementation"`. Lancé par
  `./gradlew integrationTest`.
- Raison de la séparation : `testFramework(Platform)` embarque un
  `LauncherSessionListener` (`com.intellij.tests.JUnit5TestSessionListener`)
  qui plante au démarrage du launcher JUnit 5 — donc on l'isole du source
  set qui utilise `useJUnitPlatform()`.

**Limite connue de l'étape 3** :
Les classes IntelliJ test framework du bundle IDE (`testFramework.jar` qui
porte `LightJavaCodeInsightFixtureTestCase`) sont injectées via des transforms
d'artefact attachées au source set `test` standard. Ces transforms ne se
propagent pas à un source set custom via `compileClasspath += sourceSets["test"].compileClasspath`
ni via `extendsFrom(testCompileOnly)`. L'API publique du plugin v2 ne semble
pas exposer de `configurationName` sur `intellijIdea(...)` / `bundledPlugin(...)`,
donc impossible de cibler `integrationTestImplementation` directement.

→ Le wiring complet est reporté à une session dédiée. En attendant, la
validation de `JavaPsiIntrospector` se fait manuellement via `./gradlew runIde`
sur `test-project/` (workflow déjà documenté dans `test-project/README.md`).
Les tests prévus (`resolveClass`, `listFields`, `listSuperClasses`,
`listAnnotations`, mapping wildcards) sont décrits en pseudo-code dans
`src/integrationTest/kotlin/com/contextextractor/adapters/psi/JavaPsiIntrospectorTest.kt`,
prêts à transposer dès le wiring résolu.

Résultat attendu :
- `./gradlew compileKotlin` passe (adapter PSI compile).
- `./gradlew test` passe (les 42 tests fakes + ArchUnit migrés vers le
  nouveau port avec `listFields()` restent verts).
- `./gradlew integrationTest` passe (stub minimal qui valide la
  compilation du source set integrationTest et l'accès à PsiTypeMapper).

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
3. Vérifie que le projet compile sans erreur rouge dans l'éditeur
4. Demande à l'utilisateur de tester chaque cas (00, 91 à 96)
   et de coller le prompt généré
5. Compare avec les **assertions binaires** de `test-project/EXPECTED_PROMPTS.md`
6. Itère jusqu'à validation des cas critiques

Résultat attendu : `case00 + case92 + case93` passent **100%** de leurs
assertions (chemin critique). Les autres cas sont au moins fonctionnels
(le plugin ne crashe pas, le prompt produit a la bonne structure).

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

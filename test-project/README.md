# test-project

Projet Java de référence pour l'auto-évaluation manuelle de ContextCrawler.

Chaque package `caseXX/` représente un cas concret de `STRATEGIE.md` sous
forme de vraies classes Java analysées via PSI dans le sandbox IntelliJ.

## Pré-requis

Le projet doit **compiler** sans erreur rouge dans l'éditeur, sinon PSI
résoudra `null` pour les types et tous les tests échoueront silencieusement.

## Compilation

Depuis la racine du repo :

```
./gradlew :test-project:compileJava
```

## Marqueur de méthode cible

Chaque méthode à analyser est annotée par un commentaire `// @TestTarget`
juste au-dessus de sa signature. C'est le marqueur que l'utilisateur doit
viser avec son curseur avant de déclencher l'action `Alt+G` du plugin.

## Workflow manuel (V1)

1. À la racine du plugin : `./gradlew runIde`
2. Dans le sandbox IntelliJ qui s'ouvre : `File → Open` puis sélectionner
   `test-project/` (ou ouvrir comme projet Gradle).
3. Vérifier qu'aucune classe n'apparaît avec une erreur rouge dans
   l'éditeur (sinon PSI ne pourra pas résoudre les types correctement).
4. Pour chaque case :
   1. Ouvrir le `OrderService.java` (ou `LegacyService.java` pour case96)
   2. Placer le curseur sur la méthode marquée `// @TestTarget`
   3. Déclencher l'action ContextCrawler (`Alt+G`)
   4. Coller le prompt généré dans le terminal
   5. Comparer avec les assertions de `EXPECTED_PROMPTS.md` pour ce case
5. Cocher les assertions validées dans `EXPECTED_PROMPTS.md` ; identifier
   les écarts.
6. Corriger le code du plugin, relancer `runIde`, itérer.

## Critères de validation par étape

- **Étape 7 (Mode COPY)** — `case00 + case92 + case93` doivent passer
  **100%** de leurs assertions. Chemin critique :
  - `case00` valide le pipeline complet (extraction baseline)
  - `case92` valide le Bloc 7 typique (méthode publique avec arguments)
  - `case93` valide le BFS transitif (cas le plus complexe)
- **Étape 8 (Mode LLM_CALL)** — tous les cases 91-95 doivent passer
  **80%** de leurs assertions. `case96_degraded` doit produire un message
  d'erreur clair sans crasher le plugin.

## Cartographie cas → section STRATEGIE

| Case            | Référence dans STRATEGIE.md       | Stratégie attendue           |
|-----------------|-----------------------------------|------------------------------|
| `case00`        | Baseline (hors Bloc 7)            | `MOCKITO_INJECT_MOCKS`       |
| `case91`        | §9.1 — @PostConstruct prioritaire | `CALL_POST_CONSTRUCT`        |
| `case92`        | §9.2 — Méthode publique + args    | `CALL_PUBLIC_WITH_ARGS`      |
| `case93`        | §9.3 — Chaîne transitive          | `CALL_PUBLIC_TRANSITIVE`     |
| `case94`        | §9.4 — Auto-init dans methodeCible| `IMPLICIT`                   |
| `case95`        | §9.5 — Cas non-testable           | `UNTESTABLE_AS_IS`           |
| `case96_degraded`| §8bis — Cas dégradés (cycle, wildcard) | (non testable nominalement) |

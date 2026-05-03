# Oracle de validation — assertions binaires par case

Ce fichier est l'**oracle** que le plugin doit satisfaire. Chaque assertion est
une case à cocher binaire (passe / échoue). Aucune comparaison textuelle floue.

Format : référencé par CLAUDE.md (section "Fichier EXPECTED_PROMPTS.md — format").

Étape de validation :
- **Étape 7 (Mode COPY)** — `case00 + case92 + case93` doivent passer **100%**.
- **Étape 8 (Mode LLM_CALL)** — `case91-95` doivent passer **80%**, `case96_degraded`
  produit un message d'erreur clair sans crasher le plugin.

---

## case00 — Baseline (service @Autowired + 1 repo + 1 DTO)

**Méthode cible** : `OrderService#findOrder(String reference)`

**Assertions sur le ContextTree produit** :
- [ ] `targetMethod.signature.name == "findOrder"`
- [ ] `targetMethod.signature.parameters` contient un `Parameter(name="reference", type="String")`
- [ ] `fields` contient `{name: "repository", type: "OrderRepository"}`
- [ ] `initProtocol["repository"].strategieRecommandee` est `MOCKITO_INJECT_MOCKS`
- [ ] `mocks` contient `OrderRepository`
- [ ] `dataStructures` contient `OrderEntity` (lu via `repository.findByReference`)
- [ ] `dataStructures` contient `OrderDTO` (instancié dans le corps)
- [ ] `targetMethod.branches` contient une condition `entity == null`
- [ ] `targetMethod.exceptionsLancees` est vide

**Assertions sur le prompt rendu (layer CONTEXT)** :
- [ ] La section `# Classe sous test` mentionne `OrderService` avec annotation `@Service`
- [ ] La section `# Méthode cible` indique la signature `OrderDTO findOrder(String reference)`
- [ ] La section `# Mocks` liste `OrderRepository`
- [ ] La section `# Mocks` liste la signature `OrderEntity findByReference(String reference)`
- [ ] La section `# Structures de données à construire` liste `OrderEntity` et `OrderDTO`
- [ ] La section `# Structures de données à construire` indique le pattern `SETTER_BASED` pour `OrderEntity`
- [ ] La section `# Structures de données à construire` indique le pattern `CONSTRUCTOR` pour `OrderDTO`
- [ ] Aucune mention de `OrderService` lui-même dans la section Mocks (c'est le SUT)

---

## case91 — @PostConstruct prioritaire

**Méthode cible** : `OrderService#calculate(Long orderId, String discountCode, double rawAmount)`

**Assertions sur le ContextTree produit** :
- [ ] `targetMethod.signature.name == "calculate"`
- [ ] `fields` contient `{name: "cache", type: "DiscountCache"}`
- [ ] `fields` contient `{name: "repository", type: "DiscountRepository"}`
- [ ] `initProtocol["cache"].strategieRecommandee` est `CALL_POST_CONSTRUCT`
- [ ] `initProtocol["cache"].strategieRecommandee.methode.name == "init"`
- [ ] `initProtocol["repository"].strategieRecommandee` est `MOCKITO_INJECT_MOCKS`
- [ ] `mocks` contient `DiscountRepository`
- [ ] `mocks` ne contient PAS `DiscountCache` (auto-construit)
- [ ] `dataStructures` contient `DiscountEntity`

**Assertions sur le prompt rendu (layer CONTEXT)** :
- [ ] La section CONTEXT contient `## Champ \`cache\``
- [ ] Cette section indique `Stratégie : CALL_POST_CONSTRUCT`
- [ ] Le code suggéré contient `sut.init();`
- [ ] La section `# Mocks` liste `DiscountRepository`
- [ ] La section `# Mocks` ne liste PAS `DiscountCache`
- [ ] La section `# Structures de données à construire` liste `DiscountEntity`

---

## case92 — Méthode publique avec arguments

**Méthode cible** : `OrderService#calculate(OrderRequest request)`

**Assertions sur le ContextTree produit** :
- [ ] `targetMethod.signature.name == "calculate"`
- [ ] `targetMethod.signature.parameters` contient `Parameter(name="request", type="OrderRequest")`
- [ ] `fields` contient `{name: "config", type: "Config"}`
- [ ] `fields` contient `{name: "pricingGateway", type: "PricingGateway"}`
- [ ] `initProtocol["config"].strategieRecommandee` est `CALL_PUBLIC_WITH_ARGS`
- [ ] `initProtocol["config"].strategieRecommandee.methode.name == "configure"`
- [ ] `initProtocol["config"].strategieRecommandee.args` contient un paramètre `int` et un `String`
- [ ] `initProtocol["pricingGateway"].strategieRecommandee` est `MOCKITO_INJECT_MOCKS`
- [ ] `mocks` contient `PricingGateway`
- [ ] `mocks` ne contient PAS `Config`
- [ ] `dataStructures` contient `OrderRequest` et `OrderDTO` et `Config`
- [ ] `targetMethod.exceptionsLancees` contient `IllegalArgumentException`

**Assertions sur le prompt rendu (layer CONTEXT)** :
- [ ] La section CONTEXT contient `## Champ \`config\``
- [ ] Cette section indique `Stratégie : CALL_PUBLIC_WITH_ARGS`
- [ ] Le code suggéré contient `sut.configure(` (avec arguments à fournir)
- [ ] La section `# Mocks` liste `PricingGateway`
- [ ] La section `# Méthode cible` indique l'exception `IllegalArgumentException` lancée

---

## case93 — Chaîne transitive (cas critique)

**Méthode cible** : `OrderService#calculate(OrderRequest request)`

**Assertions sur le ContextTree produit** :
- [ ] `targetMethod.signature.name == "calculate"`
- [ ] `hierarchy` contient `OrderService` ET `AbstractCacheService` (super-classe utilisateur)
- [ ] `fields` contient `{name: "cache", type: "Cache"}` (déclaré dans la super-classe)
- [ ] `fields` contient `{name: "loader", type: "Loader"}`
- [ ] `initProtocol["cache"].strategieRecommandee` est `CALL_PUBLIC_TRANSITIVE`
- [ ] `initProtocol["cache"].strategieRecommandee.pointEntree.name == "start"`
- [ ] `initProtocol["cache"].strategieRecommandee.chaineAppels` contient `["start", "startInternal", "warmup", "buildCache"]` dans cet ordre
- [ ] `initProtocol["cache"].strategieRecommandee.stubsRequis` contient un appel à `Loader.load`
- [ ] `initProtocol["loader"].strategieRecommandee` est `MOCKITO_INJECT_MOCKS`
- [ ] `mocks` contient `Loader`
- [ ] `mocks` ne contient PAS `Cache` (auto-construit)
- [ ] `dataStructures` contient `CacheEntry`, `OrderRequest`, `OrderDTO`

**Assertions sur le prompt rendu (layer CONTEXT)** :
- [ ] La section CONTEXT contient `## Champ \`cache\``
- [ ] Cette section indique `Stratégie : CALL_PUBLIC_TRANSITIVE`
- [ ] Le commentaire de chaîne mentionne `start → startInternal → warmup → buildCache`
- [ ] Le code suggéré contient un `when(loader.load()).thenReturn(...)` AVANT `sut.start();`
- [ ] La section `# Mocks` liste `Loader`
- [ ] La section `# Sous-méthodes internes` liste `startInternal`, `warmup`, `buildCache`

---

## case94 — Auto-init dans methodeCible

**Méthode cible** : `OrderService#calculate(String sku)`

**Assertions sur le ContextTree produit** :
- [ ] `targetMethod.signature.name == "calculate"`
- [ ] `fields` contient `{name: "cache", type: "Cache"}`
- [ ] `fields` contient `{name: "priceProvider", type: "PriceProvider"}`
- [ ] `initProtocol["cache"].strategieRecommandee` est `IMPLICIT`
- [ ] `initProtocol["priceProvider"].strategieRecommandee` est `MOCKITO_INJECT_MOCKS`
- [ ] `mocks` contient `PriceProvider`
- [ ] `mocks` ne contient PAS `Cache`
- [ ] `dataStructures` contient `OrderDTO`
- [ ] `targetMethod.branches` contient une condition basée sur `cache.has(sku)`

**Assertions sur le prompt rendu (layer CONTEXT)** :
- [ ] La section CONTEXT contient `## Champ \`cache\``
- [ ] Cette section indique `Stratégie : IMPLICIT`
- [ ] Le commentaire mentionne que `cache` est auto-initialisé via `primeIfNeeded()` dans la méthode cible
- [ ] La section `# Mocks` liste `PriceProvider`
- [ ] Aucune ligne `sut.<method>()` n'est suggérée dans `@BeforeEach` pour `cache`

---

## case95 — UNTESTABLE_AS_IS (refactor requis)

**Méthode cible** : `OrderService#calculate(String key)`

**Assertions sur le ContextTree produit** :
- [ ] `targetMethod.signature.name == "calculate"`
- [ ] `fields` contient `{name: "cache", type: "Cache"}`
- [ ] `initProtocol["cache"].strategieRecommandee` est `UNTESTABLE_AS_IS`
- [ ] `initProtocol["cache"].strategieRecommandee.raison` mentionne `primeCache` ET le mot "privée" ou "private"
- [ ] `initProtocol["cache"].strategieRecommandee.pistesRefacto` contient au moins 2 entrées
- [ ] `testabilityDiagnostic.testable == false`
- [ ] `testabilityDiagnostic.champsBloquants` contient `"cache"`

**Assertions sur le prompt rendu (layer CONTEXT)** :
- [ ] La section CONTEXT contient `## Champ \`cache\``
- [ ] Cette section indique `Stratégie : UNTESTABLE_AS_IS`
- [ ] Une raison textuelle est fournie
- [ ] Au moins 2 pistes de refactoring sont listées
- [ ] Le prompt indique au LLM de générer un test marqué `_TODO_untestable` avec `fail(...)`

---

## case96_degraded — Cas dégradés (référence circulaire + wildcards)

**Méthode cible** : `LegacyService#process(String tag)`

**Assertions sur le ContextTree produit** :
- [ ] `targetMethod.signature.name == "process"`
- [ ] `fields` contient `{name: "repository", type: "LegacyRepository"}`
- [ ] `fields` contient `{name: "rootNode", type: "NodeA<String>"}` ou un type équivalent
- [ ] `mocks` contient `LegacyRepository`
- [ ] `dataStructures` contient `NodeA` et `NodeB` (la récursion détecte le cycle et stoppe)
- [ ] `dataStructures[NodeA]` est marqué pour la 1re visite ; la 2e visite (depuis NodeB) ne récure pas
- [ ] Aucune StackOverflowError ou boucle infinie pendant l'extraction
- [ ] Le ContextTree est produit, même partiellement, sans crash

**Assertions sur le prompt rendu (layer CONTEXT)** :
- [ ] Le prompt est généré sans crash
- [ ] La section CONTEXT mentionne `LegacyRepository` dans Mocks
- [ ] Les types wildcards (`Map<?, ?>`) sont rendus avec `<unresolved>` ou `Object` (STRATEGIE.md §8bis.1)
- [ ] Le rendu indique `NodeB (déjà décrit ci-dessus)` ou équivalent pour la 2e visite (STRATEGIE.md §8bis.5)

**Critère d'étape 8 spécifique à ce case** :
- [ ] Le plugin AFFICHE un message d'erreur clair si le contexte ne peut pas être pleinement extrait
- [ ] Le plugin NE CRASHE PAS

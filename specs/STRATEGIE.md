# Stratégie de récupération de contexte — version finale

**Cible** : plugin IntelliJ (PSI Java) qui produit un prompt minimal et suffisant pour qu'un LLM génère un test JUnit 5 / Mockito / AssertJ qui **compile** et idéalement **passe** sur le chemin nominal.

---

## 0. Cadrage et contraintes

```markdown
# Type de test cible
Test UNITAIRE pur, pas d'intégration.
- Pas de @SpringBootTest, @WebMvcTest, @DataJpaTest, @DataMongoTest.
- Pas de chargement de contexte Spring.
- Pas d'appel réseau, base de données, filesystem.
- AUCUNE reflection dans le code de test :
  pas de ReflectionTestUtils.setField, pas de ReflectionTestUtils.invokeMethod,
  pas de Field.setAccessible(true).
- Mockito @InjectMocks autorisé (injection framework, pas reflection utilisateur).
- Le seul critère obligatoire : le fichier généré doit compiler.
- Toute dépendance non triviale est mockée.

# Stack imposée
- JUnit 5 (`org.junit.jupiter.api.*`)
- Mockito (`@Mock`, `@InjectMocks`, `@ExtendWith(MockitoExtension.class)`,
  `when(...).thenReturn(...)`, `verify(...)`, `mockStatic(...)`)
- AssertJ (`assertThat(...).isEqualTo/isNull/hasFieldOrPropertyWithValue/...`)
```

---

## 0bis. Glossaire

| Terme | Définition |
|-------|------------|
| **SUT** | System Under Test — la classe contenant la méthode à tester |
| **methodeCible** | La méthode précise à tester dans le SUT |
| **HierarchieComplete** | SUT + toutes ses super-classes utilisateur (jusqu'à `Object` ou exclusion) |
| **ChampsActifs** | Champs du SUT effectivement lus ou écrits dans le corps de `methodeCible` |
| **ContextTree** | Modèle de contexte unifié produit par la stratégie (voir ARCHITECTURE.md §4) |
| **CleVisite** | Tuple `(Mode, Classe, Méthode?)` utilisé comme clé du registre anti-cycle |
| **callGraph** | Graphe d'appels intra-SUT inverse : `{méthode → ses appelants}` |
| **Bloc 7** | Section de SUT_BOOTSTRAP qui détermine le protocole d'init de chaque champ |
| **Source d'init** | Endroit où un champ peut être assigné (constructeur, setter, méthode, etc.) |
| **StrategieInit** | Décision finale sur comment initialiser un champ dans le test généré |
| **Budget** | Limites quantitatives (profondeur, nb mocks, nb DTOs, tokens) imposées à la récursion |
| **UNTESTABLE_AS_IS** | Verdict pour un champ qui ne peut pas être initialisé sans refactorer le SUT |

---

## 1. Principes directeurs

1. **Le mode est une décision contextuelle**, pas un attribut intrinsèque du type. Un même type peut être mocké dans un contexte et instancié dans un autre.
2. **Le registre de visites est typé** : `(mode, classe, méthode?)`. Pas de collision possible.
3. **Tout ce qui finit dans le prompt doit être utile au compilateur** : signature exacte, type qualifié, pattern de construction.
4. **Budget structurel uniquement** : `maxDepth` borne le BFS du graphe de références ; pas de cap de résultat sur les mocks/DTOs/internals (cf §2.3 et RAPPORT_CONTEXT §9 défaut #3).
5. **Pas de reflection** : si un champ privé n'est pas accessible par un chemin légitime, le rapport surface explicitement le problème (`UNTESTABLE_AS_IS`) avec pistes de refacto.
6. **Le prompt produit est paramétré** par la stack de test (JUnit 5 + Mockito + AssertJ) et par un **profil de tuning LLM** swappable (§6bis).
7. **Pipeline 2-pass deferred classification** (V1.2) : l'énumération du graphe de références (PASSE 1) est strictement séparée de la décision de mode (PASSE 2) et de la matérialisation (PASSE 3). Cf §3.0.

---

## 2. Taxonomie des modes

```kotlin
enum class Mode {
    SUT_BOOTSTRAP,      // point d'entrée : la classe testée
    INTERNAL_LOGIC,     // sous-méthode du SUT à explorer pour comprendre la logique
    MOCK_EXTERNAL,      // dépendance à mocker — ne pas lire le corps
    DATA_STRUCTURE,     // DTO/Record/Builder à instancier
    SYSTEM_IGNORE,      // primitif, java.*, javax.*, etc.
    FUNCTIONAL_LAMBDA,  // SAM type java.util.function.* — fournir lambda
    CONTAINER,          // Optional, CompletableFuture, Mono, Flux
    COLLECTION,         // List/Set/Map utilisateur
    STATIC_UTILITY      // classe utilitaire 100% statique
}
```

### 2.1 Fonction Classifier (contextuelle)

```text
Classifier(type, contexteAppelant) → Mode
  // contexteAppelant ∈ { ROOT_SUT, FIELD_OF_SUT, PARAM_OF_METHOD,
  //                      RETURN_OF_METHOD, FIELD_OF_DTO, GENERIC_ARG,
  //                      CALL_TARGET, INSTANTIATION }

  // 1. Cas d'arrêt système
  Si type.fqName commence par {java., javax., jakarta., kotlin., scala., sun., com.sun.}
     OU type est primitif/wrapper (int, Integer, String, Boolean, …)
     OU type ∈ {void, Void, Object}
     → SYSTEM_IGNORE

  // 2. Le SUT racine est toujours SUT
  Si contexteAppelant == ROOT_SUT
     → SUT_BOOTSTRAP

  // 3. SAM Java fonctionnel
  Si type est SAM ET dans java.util.function.*
     → FUNCTIONAL_LAMBDA

  // 4. Conteneurs async/optional
  Si type ∈ {CompletableFuture, Mono, Flux, Single, Observable, Optional}
     → CONTAINER

  // 5. Collections utilisateur
  Si type implémente java.util.Collection ou Map
     → COLLECTION

  // 6. Énumérations / sealed
  Si type est enum OU sealed
     → DATA_STRUCTURE  // pattern ENUM ou SEALED en Phase 1 du mode

  // 7. Classes 100% statiques
  Si type a uniquement des membres statiques ET constructeur privé
     → STATIC_UTILITY

  // 8. Interface ou classe abstraite (hors SAM déjà capté)
  Si type est interface OU abstract
     → MOCK_EXTERNAL

  // 9. Annotations Spring de service (sauf SUT racine)
  Si type a annotation ∈ {@Service, @Repository, @Component, @RestClient, @FeignClient}
     ET contexteAppelant ≠ ROOT_SUT
     → MOCK_EXTERNAL

  // 10. DTO / POJO / Record
  Si type est Record
     OU type a annotation Lombok ∈ {@Data, @Value, @Builder}
     OU type n'a aucune méthode "métier" (uniquement getters/setters/equals/hashCode/toString)
     → DATA_STRUCTURE

  // 11. Classe interne au SUT (pour exploration)
  Si contexteAppelant == CALL_TARGET ET type ∈ HierarchieComplete(SUT)
     → INTERNAL_LOGIC

  // 12. Défaut sécuritaire
  → MOCK_EXTERNAL
```

### 2.2 Registre de visites unifié

```kotlin
data class CleVisite(
    val mode    : Mode,
    val classe  : String,        // nom qualifié
    val methode : String?        // null pour DATA_STRUCTURE, SUT_BOOTSTRAP root, etc.
)
```

### 2.3 Budget

**Refactor V1.2 (Phase 4)** : les caps de résultat `nbDtoMax`, `nbMocksMax`,
`nbInternesMax` ont été supprimés. Cf RAPPORT_CONTEXT §9.7 pour la
motivation (Bug A/I/M/DD générés par l'éviction LFU réactive).

```kotlin
data class Budget(
    val maxDepth           : Int = 6,      // profondeur BFS ReferenceGraphBuilder
    val maxInitDepth       : Int = 2,      // chaînes d'init transitives (BLOC 7)
    val maxGraphDepth      : Int = 4,      // remontée callGraph intra-SUT
    val maxEstimatedTokens : Int = 50_000  // coupure prompt avant troncature
)
```

**Sémantique V1.2** :
- `maxDepth` borne le **crawl** PASSE 1 (pas le résultat). Tout type
  atteignable depuis target en ≤ maxDepth sauts est conservé.
- Les autres champs sont purement structurels (profondeurs BFS, taille du
  prompt final).
- Aucune éviction réactive — un mock essentiel ne peut plus être perdu par
  saturation d'un cap.

Dépassement (typiquement `maxDepth` atteint sur une branche profonde) →
marquer `resultat.tronque = true` et logger la raison dans
`truncationReasons` (consommé par la layer CONSTRAINTS section
« # Truncated context »).

---

## 3. Pseudo-code par mode

### 3.0 Pipeline V1.2 — 2-pass deferred classification

**Contexte historique** : la V1.1 implémentait un BLOC 6 single-pass eager —
chaque type rencontré était classifié immédiatement par sa shape (suffixe,
méthodes), avec promotions/évictions à la volée pour rattraper les erreurs
de contexte. Cette approche a produit 4 défauts structurels (cf
RAPPORT_CONTEXT §9.1) résolus par le refactor V1.2.

**Pipeline V1.2 actif** (cf `RecursiveDeepStrategy.extractCore`) :

```
BLOC 1-5 (inchangés — hiérarchie, target analysis, usefulFields, ctor, setters)
  ↓
PASSE 1 — ReferenceGraphBuilder
  • BFS exhaustif borné par budget.maxDepth
  • Énumère TOUTES les références (champs SUT, params target, returns,
    calls instance/static, instantiations) SANS classifier
  • Détecte les frontières framework (Bug N transitive) AU CALL SITE
  • Propage les sous-classes SEALED et les fields des DTOs Phase 4
  ↓
PASSE 2 — DefaultContextAwareClassifier
  • Consomme la `ClassReference` complète (tous ses usages agrégés)
  • 16 règles en ordre de priorité — décide UNE FOIS avec contexte complet
  • Règle 8 (instance call) raffinée : MOCK seulement si mutator/business
    (args ou non-pure-getter), sinon laisse les règles DTO décider
  • Pas de promotion post-hoc, pas de filtre rétroactif
  ↓
PASSE 3 — ResultMaterializer
  • Transforme (graphe + classifications) → MockInfo / DataStructureInfo
    / InternalLogic / StaticCallInfo
  • Pour chaque MOCK : agrège signatures appelées depuis `AsCallTarget`
  • Pour chaque DTO : détection pattern §3.4 + capture Phase 2/3
  • Pour chaque InternalLogic : body + analyse + call summaries
  • Frontières framework → entrée stubViaSpy=true sans lecture de body
  ↓
BLOC 7 (inchangé — init protocol, Bug CC préservé)
  ↓
filteredFields + reconcileMocksWithInitProtocol (cleanup prompt)
```

**Invariants V1.2** :
- Aucun type atteignable depuis target n'est silencieusement évincé (défaut #3 RAPPORT)
- Aucune décision de mode n'est prise avant énumération complète (défaut #2 RAPPORT)
- Le classifier raisonne sur le **contexte d'usage** (UsageSite), pas la shape (défaut #1 RAPPORT)
- L'init protocol exclut target des sources MethodInitializer (Bug CC, défaut #4 RAPPORT)

**§3.1-3.5 ci-dessous** décrivent les modes du classifier — la sémantique
est identique en V1.2, mais l'application n'est plus séquentielle eager :
les 16 règles du `DefaultContextAwareClassifier` agrègent tous les
contextes d'usage avant de produire un Mode unique par classe.

### 3.1 Mode `SUT_BOOTSTRAP`

```text
Recurse(SUT, methodeCible, visites, resultat, SUT_BOOTSTRAP, budget, profondeur)

  Si profondeur > budget.profondeurMax → tronquer ET retourner
  cle = CleVisite(SUT_BOOTSTRAP, SUT.fqName, methodeCible.nom)
  Si cle ∈ visites → retourner
  visites.ajouter(cle)

  // ── BLOC 1 : HIÉRARCHIE
  HierarchieComplete = []
  ClasseCourante = SUT
  Tant que ClasseCourante ∉ {Object, null, exclusions} :
    HierarchieComplete.ajouter({
      classe       : ClasseCourante,
      annotations  : ClasseCourante.annotations
    })
    Si ClasseCourante ≠ SUT ET ClasseCourante n'est pas java.*/javax.* :
      Capturer le constructeur compatible super(...) → resultat.hierarchie.constructeursSuper
    ClasseCourante = ClasseCourante.superClasse
  resultat.hierarchie = HierarchieComplete

  // ── BLOC 2 : ANALYSE COMPLÈTE DE methodeCible
  Corps = AST(methodeCible)
  resultat.methodeCible.signature          = capturerSignature(methodeCible)
  resultat.methodeCible.exceptionsDeclarees = methodeCible.throws
  // Texte source intégral du corps (accolades incluses) — frontière SUT_BOOTSTRAP
  // ouverte : la spec §3.3 ligne « STOP » fermera cette frontière côté
  // MOCK_EXTERNAL. Le LLM en a besoin pour reproduire fidèlement la logique
  // métier, en complément des éléments structurés (appelsInstance, branches…).
  resultat.methodeCible.corps              = corpsTexte(methodeCible)

  ChampsActifs       = {}
  AppelsInstance     = []   // a.b()
  AppelsStatiques    = []   // Foo.bar()
  Instanciations     = []   // new X()
  Lambdas            = []   // expressions lambda et method references
  ExceptionsLancees  = []
  ExceptionsCatchees = []
  BranchesCond       = []
  ConstantesCmp      = []
  SourcesIndéter     = []   // now(), UUID.random(), Math.random(), System.currentTimeMillis()
  VariablesLocales   = []

  Pour chaque nœud dans Corps :

    Si nœud == ThisFieldAccess(X) :
      ChampsActifs.ajouter(X)

    Si nœud == MethodCallExpression :
      Si nœud est appel statique :
        AppelsStatiques.ajouter({ classe, methode, argsTypes })
        Si nœud ∈ {LocalDateTime.now(), Instant.now(), UUID.randomUUID(),
                   Math.random(), System.currentTimeMillis()} :
          SourcesIndéter.ajouter(nœud)
      Sinon :
        // Décomposer les chaînes a.b().c().d() maillon par maillon
        Pour chaque maillon dans décomposerChaine(nœud) :
          AppelsInstance.ajouter({
            cibleType  : maillon.cible.typeResolu,
            cibleNom   : maillon.cibleSource,
            methode    : maillon.nom,
            argsTypes  : maillon.argsTypesResolus
          })

    Si nœud == NewExpression(Type, args) :
      Instanciations.ajouter({ type: Type, argsTypes: args.types })

    Si nœud == LocalVariableDeclaration(type, nom, init) :
      VariablesLocales.ajouter({ type, nom, init })

    Si nœud == LambdaExpression OU MethodReferenceExpression :
      Lambdas.ajouter({ typeFonctionnelAttendu: nœud.typeAttendu })

    Si nœud == ThrowStatement(new TypeException(args)) :
      ExceptionsLancees.ajouter({ type: TypeException, message: args.constSiPossible })

    Si nœud == TryStatement(corpsT, catches, finally) :
      Pour chaque catch dans catches :
        ExceptionsCatchees.ajouter({
          types       : catch.typesException,        // multi-catch supporté
          appelsCatch : extraireAppels(catch.corps)
        })

    Si nœud ∈ {IfStatement, SwitchExpression, ConditionalExpression} :
      BranchesCond.ajouter({
        type      : nœud.kind,
        condition : nœud.condition.toSource(),
        constantesUtilisees : extraireLitteraux(nœud.condition)
      })
      ConstantesCmp.ajouterTout(extraireLitteraux(nœud.condition))

  resultat.methodeCible.appelsInstance     = AppelsInstance
  resultat.methodeCible.appelsStatiques    = AppelsStatiques
  resultat.methodeCible.lambdasAttendues   = Lambdas
  resultat.methodeCible.exceptionsLancees  = ExceptionsLancees
  resultat.methodeCible.exceptionsCatchees = ExceptionsCatchees
  resultat.methodeCible.branches           = BranchesCond
  resultat.methodeCible.sourcesIndéter     = SourcesIndéter
  resultat.methodeCible.typeRetour         = resoudreType(methodeCible.typeRetour)

  // ── BLOC 3 : INVENTAIRE DES CHAMPS UTILES
  Pour chaque (Classe, _) dans HierarchieComplete :
    Pour chaque Champ de Classe :
      EstUtile =
           Champ.nom ∈ ChampsActifs
        OU Champ.annotations ∩ {@Autowired, @Inject, @Resource, @Value, @PersistenceContext} ≠ ∅
        OU (Champ est final ET initialisé via constructeur ET Lombok @RequiredArgsConstructor)
      Si EstUtile :
        resultat.champs.ajouter({
          nom         : Champ.nom,
          type        : resoudreType(Champ.type),
          visibilite  : Champ.visibilite,
          annotations : Champ.annotations,
          declareDans : Classe.fqName
        })

  // ── BLOC 4 : PROTOCOLE DE CONSTRUCTION DU SUT
  ConstructeurChoisi = choisirConstructeur(SUT)
  resultat.planInstanciation.constructeurChoisi = {
    parametres : ConstructeurChoisi.parametres,
    annotation : ConstructeurChoisi.annotationDeclencheuse
  }
  Si ConstructeurChoisi appelle super(...) :
    resultat.planInstanciation.valeursSuper = capturerArguments(super)

  // ── BLOC 5 : LEVIERS DE MUTATION
  Pour chaque (Classe, _) dans HierarchieComplete :
    Pour chaque Méthode de Classe :
      Si Méthode est setter et correspond à un champ utile :
        resultat.planInstanciation.setters.ajouter(...)
      Si Méthode annotée @PostConstruct :
        resultat.planInstanciation.postConstruct.ajouter(Méthode.nom)

  // ── BLOC 6 : POINTS D'ENTRÉE RÉCURSIFS
  // 6a. Dépendances de construction
  Pour chaque Dep dans (constructeurChoisi.parametres ∪ resultat.champs) :
    Mode = Classifier(Dep.type, FIELD_OF_SUT)
    Si Mode ≠ SYSTEM_IGNORE :
      Recurse(Dep.type, null, visites, resultat, Mode, budget, profondeur+1)

  // 6b. Paramètres de methodeCible
  Pour chaque Param dans methodeCible.parametres :
    Mode = Classifier(Param.type, PARAM_OF_METHOD)
    Recurse(Param.type, null, visites, resultat, Mode, budget, profondeur+1)

  // 6c. Appels d'instance détectés
  Pour chaque Appel dans AppelsInstance :
    ClasseAppel = Appel.cibleType
    Si ClasseAppel ∈ HierarchieComplete :
      Mi = ClasseAppel.trouverMethode(Appel.methode, Appel.argsTypes)
      Recurse(ClasseAppel, Mi, visites, resultat, INTERNAL_LOGIC, budget, profondeur+1)
    Sinon :
      Mode = Classifier(ClasseAppel, CALL_TARGET)
      Si Mode ≠ SYSTEM_IGNORE :
        Mi = ClasseAppel.trouverMethode(Appel.methode, Appel.argsTypes)
        Recurse(ClasseAppel, Mi, visites, resultat, Mode, budget, profondeur+1)

  // 6c-bis. Appels statiques utilisateur
  Pour chaque Appel dans AppelsStatiques :
    Si Appel.classe est code utilisateur :
      resultat.statiques.ajouter({ classe, methode, signature })

  // 6d. Instanciations
  Pour chaque Inst dans Instanciations :
    Mode = Classifier(Inst.type, INSTANTIATION)
    Si Mode == DATA_STRUCTURE :
      Recurse(Inst.type, null, visites, resultat, DATA_STRUCTURE, budget, profondeur+1)

  // 6e. Type de retour de methodeCible
  Si Classifier(typeRetour.typeBrut, RETURN_OF_METHOD) == DATA_STRUCTURE :
    Recurse(typeRetour.typeBrut, null, visites, resultat, DATA_STRUCTURE, ...)
  Pour chaque TypeArg dans typeRetour.typeArgs :
    Recurse(TypeArg, null, visites, resultat, Classifier(TypeArg, GENERIC_ARG), ...)

  // ── BLOC 7 : PROTOCOLE D'INITIALISATION DES CHAMPS PRIVÉS
  // (voir section 4 pour le détail)
  callGraph = construireCallGraphIntraSUT(SUT)
  Pour chaque champ dans resultat.champs :
    sources = collecterSourcesInit(SUT, champ)
    strategie = choisirStrategie(champ, sources, callGraph, methodeCible)
    resultat.protocoleInit[champ.nom] = ProtocoleInitChamp(champ, sources, strategie)

  resultat.ordreInitialisation = calculerOrdreTopologique(resultat.protocoleInit)
  resultat.diagnosticTestabilite = construireDiagnostic(resultat.protocoleInit)
```

### 3.2 Mode `INTERNAL_LOGIC`

```text
Recurse(Classe, Methode, visites, resultat, INTERNAL_LOGIC, budget, profondeur)

  Si profondeur > budget.profondeurMax → tronquer ET retourner
  cle = CleVisite(INTERNAL_LOGIC, Classe.fqName, Methode.signatureCanonique())
  Si cle ∈ visites → retourner
  visites.ajouter(cle)
  Si resultat.logiquesInternes.taille >= budget.nbInternesMax → tronquer ET retourner

  Corps = AST(Methode)
  resultat.logiquesInternes[cle] = {
    signature           : capturerSignature(Methode),
    exceptionsLancees   : [],
    exceptionsCatchees  : [],
    appelsResume        : [],
    // Texte source intégral du corps — frontière intra-SUT identique à §3.1.
    // INTERNAL_LOGIC est par construction limité à la hiérarchie SUT (voir
    // condition `ClasseAppel ∈ HierarchieComplete(SUT)` ci-dessous), donc
    // aucun conflit avec §3.3 « STOP ».
    corps               : corpsTexte(Methode)
  }

  Pour chaque nœud dans Corps :
    Si nœud == MethodCall :
      ClasseAppel = résoudreType(nœud.cible)
      MethodeAppelée = ClasseAppel.trouverMethode(nœud.nom, nœud.argsTypes)
      Si ClasseAppel ∈ HierarchieComplete(SUT) :
        Recurse(ClasseAppel, MethodeAppelée, visites, resultat, INTERNAL_LOGIC, ...)
      Sinon :
        Mode = Classifier(ClasseAppel, CALL_TARGET)
        Si Mode ∈ {MOCK_EXTERNAL, DATA_STRUCTURE} :
          Recurse(ClasseAppel, MethodeAppelée, visites, resultat, Mode, ...)

    Si nœud == ThrowStatement → ajouter à exceptionsLancees
    Si nœud == TryStatement   → ajouter à exceptionsCatchees + récurer sur appels du catch

    Si nœud == NewExpression ET Classifier(nœud.type, INSTANTIATION) == DATA_STRUCTURE :
      Recurse(nœud.type, null, visites, resultat, DATA_STRUCTURE, ...)

  // Type de retour
  TR = resoudreType(Methode.typeRetour)
  Si Classifier(TR.typeBrut, RETURN_OF_METHOD) == DATA_STRUCTURE :
    Recurse(TR.typeBrut, null, visites, resultat, DATA_STRUCTURE, ...)
  Pour chaque arg dans TR.typeArgs :
    Si Classifier(arg, GENERIC_ARG) == DATA_STRUCTURE :
      Recurse(arg, null, visites, resultat, DATA_STRUCTURE, ...)
```

### 3.2bis Sous-cas `STUB_VIA_SPY` (frontière framework)

**Motivation** — Un controleur JSF / Servlet expose des méthodes héritées d'une
classe-cadre (`BaseControleur`, `HttpServlet`…) qui descendent directement dans
`javax.faces.*`, `org.primefaces.*`, `javax.servlet.*` ou des appels I/O bas
niveau. Expandre ces méthodes en `INTERNAL_LOGIC` produit deux échecs :

1. La récursion collecte des mocks parasites sur le cadre (`FacesContext`,
   `ExternalContext`, `RequestContext`, `NavigationHandler`…) qui saturent le
   budget `maxMockCount` et n'apportent aucune valeur au test.
2. Le LLM reçoit une consigne « ne pas mocker » sur des méthodes qu'il ne sait
   pas exécuter sans initialiser tout l'écosystème JSF — ce qui crée un prompt
   logiquement insatisfaisable et déclenche la boucle de raisonnement observée
   en production (cf [incident SupervisionDeltaVecControleur]).

**Détection** — Une méthode intra-SUT est classée `STUB_VIA_SPY` si :

```text
Pour chaque appel directement contenu dans Methode :
  Si appel.classeApi.fqName matche un préfixe ∈ FRAMEWORK_PREFIXES :
    Methode est STUB_VIA_SPY (drapeau stubViaSpy=true)
    fin de l'analyse
```

`FRAMEWORK_PREFIXES` (V1, hard-codés, configurables YAML en V1.1) :
- `javax.faces.`, `jakarta.faces.`
- `org.primefaces.`
- `javax.servlet.`, `jakarta.servlet.`
- `java.io.`, `java.net.`, `java.nio.`

On reste **conservateur** : pas `org.springframework.*` ni `org.hibernate.*`
car leurs services sont à mocker normalement via `MOCK_EXTERNAL`. Seuls les
cadres dont les classes ne sont **pas mockables raisonnablement** entrent
dans la liste.

**Sémantique** — Une méthode marquée `STUB_VIA_SPY` :

- est enregistrée dans `resultat.logiquesInternes` avec `stubViaSpy = true` ET
  `frameworkPrefixesHit = [préfixes matchés]`
- **arrête la récursion** : son corps n'est pas exploré, ses appels ne sont
  pas suivis, ses instanciations ne sont pas crawlées. Le test ne traversera
  jamais ce code → inutile de le documenter au LLM.
- est rendue dans une section dédiée (cf §6 `# Méthodes à stubber par spy`)
  qui fournit le pattern Mockito `spy(sut) + doAnswer(...)`.

**Interaction avec les autres modes** — Le check `STUB_VIA_SPY` se fait
**après** la détection des getters triviaux (§3.2 + défaut #4) et **avant**
la récursion `INTERNAL_LOGIC` normale. Une méthode hérité framework qui est
aussi un getter trivial reste un getter trivial (court-circuit pur, sans spy
nécessaire).

**Marqueur pour le BFS d'usage transitif** (défaut #2) — Le calcul d'usage
de champ traverse les méthodes `STUB_VIA_SPY` **comme les autres** pour
collecter les champs qu'elles touchent (essentiel pour ne pas perdre les
@Autowired hérités qui apparaissent uniquement dans la chaîne d'init). Le
court-circuit n'agit que sur la collecte `internalLogics`.

### 3.3 Mode `MOCK_EXTERNAL`

```text
Recurse(ClasseExterne, MethodeAppelée, visites, resultat, MOCK_EXTERNAL, budget, profondeur)

  cle = CleVisite(MOCK_EXTERNAL, ClasseExterne.fqName, MethodeAppelée?.signatureCanonique())
  Si cle ∈ visites → retourner
  visites.ajouter(cle)
  Si resultat.mocks.taille >= budget.nbMocksMax → tronquer ET retourner

  // Phase 1 : Type pour le mock = celui DÉCLARÉ dans le SUT (champ ou paramètre)
  TypePourMock = retrouverTypeDeclareDansSUT(ClasseExterne)
  Si TypePourMock == null :
    TypePourMock = ClasseExterne.uniqueInterfaceUtilisateurOuClasse()

  resultat.mocks[ClasseExterne].typeDeclare        = TypePourMock
  resultat.mocks[ClasseExterne].classeConcrete     = ClasseExterne.fqName
  resultat.mocks[ClasseExterne].annotationsClasse  = ClasseExterne.annotations

  // Phase 2 : Signature appelée
  Si MethodeAppelée ≠ null :
    sig = {
      nom         : MethodeAppelée.nom,
      parametres  : MethodeAppelée.parametres.map(p → {nom: p.nom, type: resoudreType(p.type)}),
      typeRetour  : resoudreType(MethodeAppelée.typeRetour),
      throws      : MethodeAppelée.throws,
      annotations : MethodeAppelée.annotations
    }
    resultat.mocks[ClasseExterne].signaturesRequises.ajouter(sig)

  // Phase 3 : Récursion sur le type de retour pour stubbing
  Si MethodeAppelée ≠ null :
    TR = resoudreType(MethodeAppelée.typeRetour)
    Pour chaque T dans TR.aplatir() :
      Mode = Classifier(T, RETURN_OF_METHOD)
      Si Mode == DATA_STRUCTURE :
        Recurse(T, null, visites, resultat, DATA_STRUCTURE, ...)
      Si Mode == MOCK_EXTERNAL :
        resultat.mocks[ClasseExterne].retourEstMockImbrique = true

  // STOP : ne jamais lire le corps des méthodes externes
```

### 3.4 Mode `DATA_STRUCTURE`

```text
Recurse(ClasseDonnee, _, visites, resultat, DATA_STRUCTURE, budget, profondeur)

  cle = CleVisite(DATA_STRUCTURE, ClasseDonnee.fqName, null)
  Si cle ∈ visites → retourner
  visites.ajouter(cle)
  Si resultat.dataStructures.taille >= budget.nbDtoMax → tronquer ET retourner

  // Phase 1 : détection du pattern
  Pattern = détecterPattern(ClasseDonnee)
  fonction détecterPattern(C):
    Si C est Record                              → RECORD
    Si C a annotation Lombok @Builder           → BUILDER
    Si C a annotation Lombok @Value             → CONSTRUCTOR (immutable)
    Si C est enum                                → ENUM
    Si C est sealed                              → SEALED
    Si ∃ méthode statique "builder()"           → BUILDER
    Si ∃ méthode statique "of(...)" / "from(...)" → STATIC_FACTORY
    Si ∃ constructeur @JsonCreator              → CONSTRUCTOR
    Si C a Lombok @Data ou @AllArgsConstructor  → BUILDER si @Builder, sinon CONSTRUCTOR
    Si ∃ constructeur public avec paramètres    → CONSTRUCTOR
    Si C a no-arg constructor + setters publics → SETTER_BASED
    Sinon                                        → SETTER_BASED

  resultat.dataStructures[ClasseDonnee].pattern = Pattern
  resultat.dataStructures[ClasseDonnee].fqName  = ClasseDonnee.fqName

  // Phase 2 : capturer les éléments selon le pattern
  Selon Pattern :
    RECORD          : capturer composants (nom, type résolu, annotations validation)
    BUILDER         : capturer toutes les méthodes du Builder (nom, champ, type, obligatoire)
    CONSTRUCTOR     : choisir constructeur (@JsonCreator > tous-args > plus complet),
                      capturer paramètres (nom via @JsonProperty si présent)
    SETTER_BASED    : confirmer no-arg constructor, capturer tous les setters
    ENUM            : capturer toutes les constantes
    SEALED          : capturer sous-classes permises ; récurer DATA_STRUCTURE sur chacune
    STATIC_FACTORY  : capturer chaque factory (nom, params, type retour)

  // Phase 3 : champs (sauf RECORD/ENUM/SEALED)
  Si Pattern ∉ {RECORD, ENUM, SEALED} :
    Pour chaque Champ de ClasseDonnee + super-classes utilisateur :
      capturer({
        nom, type, valeurDefaut,
        annotationsValidation : ∩ {@NotNull, @NotBlank, @Size, @Min, @Max, @Pattern, @Email}
      })

  // Phase 4 : récursion sur les champs complexes
  Pour chaque champ capturé :
    SousMode = Classifier(champ.type.typeBrut, FIELD_OF_DTO)
    Si SousMode == DATA_STRUCTURE :
      Recurse(champ.type.typeBrut, null, visites, resultat, DATA_STRUCTURE, ...)
    Si SousMode ∈ {COLLECTION, CONTAINER} :
      Pour chaque arg dans champ.type.typeArgs :
        Si Classifier(arg, GENERIC_ARG) == DATA_STRUCTURE :
          Recurse(arg, null, visites, resultat, DATA_STRUCTURE, ...)
```

### 3.5 Résolution des génériques

```text
fonction resoudreType(typePsi) → TypeResolu
  Si typePsi est PsiWildcardType :
    bound = typePsi.bound  // Object si non borné
    retourner resoudreType(bound) avec estWildcard = true

  Si typePsi est PsiClassType ET typePsi.hasParameters() :
    typeBrut = typePsi.rawType.canonicalText
    typeArgs = typePsi.parameters.map(resoudreType)
    retourner TypeResolu(typeBrut, typeArgs,
                         estCollection = isCollection(typeBrut),
                         estContainer  = isContainer(typeBrut))

  Si typePsi est PsiTypeParameter :
    bound = remonterBindingViaHeritage(typePsi, contexteSUT)
    Si bound trouvé : retourner resoudreType(bound)
    Sinon : retourner TypeResolu("Object", [], estTypeParametreNonResolu = true)

  Si typePsi est PsiArrayType :
    elem = resoudreType(typePsi.componentType)
    retourner TypeResolu(elem.typeBrut + "[]", [], estCollection = true, typeElement = elem)

  retourner TypeResolu(typePsi.canonicalText, [])
```

### 3.6 Choix du constructeur du SUT

```text
fonction choisirConstructeur(C):
  Si ∃ constructeur annoté @Autowired             → retourner ce constructeur
  Si ∃ constructeur annoté @JsonCreator           → retourner ce constructeur
  Si ∃ constructeur annoté @ConstructorProperties → retourner ce constructeur
  Si C a Lombok @RequiredArgsConstructor          → simuler constructeur avec champs final
  Si C a Lombok @AllArgsConstructor               → simuler constructeur avec tous les champs
  Si C n'a qu'un seul constructeur déclaré        → retourner ce constructeur
  Si C a plusieurs constructeurs                  → retourner celui avec le plus de paramètres
  → retourner ConstructeurDefaut (synthétique)
```

---

## 4. Bloc 7 : Protocole d'initialisation par champ

### 4.0 Vue d'ensemble — du champ à la stratégie

```text
Champ du SUT
    │
    ▼
collecterSourcesInit()
    │
    ▼
List<SourceInit>
    │
    ▼
choisirStrategie() ─┐
                    │
    ┌───────────────┴────────────────────────────┐
    │                                            │
    ▼                                            ▼
Source détectée                           StrategieInit retournée
─────────────────────                     ─────────────────────────
Constructor parameter           ───────►  CONSTRUCTOR
@Autowired/@Inject field        ───────►  MOCKITO_INJECT_MOCKS
Public setter                   ───────►  SETTER(methode)
@PostConstruct simple           ───────►  CALL_POST_CONSTRUCT(init)
FieldInitializer (valeur sûre)  ───────►  IMPLICIT
Public method (no args, no ext) ───────►  CALL_PUBLIC(methode)
Public method (with stubs)      ───────►  CALL_PUBLIC_WITH_STUBS(...)
Public method (with args)       ───────►  CALL_PUBLIC_WITH_ARGS(...)
Private method + chemin BFS     ───────►  CALL_PUBLIC_TRANSITIVE(...)
Package-private setter          ───────►  CALL_SAME_PACKAGE(...)
Auto-init dans methodeCible     ───────►  IMPLICIT
Aucun chemin valide             ───────►  UNTESTABLE_AS_IS(raison, pistes)
```

### 4.1 Sources d'initialisation possibles

| Source | Détection PSI |
|--------|---------------|
| **CONSTRUCTOR** | Le champ apparaît dans `ConstructeurChoisi.parametres` |
| **FIELD_INITIALIZER** | `PsiField.getInitializer() != null` |
| **SETTER** | Méthode `setX` avec param compatible |
| **POST_CONSTRUCT** | Méthode annotée `@PostConstruct` qui assigne le champ |
| **METHOD_INITIALIZER** | Toute autre méthode assignant `this.champ = ...` |
| **INITIALIZER_BLOCK** | Bloc `{ this.x = ...; }` ou `static { ... }` |

> Pas de stratégie reflection. Si aucune des sources ci-dessus ne donne un chemin légitime, on retourne `UNTESTABLE_AS_IS`.

### 4.2 Détection des méthodes assignatrices

```text
fonction trouverMethodesAssignatrices(SUT, nomChamp) → List<MethodeAssignatrice>
  resultat = []
  Pour chaque Classe dans HierarchieComplete(SUT) :
    Pour chaque Methode dans Classe.methods + Classe.constructors :
      Si Methode == ConstructeurChoisi → continuer
      visitor = JavaRecursiveElementVisitor() {
        boolean assigneLeChamp = false
        boolean conditionnel = false

        visitAssignmentExpression(expr):
          super.visitAssignmentExpression(expr)
          target = resoudreReference(expr.lExpression)
          Si target est PsiField ET target.nom == nomChamp
                                  ET target.containingClass ∈ HierarchieComplete :
            assigneLeChamp = true
            conditionnel = (estDansBlocConditionnel(expr)
                           ET conditionEstNullCheck(expr, nomChamp))
      }
      Methode.body?.accept(visitor)
      Si visitor.assigneLeChamp :
        resultat.ajouter({
          nom              : Methode.nom,
          signature        : capturerSignature(Methode),
          visibilite       : Methode.visibilite,
          annotations      : Methode.annotations,
          parametresRequis : Methode.parametres.map(resoudreType),
          aGardeNull       : visitor.conditionnel,
          appelsExternes   : detecteAppelsHorsSUT(Methode.body),
          assigneAussi     : detecteAutresAssignations(Methode.body, nomChamp)
        })
  retourner resultat
```

### 4.3 Graphe d'appels inverse intra-SUT

```text
fonction construireCallGraphIntraSUT(SUT) → Map<Methode, Set<Methode>>
  // graphe[X] = méthodes du SUT qui appellent X
  graphe = mapVide()
  Pour chaque Classe dans HierarchieComplete(SUT) :
    Pour chaque Methode dans Classe.methods + Classe.constructors + Classe.initializers :
      visitor = JavaRecursiveElementVisitor() {
        visitMethodCallExpression(call):
          super.visitMethodCallExpression(call)
          cible = call.resolveMethod()
          qualifier = call.methodExpression.qualifierExpression
          estIntraSUT =
                qualifier == null
             OU qualifier instanceof PsiThisExpression
             OU qualifier instanceof PsiSuperExpression
             OU (qualifier.type ∈ HierarchieComplete(SUT))
          Si estIntraSUT ET cible.containingClass ∈ HierarchieComplete(SUT) :
            graphe[cible].ajouter(Methode)
      }
      Methode.body?.accept(visitor)
  retourner graphe
```

### 4.4 Recherche d'un point d'entrée public (BFS dans le graphe inverse)

```text
fonction trouverPointEntreePublic(SUT, champ, methodeAssignatrice, callGraph, methodeCibleSUT)
                                   → CheminInitialisation?
  visited = {}
  queue   = [{ noeud: methodeAssignatrice, chaine: [methodeAssignatrice], profondeur: 0 }]
  candidats = []

  Tant que queue non vide ET profondeur < budget.profondeurGraphe :
    current = queue.poll()
    visited.ajouter(current.noeud)

    accessible = current.noeud.visibilite == "public"
              OU (current.noeud.visibilite ∈ {"protected", "package"} ET acceptePackageTest)

    Si accessible :
      // Filtre d'auto-pollution : pas la méthode cible elle-même
      Si current.noeud == methodeCibleSUT :
        continuer
      Si current.noeud est constructeur :
        candidats.ajouter(CheminInitialisation(
          kind = IMPLICIT_VIA_CONSTRUCTOR, chaine = current.chaine,
          profondeur = current.profondeur, score = 0
        ))
        continuer
      candidats.ajouter(CheminInitialisation(
        kind = si "@PostConstruct" ∈ current.noeud.annotations
               alors PUBLIC_POST_CONSTRUCT sinon PUBLIC_TRANSITIF,
        methodePointEntree    = current.noeud.signature,
        chaine                = current.chaine,
        profondeur            = current.profondeur,
        parametresRequis      = current.noeud.parametres,
        appelsExternesAStubber= collecterAppelsExternes(current.chaine, callGraph),
        effetsDeBord          = collecterChampsAssignes(current.chaine) - {champ.nom},
        score                 = scorer(current.noeud, current.profondeur, ...)
      ))

    Pour chaque appelant dans callGraph[current.noeud] :
      Si appelant ∉ visited :
        queue.add({ noeud: appelant, chaine: current.chaine + [appelant],
                    profondeur: current.profondeur + 1 })

  Si candidats vide → retourner null
  retourner candidats.minBy { it.score }

fonction scorer(methode, profondeur, parametresRequis, appelsExternes, effetsDeBord, kind):
  score = 0
  score += profondeur * 100
  score -= 200  Si kind == PUBLIC_POST_CONSTRUCT
  score -= 100  Si kind == IMPLICIT_VIA_CONSTRUCTOR
  score += parametresRequis.taille * 30
  score += appelsExternes.taille * 20
  score += effetsDeBord.taille * 50
  score += 25   Si méthode retourne un type non-void
  retourner score
```

### 4.5 Sélecteur de stratégie (sans reflection)

```text
fonction choisirStrategie(champ, sources, callGraph, methodeCibleSUT) → StrategieInit

  // 1. Constructeur — toujours préféré
  Si ∃ s ∈ sources : s is Constructor :
    retourner CONSTRUCTOR

  // 2. Champ @Autowired/@Inject privé — InjectMocks
  Si "@Autowired" ∈ champ.annotations OU "@Inject" ∈ champ.annotations :
    retourner MOCKITO_INJECT_MOCKS

  // 3. Setter public
  setterPublic = sources.firstOrNull { it is Setter ET it.methode.visibilite == "public" }
  Si setterPublic ≠ null :
    retourner SETTER(setterPublic.methode)

  // 4. @PostConstruct sans paramètre, sans appels externes complexes
  postConstruct = sources.firstOrNull {
    it is MethodInitializer ET it.kind == POST_CONSTRUCT
    ET it.parametresRequis.empty
  }
  Si postConstruct ≠ null :
    retourner CALL_POST_CONSTRUCT(postConstruct.methode)

  // 5. FIELD_INITIALIZER avec valeur sûre (Logger, ArrayList vide, etc.)
  fieldInit = sources.firstOrNull { it is FieldInitializer }
  Si fieldInit ≠ null ET Classifier(fieldInit.typeInit) ∈ {SYSTEM_IGNORE, ENUM} :
    retourner IMPLICIT

  // 6. Méthode publique directe
  pubDirect = sources.filter {
    it is MethodInitializer ET it.visibilite == "public"
  }.minByOrNull { score(it) }
  Si pubDirect ≠ null :
    Si pubDirect.parametresRequis.empty :
      Si pubDirect.appelsExternes.empty :
        retourner CALL_PUBLIC(pubDirect.methode)
      Sinon :
        retourner CALL_PUBLIC_WITH_STUBS(pubDirect.methode, pubDirect.appelsExternes)
    Sinon :
      retourner CALL_PUBLIC_WITH_ARGS(pubDirect.methode, pubDirect.parametresRequis)

  // 7. Méthode privée/package : recherche d'un point d'entrée transitif
  methodesAssignatrices = sources.filter { it is MethodInitializer }
  Pour chaque ma dans methodesAssignatrices :
    chemin = trouverPointEntreePublic(SUT, champ, ma.methode, callGraph, methodeCibleSUT)
    Si chemin ≠ null :
      Selon chemin.kind :
        IMPLICIT_VIA_CONSTRUCTOR  → retourner IMPLICIT_VIA_CONSTRUCTOR
        PUBLIC_POST_CONSTRUCT     → retourner CALL_POST_CONSTRUCT(chemin.methodePointEntree)
        PUBLIC_TRANSITIF          → retourner CALL_PUBLIC_TRANSITIVE(
                                       pointEntree   = chemin.methodePointEntree,
                                       chaineAppels  = chemin.chaine.map { it.nom },
                                       args          = chemin.parametresRequis,
                                       stubsRequis   = chemin.appelsExternesAStubber,
                                       effetsDeBord  = chemin.effetsDeBord
                                     )

  // 8. Setter package-private accessible si test placé dans le même package
  setterPackage = sources.firstOrNull {
    it is Setter ET it.methode.visibilite ∈ {"protected", "package"}
  }
  Si setterPackage ≠ null :
    retourner CALL_SAME_PACKAGE(setterPackage.methode, [setterPackage.methode.nom])

  // 9. Méthode initialisatrice protected/package
  methodPackage = sources.firstOrNull {
    it is MethodInitializer ET it.visibilite ∈ {"protected", "package"}
    ET it.parametresRequis.empty
  }
  Si methodPackage ≠ null :
    retourner CALL_SAME_PACKAGE(methodPackage.methode, [methodPackage.methode.nom])

  // 10. Auto-init dans la méthode cible elle-même ?
  Si estAutoInitialisé(champ, methodeCibleSUT) :
    retourner IMPLICIT

  // 11. Aucun chemin → non testable sans refactor
  retourner UNTESTABLE_AS_IS(
    raison        = construireRaison(champ, sources, methodeCibleSUT, callGraph),
    pistesRefacto = construirePistes(champ, sources)
  )

fonction estAutoInitialisé(champ, methodeCible):
  ast = AST(methodeCible)
  premièreLecture = ast.firstNode { accède champ.nom en lecture }
  premièreAssignation = ast.firstNode { assigne champ.nom (directement ou via appel transitif) }
  retourner premièreAssignation ≠ null ET premièreAssignation.position < premièreLecture.position
```

### 4.6 Diagnostic UNTESTABLE_AS_IS

```text
fonction construireRaison(champ, sources, methodeCible, callGraph) → String
  Si sources est vide :
    retourner "Aucune source d'initialisation détectée pour `${champ.nom}`. " +
              "Le champ est lu dans `${methodeCible.nom}` mais jamais assigné."

  Si sources.tous(privateOuInaccessible) :
    cibles = sources.map { it.methode.nom }
    appelantes = cibles.flatMap { callGraph[it] }
    Si appelantes contient methodeCible ET appelantes.size == 1 :
      retourner "`${champ.nom}` n'est assigné que par ${cibles}, " +
                "qui n'est appelée que par la méthode cible elle-même."

  retourner "Toutes les méthodes assignant `${champ.nom}` sont privées (${cibles}) " +
            "et aucune méthode publique du SUT ne les appelle transitivement."

fonction construirePistes(champ, sources) → List<String>
  pistes = []
  Si "@Autowired" ∈ champ.annotations :
    pistes.ajouter("Vérifier que @InjectMocks est bien utilisé.")
  Sinon :
    pistes.ajouter("Ajouter un constructeur prenant `${champ.type} ${champ.nom}` en paramètre.")
    pistes.ajouter("Ajouter un setter public `set${champ.nom.capitalized()}(${champ.type})`.")
  Si sources contient méthode initialisatrice privée :
    privées = sources.filter { it.visibilite == "private" }.map { it.methode.nom }
    pistes.ajouter("Rendre l'une des méthodes ${privées} package-private ou public.")
    pistes.ajouter("Annoter une de ces méthodes avec @PostConstruct.")
  retourner pistes
```

### 4.7 Ordre topologique d'application

1. `MOCKITO_INJECT_MOCKS` → automatique (n'apparaît pas dans le code)
2. `SETTER` → après `new SUT(...)`, avant init
3. `CALL_POST_CONSTRUCT` → typiquement après les setters
4. `CALL_PUBLIC_WITH_STUBS` / `CALL_PUBLIC_TRANSITIVE` → APRÈS les `when(...)` Mockito, donc en fin de `@BeforeEach`
5. `CALL_PUBLIC_WITH_ARGS` → en fin de `@BeforeEach` également

---

## 5. Modèles de données

> **Note importante — pseudo-code vs implémentation**
>
> Les noms français de cette section (`ContexteResultat`, `methodeCible`,
> `champs`, `protocoleInit`, etc.) sont du **pseudo-code de spécification**
> et n'apparaissent jamais tels quels dans le code Kotlin du plugin.
>
> L'implémentation Kotlin utilise systématiquement les noms anglais définis
> dans la **table de traduction de ARCHITECTURE.md §3bis**, qui est la
> source de vérité unique pour cette traduction.
>
> Exemples de correspondance :
> - `ContexteResultat` → `ContextResult`
> - `methodeCible` → `targetMethod`
> - `StrategieInit` → `InitStrategy`
> - `ChampSUT` → `SutField`
>
> Si un terme français n'est pas couvert par la table, l'ajouter dans
> ARCHITECTURE.md §3bis **avant** de l'utiliser en code.

```kotlin
// ───────── Types résolus ─────────
data class TypeResolu(
    val typeBrut                  : String,
    val fqName                    : String,
    val typeArgs                  : List<TypeResolu>,
    val estCollection             : Boolean = false,
    val estContainer              : Boolean = false,
    val estWildcard               : Boolean = false,
    val estTypeParametreNonResolu : Boolean = false,
    val nullable                  : Boolean = false
) {
    fun aplatir(): List<String> = listOf(typeBrut) + typeArgs.flatMap { it.aplatir() }
}

data class Parametre(val nom: String, val type: TypeResolu, val annotations: List<String> = emptyList())

data class SignatureMethode(
    val nom             : String,
    val typeRetour      : TypeResolu,
    val parametres      : List<Parametre>,
    val annotations     : List<String>,
    val throwsDeclarees : List<String>,
    val visibilite      : String
)

// ───────── Hiérarchie et SUT ─────────
data class NiveauHierarchie(val classe: String, val annotations: List<String>, val constructeurSuper: List<Parametre>?)
data class ChampSUT(val nom: String, val type: TypeResolu, val visibilite: String, val annotations: List<String>, val declareDans: String)
data class ConstructeurChoisi(val parametres: List<Parametre>, val annotationDeclencheuse: String?, val argsSuper: List<String>)
data class Setter(val methode: String, val champ: String, val type: TypeResolu)

data class PlanInstanciation(
    val constructeurChoisi : ConstructeurChoisi,
    val setters            : List<Setter>,
    val postConstruct      : List<String>
)

// ───────── Méthode cible ─────────
data class ExceptionLancee(val type: String, val message: String?)
data class ExceptionCatchee(val types: List<String>, val appelsCatch: List<String>)
data class BrancheCondition(val kind: String, val condition: String, val constantesUtilisees: List<String>)
data class AppelInstance(val cibleType: String, val cibleNom: String, val methode: String, val argsTypes: List<String>)
data class AppelStatique(val classe: String, val methode: String, val argsTypes: List<String>)

data class AnalyseMethodeCible(
    val signature           : SignatureMethode,
    val appelsInstance      : List<AppelInstance>,
    val appelsStatiques     : List<AppelStatique>,
    val lambdasAttendues    : List<String>,
    val exceptionsLancees   : List<ExceptionLancee>,
    val exceptionsCatchees  : List<ExceptionCatchee>,
    val branches            : List<BrancheCondition>,
    val sourcesIndéter      : List<String>,
    // §3.1 BLOC 2 — texte source du corps (accolades incluses). Vide si le
    // port n'a pas pu lire le source (méthode abstraite, cas dégradé §8bis).
    val corps               : String = ""
)

// ───────── Mocks ─────────
data class SignatureMockee(val nom: String, val parametres: List<Parametre>, val typeRetour: TypeResolu, val throwsDeclarees: List<String>, val annotations: List<String>)

data class MockInfo(
    val classeConcrete        : String,
    val typeDeclare           : String,
    val annotationsClasse     : List<String>,
    val signaturesRequises    : List<SignatureMockee>,
    val retourEstMockImbrique : Boolean = false
)

// ───────── Data Structures ─────────
enum class PatternConstruction { RECORD, BUILDER, CONSTRUCTOR, SETTER_BASED, ENUM, SEALED, STATIC_FACTORY }

data class ChampData(val nom: String, val type: TypeResolu, val annotationsValidation: List<String>, val valeurDefaut: String?, val obligatoire: Boolean = false)
data class CollectionInfo(val nomChamp: String, val typeCollection: String, val typeElement: TypeResolu, val typeCle: TypeResolu? = null)
data class BuilderInfo(val builderClass: String, val methodes: List<MethodeBuilder>)
data class MethodeBuilder(val nom: String, val champ: String, val type: TypeResolu, val obligatoire: Boolean)
data class FactoryInfo(val nom: String, val parametres: List<Parametre>, val typeRetour: TypeResolu)

data class DataStructureInfo(
    val classe       : String,
    val fqName       : String,
    val pattern      : PatternConstruction,
    val champs       : List<ChampData>,
    val collections  : List<CollectionInfo>,
    val builderInfo  : BuilderInfo? = null,
    val factoryInfo  : List<FactoryInfo> = emptyList(),
    val enumValues   : List<String> = emptyList(),
    val sealedSubs   : List<String> = emptyList()
)

// ───────── Logique interne ─────────
data class LogiqueInterne(
    val cleVisite           : String,
    val signature           : SignatureMethode,
    val exceptionsLancees   : List<ExceptionLancee>,
    val exceptionsCatchees  : List<ExceptionCatchee>,
    val appelsResume        : List<String>,
    // §3.2 — texte source du corps. Frontière intra-SUT (cf §3.3 « STOP » qui
    // s'applique exclusivement à MOCK_EXTERNAL). Vide si non capturé.
    val corps               : String = ""
)

data class StatiqueInfo(val classe: String, val methode: String, val signature: SignatureMethode)

// ───────── Protocole d'init (Bloc 7) ─────────
sealed class SourceInit {
    data class Constructor(val parametre: String) : SourceInit()
    data class FieldInitializer(val expression: String, val typeInit: TypeResolu) : SourceInit()
    data class Setter(val methode: String, val parametre: TypeResolu) : SourceInit()
    data class MethodInitializer(
        val kind             : MethodInitKind,
        val methode          : SignatureMethode,
        val visibilite       : String,
        val parametresRequis : List<Parametre>,
        val aGardeNull       : Boolean,
        val appelsExternes   : List<AppelInstance>,
        val assigneAussi     : List<String>
    ) : SourceInit()
    data class InitializerBlock(val static: Boolean, val source: String) : SourceInit()
}

enum class MethodInitKind { POST_CONSTRUCT, ORDINARY }

sealed class StrategieInit {
    object CONSTRUCTOR : StrategieInit()
    object IMPLICIT : StrategieInit()
    object IMPLICIT_VIA_CONSTRUCTOR : StrategieInit()
    object MOCKITO_INJECT_MOCKS : StrategieInit()
    data class SETTER(val methode: String) : StrategieInit()
    data class CALL_POST_CONSTRUCT(val methode: SignatureMethode) : StrategieInit()
    data class CALL_PUBLIC(val methode: SignatureMethode) : StrategieInit()
    data class CALL_PUBLIC_WITH_STUBS(val methode: SignatureMethode, val stubsRequis: List<AppelInstance>) : StrategieInit()
    data class CALL_PUBLIC_WITH_ARGS(val methode: SignatureMethode, val args: List<Parametre>) : StrategieInit()
    data class CALL_PUBLIC_TRANSITIVE(
        val pointEntree   : SignatureMethode,
        val chaineAppels  : List<String>,
        val args          : List<Parametre>,
        val stubsRequis   : List<AppelInstance>,
        val effetsDeBord  : List<String>
    ) : StrategieInit()
    data class CALL_SAME_PACKAGE(val methode: SignatureMethode, val chaineAppels: List<String>) : StrategieInit()
    data class UNTESTABLE_AS_IS(val raison: String, val pistesRefacto: List<String>) : StrategieInit()
}

data class ProtocoleInitChamp(
    val champ                : ChampSUT,
    val sources              : List<SourceInit>,
    val strategieRecommandee : StrategieInit
)

data class DiagnosticTestabilite(
    val testable        : Boolean,
    val champsBloquants : List<String>,
    val raisons         : List<String>,
    val pistesRefacto   : List<String>
)

// ───────── Racine ─────────
data class ContexteResultat(
    val sutFqName             : String,
    val hierarchie            : List<NiveauHierarchie>,
    val champs                : List<ChampSUT>,
    val planInstanciation     : PlanInstanciation,
    val methodeCible          : AnalyseMethodeCible,
    val logiquesInternes      : LinkedHashMap<String, LogiqueInterne>,
    val mocks                 : LinkedHashMap<String, MockInfo>,
    val dataStructures        : LinkedHashMap<String, DataStructureInfo>,
    val statiques             : List<StatiqueInfo>,
    val protocoleInit         : LinkedHashMap<String, ProtocoleInitChamp>,
    val ordreInitialisation   : List<String>,
    val diagnosticTestabilite : DiagnosticTestabilite,
    val callGraphIntraSUT     : Map<String, List<String>>,
    val tronque               : Boolean = false,
    val raisonsTroncature     : List<String> = emptyList()
)
```

---

## 6. Format de rendu du ContextTree (contenu de la layer CONTEXT)

> Cette section décrit **uniquement** le rendu du `ContextTree` qui sera injecté
> dans la layer `[CONTEXT]` du prompt final. Le wrapping complet du prompt
> (SYSTEM, CONSTRAINTS, INSTRUCTION, etc.) est défini dans **PROMPT_FORMAT.md**.
>
> Ce rendu est produit par `ContextRenderStage` à partir du `ContextTree`.
> Il ne contient **aucune** instruction de génération — il décrit factuellement
> le contexte extrait. Les contraintes et la mission sont assemblées par les
> stages suivants à partir des templates de PROMPT_FORMAT.md.

> **Bug Q (V1)** — Le rendu CONTEXT est désormais entièrement en anglais
> (le spec ci-dessous garde le format en anglais pour matcher l'implémentation).
> Spec doc rédigée en français (convention CLAUDE.md), output runtime en anglais
> (consommé par le LLM).
>
> **Bug R (V1)** — La variable `sut` des patterns suggérés est désormais dérivée
> du nom de la classe (ex : `SupervisionDeltaVecControleur` → `supervisionDeltaVecControleur`)
> pour respecter la règle de naming `TypeName typeName`.

```markdown
# Class under test
{sutFqName} {annotationsClasse}
Hierarchy: {hierarchie format A → B → C avec annotations}

# Target method
{signature complète, types qualifiés}
{si corps non vide :}
Source code:
```java
{corps texte intégral, accolades incluses}
```
- throws (declared): {throwsDeclarees}
- exceptions thrown in body: {exceptionsLancees}
- exceptions caught: {exceptionsCatchees}
- branches: {liste avec condition}
- non-deterministic sources: {sourcesIndéter}
- expected lambdas: {lambdasAttendues}

# Class under test instantiation
new {sutFqName}({paramètres avec types qualifiés})
{si super(...)} super({argsSuper})

# Field initialization protocol
EXECUTE IN ORDER below, inside @BeforeEach.

{Pour chaque champ dont strategieRecommandee != CONSTRUCTOR/IMPLICIT/MOCKITO_INJECT_MOCKS :}

## Field `{champ.nom}`: {champ.type}
Strategy: {strategieRecommandee.kind}

{selon kind — {cut} = variable name dérivé du SUT, ex: "orderService" :}
SETTER:
{cut}.{methode}(mockOf{type});

    CALL_POST_CONSTRUCT:
      {cut}.{methode}();   // @PostConstruct

    CALL_PUBLIC:
      {cut}.{methode}();

    CALL_PUBLIC_WITH_STUBS:
      // Stub first:
      when({stub1.cible}.{stub1.methode}({stub1.argsTypes})).thenReturn(...);
      // Then:
      {cut}.{methode}();

    CALL_PUBLIC_WITH_ARGS:
      {cut}.{methode}({arg1}, {arg2});  // construct per the "Data structures" section

    CALL_PUBLIC_TRANSITIVE:
      // This public method assigns `{champ.nom}` via the chain:
      //    {chaineAppels.join(' → ')}
      // Known side effects: {effetsDeBord}
      // Required stubs before the call:
      {stubsRequis listés}
      {cut}.{pointEntree.nom}({args});

    CALL_SAME_PACKAGE:
      // Place the test in the same package as the class under test
      {cut}.{methode}();

    MOCKITO_INJECT_MOCKS:
      // Nothing to do: Mockito injects this @Autowired/@Inject field automatically

    UNTESTABLE_AS_IS:
      // STOP: this field cannot be initialized without refactoring.
      // Reason: {raison}
      // Hints: {pistesRefacto}
      // Generate a test marked TODO:
      @Test
      void {methodeCible.nom}_TODO_untestable() {
          fail("Test cannot be completed without refactoring the class under test. See docstring.");
      }

# Mocks (annotate with @Mock {declaredType})
{Pour chaque mock :}
## {typeDeclare}  (concrete: {classeConcrete})
Methods to stub:
- {signatureMockee complète}
  {si throws : "throws {liste}"}

# Internal sub-methods (informational only, do not mock)
{Pour chaque InternalLogic dont stubViaSpy == false :}
## {classe}#{méthode}{signature}
{si corps non vide :}
Source code:
```java
{corps texte intégral, accolades incluses}
```
- throws: {exceptions}
- key calls: {résumé}

# Methods to stub via spy (framework boundary — §3.2bis)
{Pour chaque InternalLogic dont stubViaSpy == true :}
## {classe}#{méthode}({argTypes})
- Reason: descends into {préfixes matchés joints par ", "}
- Expected pattern in @BeforeEach:
  ```java
  {cut} = spy({cut});
  doAnswer(invocation -> null).when({cut}).{méthode}(any({argTypes[0]}.class));
  // or doReturn(<typedDefault>) for primitive returns (boolean → false, int → 0, ...)
  ```
- Do NOT explore the body — closed test boundary.

# Data structures to construct
{Pour chaque DTO :}
## {fqName} [{pattern}]
{selon pattern : RECORD, BUILDER, CONSTRUCTOR, SETTER_BASED, ENUM, SEALED, STATIC_FACTORY}
Fields: {liste champ:type}

# Detected user static calls
{si présents : "Mock via Mockito.mockStatic({class}.class):"}
```

> **Note** : les contraintes de génération, les garde-fous (no reflection, etc.)
> et la mission finale sont assemblés par les stages suivants à partir des
> templates de **PROMPT_FORMAT.md**. Ce fragment ne les répète pas.

---

## 6bis. CONSTRAINTS — split Base + Tuning LLM (V1.2 Phase 5)

**Motivation** : V1.1 mélangeait dans un seul bloc `DEFAULT_CONSTRAINTS` :
- des **règles universelles** Java/JUnit/Mockito (applicables quel que soit le LLM cible)
- des **patches hand-holding spécifiques à Qwen 3.6 35B** (Bug Y/Z/AA/DD) observés en production

Pour un futur LLM puissant (Claude Opus, GPT-4o…), inclure les patches Qwen
noierait les règles structurelles et gaspillerait des tokens. La V1.2 Phase 5
sépare les deux et rend le profil swappable via Settings.

### 6bis.1 Architecture

```
core/prompt/constraints/
├── BaseConstraints.kt          ← règles universelles (~150 lignes)
├── QwenTuningConstraints.kt    ← patches Bug Y/Z/AA/DD (~65 lignes)
└── ConstraintsProfile.kt       ← sealed interface
```

`LayerCompositionStage.Templates.defaults(profile)` compose à la volée :
- `BaseConstraints.TEXT` (prefix, jusqu'à `# Coverage scope`)
- `profile.tuningText` (inséré juste avant `# Wrapping rules for return types`)
- `BaseConstraints.TEXT` (suffix, depuis `# Wrapping rules`)

L'ordre V1.1 est préservé byte-pour-byte quand le profil Qwen est actif —
verrou de rétro-compat pour les tests historiques.

### 6bis.2 Profils livrés

| Profil | id | Contenu | Cible |
|--------|----|---------|---|
| `Qwen36b35bProfile` | `"qwen"` | Bug Y + Z + AA + DD | Qwen 3.6 35B (défaut V1) |
| `NoTuningProfile`   | `"none"` | Aucun patch | LLM puissants (Claude/GPT-4) |

`resolveConstraintsProfile(id)` mappe la string config → profil. Fallback
safe : id inconnu → Qwen (jamais NoTuning par accident).

### 6bis.3 Patches Qwen-tuning isolés

| Bug | Modèle d'échec observé | Recipe LLM |
|-----|------------------------|------------|
| Y   | Test n'a pas `throws ExceptionType` alors que target en déclare | « EVERY test method ... MUST declare throws ExceptionType » |
| Z   | `when(...).thenReturn(Collections.emptyMap())` au lieu de `Collections.<K,V>emptyMap()` | « use Explicit type witness » |
| AA  | `verify(mock).foo(new DTO())` au lieu de `any(DTO.class)` | « Use any(Type.class) matcher » |
| DD  | `new ElementXxx()` pour peupler un List<ElementXxx> sans no-arg ctor | « Use Mockito.mock(Type.class) AD-HOC inside the test body » |

### 6bis.4 Wiring config

```
ContextCrawlerSettings (UI)
       ↓
ContextExtractorConfig.llm.tuningProfile: String  (défaut "qwen")
       ↓
ContextExtractorService.buildContext
       ↓
PromptBuilder.defaultPipelineForProfileId(id)
       ↓
LayerCompositionStage(Templates.defaults(profile))
```

Le `tuningProfileId` est également propagé jusqu'au `PromptCopyDialog`
pour que le rebuild « Copy with enrichment » utilise le même profil que
l'extraction initiale.

### 6bis.5 Ajouter un nouveau profil

1. Créer `core/prompt/constraints/ClaudeOpusConstraints.kt` (ou autre nom)
2. Ajouter `object ClaudeOpusProfile : ConstraintsProfile { ... }`
3. Étendre la branche `when` dans `resolveConstraintsProfile`
4. Ajouter `"claude-opus"` au `ComboBox` du `ContextCrawlerConfigurable`

Aucune modification de `BaseConstraints` ni de tests existants n'est
nécessaire — le verrou « base ne contient pas de Bug X » est testé par
`ConstraintsProfileSplitTest.kt`.

---

## 7. Notes d'implémentation PSI

### 7.1 APIs PSI clés

| Besoin | API |
|--------|-----|
| Trouver classe par FQN | `JavaPsiFacade.getInstance(project).findClass(fqn, scope)` |
| Parcours d'une méthode | `PsiMethod.getBody().accept(JavaRecursiveElementVisitor { ... })` |
| Type qualifié | `PsiType.getCanonicalText()` |
| Résolution méthode appelée | `PsiMethodCallExpression.resolveMethod()` |
| Décomposer chaîne d'appels | parcourir `getMethodExpression().getQualifierExpression()` récursivement |
| Détecter static | `resolveMethod().hasModifierProperty(PsiModifier.STATIC)` |
| Annotations | `AnnotationUtil.findAnnotation(elem, fqn)` |
| Records | `PsiClass.isRecord()`, `getRecordComponents()` |
| Sealed | `PsiClass.hasModifierProperty(PsiModifier.SEALED)`, `getPermitsList()` |
| Méthodes héritées | `PsiClass.findMethodsByName(name, deep = true)` |
| Try/catch | `JavaRecursiveElementVisitor.visitTryStatement(...)` |
| Lambda | `visitLambdaExpression(...)`, `getFunctionalInterfaceType()` |
| Assignation | `JavaRecursiveElementVisitor.visitAssignmentExpression(...)` |
| Initializers | `PsiClass.getInitializers()` retourne `PsiClassInitializer[]` |

### 7.2 Pièges classiques

- **Toujours `getCanonicalText()`** (jamais `getText()`) pour les types.
- **`findMethodsByName(name, true)`** pour inclure les supers.
- **Lombok** : si le plugin Lombok IntelliJ est actif, les méthodes générées apparaissent comme `LightMethod` dans PSI standard.
- **Generics non résolus** : `PsiTypeParameter` → remonter via `PsiClassType.getSubstitutor()`.
- **Performance** : tout appel PSI doit être fait dans une `ReadAction`. `ProgressManager.checkCanceled()` régulièrement pour les graphes profonds.

### 7.3 Cache

```text
cacheClasses     : Map<String, PsiClass>
cacheMethodes    : Map<MethodSignatureKey, PsiMethod>
cacheCallGraph   : Map<PsiClass, Map<PsiMethod, Set<PsiMethod>>>   // calculé une fois par SUT
cacheResultat    : Map<(sutFqName, methodeSig), ContexteResultat>  // invalidé via PsiModificationTracker
```

---

## 8. Stratégie de troncature

Si dépassement de budget pendant la récursion :

1. Marquer `resultat.tronque = true` et la raison.
2. Conserver ce qui est plus proche de la racine.
3. Lister les éléments non explorés dans `aExplorerEnPriorite`.
4. Le prompt indique au LLM que le contexte est partiel.

Priorisation :
- Méthodes effectivement appelées sur les mocks > méthodes potentiellement appelées
- DTOs directement passés en argument > DTOs imbriqués au 3e niveau
- Branches du chemin nominal > branches d'exception

---

## 8bis. Gestion des cas dégradés

Sur du code legacy ou un projet en cours de build, l'extraction peut rencontrer
des cas non nominaux. Aucun de ces cas ne doit faire planter le plugin —
chacun a une stratégie de fallback explicite.

### 8bis.1 Type non résolvable

```text
resoudreType(typePsi) retourne TypeResolu("Object", [], typeIrresoluble = true)
  → Logger : warn "Type non résolu : ${typePsi.canonicalText}"
  → Continuer la récursion avec ce TypeResolu marqué
  → Le rendu indique "<unresolved>" dans le prompt
  → Le LLM verra explicitement le problème et pourra utiliser Object
```

### 8bis.2 Classe introuvable (FQN inexistant)

```text
JavaPsiFacade.findClass(fqn, scope) == null
  → Si fqn est un type système → traiter comme SYSTEM_IGNORE
  → Sinon → ajouter à resultat.erreursResolution
              + ne pas récurer dans cette branche
              + le rendu indique la classe manquante
```

### 8bis.3 Build cassé (références rouges dans l'IDE)

```text
PsiClass / PsiMethod retournent des éléments avec resolveResult.isValid == false
  → Logger : warn "Code non compilable détecté"
  → Capturer ce qui est lisible (signatures, annotations)
  → Marquer resultat.codeIncomplet = true
  → Le rendu indique au LLM que le code source est partiellement compilable
```

### 8bis.4 Budget dépassé en plein milieu d'un nœud critique

```text
Si profondeur > budget.profondeurMax sur un nœud SUT_BOOTSTRAP :
  → ERREUR : un SUT doit toujours être complètement extrait
  → Augmenter le budget temporairement OU retourner UNTESTABLE_AS_IS

Si profondeur > budget.profondeurMax sur un nœud DATA_STRUCTURE imbriqué :
  → Tronquer ce sous-arbre, conserver le nœud mais marquer enfantsTronques = true
  → Le rendu indique "{Type} (structure interne tronquée — profondeur max atteinte)"
```

### 8bis.5 Dépendance circulaire entre DTOs

```text
DTO A → champ de type B → champ de type A
  → Le registre de visites détecte le cycle (CleVisite déjà présente)
  → Récursion stoppée à la deuxième visite
  → Le rendu indique "{TypeA} (déjà décrit ci-dessus)"
  → Pas d'erreur, comportement attendu
```

### 8bis.6 Lombok actif mais Lombok plugin désactivé

```text
Si SUT a annotation @Data/@Builder/@RequiredArgsConstructor
  ET aucune méthode getter/setter/builder() n'est trouvée dans PSI
  → Logger : info "Lombok détecté mais membres générés non visibles"
  → Considérer le pattern Lombok déclaré comme la source de vérité
  → Synthétiser les membres attendus selon l'annotation
```

### 8bis.7 PSI null sur l'élément ciblé par l'utilisateur

```text
Si JavaPsiIntrospector.findEnclosingMethod() == null
  → ERREUR remontée à l'UI : "Le curseur n'est pas dans une méthode Java"
  → Pas d'extraction, message clair pour l'utilisateur
```

---

## 9. Exemples concrets pour le Bloc 7

### 9.1 Cas favorable — @PostConstruct prioritaire

```java
public class OrderService {
    private DiscountCache cache;
    @PostConstruct public void init() { primeCache(); }
    private void primeCache() { this.cache = new DiscountCache(); }
    public OrderDTO calculate(...) { return cache.lookup(...); }
}
```
→ Stratégie `CALL_POST_CONSTRUCT(init)` → `sut.init();` dans `@BeforeEach`.

### 9.2 Méthode publique avec arguments

```java
public class OrderService {
    private Config config;
    public void configure(int timeout, String region) {
        this.config = new Config(timeout, region);
    }
    public OrderDTO calculate(...) { return config.apply(...); }
}
```
→ Stratégie `CALL_PUBLIC_WITH_ARGS(configure, [int, String])` → `sut.configure(30, "EU");`.

### 9.3 Chaîne transitive

```java
public class OrderService {
    private Cache cache;
    public void start() { startInternal(); }
    private void startInternal() { warmup(); }
    private void warmup() { this.cache = buildCache(); }
    private Cache buildCache() { return new Cache(loader.load()); }
    public OrderDTO calculate(...) { return cache.get(...); }
}
```
→ Stratégie `CALL_PUBLIC_TRANSITIVE(start, [start, startInternal, warmup], stubs=[loader.load])`.
→ Prompt : stubber `loader.load()`, puis `sut.start();`.

### 9.4 Auto-init dans la méthode cible

```java
public class OrderService {
    private Cache cache;
    private void primeIfNeeded() { if (cache == null) cache = new Cache(); }
    public OrderDTO calculate(...) {
        primeIfNeeded();
        return cache.get(...);
    }
}
```
→ `estAutoInitialisé(cache, calculate) == true` → Stratégie `IMPLICIT`.

### 9.5 Cas non-testable — refactor requis

```java
public class OrderService {
    private Cache cache;
    private void primeCache(Config c) { this.cache = new Cache(c); }
    public OrderDTO calculate(...) { return cache.get(...); }
    // primeCache n'est appelée nulle part ailleurs
}
```
→ Stratégie `UNTESTABLE_AS_IS(raison="primeCache jamais appelée par une méthode publique")`.
→ Prompt : test marqué TODO + 3 pistes de refacto.

---

## 10. Limites connues

- **Polymorphisme effectif** : si une méthode prend `Animal` mais reçoit `Dog` en pratique, hors data-flow analysis.
- **Réflexion utilisateur** dans le SUT (`Class.forName`) : non détectée.
- **Spring conditionnel** (`@ConditionalOnProperty`, profils) : hors périmètre unit test.
- **AspectJ / proxy CGLIB** : non détectables ; non concernés en unit test pur.
- **Mutation de collections** (`this.list.add(...)`) : détecter en v4.
- **Test paramétré** (`@ParameterizedTest`) : laissé à l'appréciation du LLM, basé sur `branches` et `constantesUtilisees`.
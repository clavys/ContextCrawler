# Matrice de couverture des features — ContextCrawler

> **Instrument de convergence.** Hypothèse de travail : pour une architecture
> correcte et des tests **strictement unitaires** (on mocke tout autour de la
> logique), l'ensemble des cas à traiter est **fini et borné par la sémantique
> du langage + des frameworks** (Java / PSI / Mockito / JUnit), **pas par le
> code du projet analysé**. Cette matrice énumère cet ensemble fini ; toute
> ligne sans règle **et** sans verrou est un bug *prévisible*.
>
> Quand chaque ligne sémantique a une règle ET un test, l'**axe déterministe
> (L)** est clos — et « plus on avance, moins il y a de bugs » devient une
> garantie, plus une intuition.

---

## 0. Cadre : les 3 axes d'erreur (rappel)

| Axe | Borné par | Converge ? | Réduit par… |
|---|---|---|---|
| **L** — extracteur déterministe | sémantique Java/Mockito/JUnit (fini) | **oui**, via cette matrice | couverture par feature |
| **P** — fidélité PSI / environnement | complétude classpath, bytecode des libs, résolution générique | borné, plancher non nul | **mock-everything** (supprime la part *comportementale* ; garde la part *structurelle*) |
| **G** — comportement du LLM | le modèle lui-même | **non** | prompt actionnable + profil isolé |

**Principe directeur (north star)** : *contexte minimal piloté par la demande* —
ne récupérer/matérialiser un élément (champ, ctor, type, signature) **que si une
obligation concrète du chemin nominal l'exige** : un stub, une assertion, un
discriminant de branche, un argument de constructeur. Ce principe subsume le cas
DTO (plus de sur-fetch), borne l'axe P (on ne résout que ce qu'on demande) et
réduit l'axe G (moins de bruit dans le prompt).

**Légende statut** : ✅ règle + verrou · ⚠️ partiel / à confirmer · ❌ faille
connue (n° = audit `RAPPORT`/session). « Verrou » = test qui échoue si la règle
régresse.

---

## A. Nature du type référencé → `ExtractionMode` (PASSE 2, 16 règles)

| Feature | Décision attendue | Règle | Statut | Verrou / note |
|---|---|---|---|---|
| `java.*` / primitif | SYSTEM_IGNORE | R1 | ✅ | classifier tests |
| préfixe framework configuré (`javax.faces`, `org.primefaces`) | SYSTEM_IGNORE | R2 | ✅ | Bug HH (4.2) |
| container (`Optional`, `CompletableFuture`, `Mono`, `Flux`, RxJava) | CONTAINER | R3 | ✅ | + wrapping rules |
| collection (`List`/`Set`/`Map`/`Collection`/`Iterable`/`Queue`/`Deque`) | COLLECTION | R4 | ✅ | |
| SAM `java.util.function.*` | FUNCTIONAL_LAMBDA | R5 | ✅ | |
| classe de la hiérarchie SUT | INTERNAL_LOGIC | R6 | ✅ | |
| appelé **uniquement** en statique | STATIC_UTILITY | R7 | ✅ | + pur vs mockable (Bug HH) |
| `new Type()` dans le corps | DATA_STRUCTURE (forcé) | R7bis | ✅ | Astrea R3-2 (case 4.3) |
| appelé en instance + **mutateur/business** | MOCK_EXTERNAL | R8 | ✅ | Bug U |
| appelé en instance + **champ injecté du SUT** | MOCK_EXTERNAL | R8 (`isSutField`) | ⚠️ | **faille #3** : champ avec initialiseur inline / `final` ⇒ `@InjectMocks` n'injecte pas ⇒ mock orphelin |
| `equals(x)` / `compareTo(x)` sur value-object | DATA_STRUCTURE attendu | R8 helper | ❌ | **faille #4** : args ⇒ classé « business » ⇒ MOCK à tort |
| ENUM / SEALED / RECORD | DATA_STRUCTURE | R9 | ✅ | |
| Lombok `@Data`/`@Value`/`@Builder` | DATA_STRUCTURE | R10 | ✅ | |
| param de target lu via getter **non adressable** | MOCK_EXTERNAL | R10bis | ✅ (dormant) | `ParamConstructibilityTTTest` (Bug TT) |
| retour de stub **exclusif** sans ctor no-arg | MOCK_EXTERNAL | R11bis | ⚠️ | **faille #2** : ne vérifie pas `visibility=="public"` du ctor no-arg |
| interface / classe abstraite | MOCK_EXTERNAL | R12 | ✅ | |
| stéréotype Spring (`@Service`/`@Repository`/…) | MOCK_EXTERNAL | R13 | ⚠️ | **faille #8** : liste fixe + annotations directes (méta-annotations / `@Configuration` ratés) |
| toutes méthodes triviales (getters/setters) | DATA_STRUCTURE | R14 | ✅ | case00/case92 |
| surface de target + descriptor introuvable | MOCK_EXTERNAL (sécurité) | R15 | ✅ | |
| défaut | DATA_STRUCTURE | R16 | ✅ | inversion V1.2 |
| **classe `final`** | non mockable sans mockito-inline | — | ❌ | **faille #5** : `ClassDescriptor` n'expose pas la finalité (port à étendre) |
| **classe nested non-static** | besoin de l'instance englobante | — | ❌ | gap probable (instanciation `outer.new Inner()`) |

---

## B. Construction d'un `DATA_STRUCTURE` → `ConstructionPattern` (PASSE 3)

| Feature | Pattern | Statut | Verrou / note |
|---|---|---|---|
| record natif | RECORD | ✅ | composants = ctor canonique |
| `@Builder` Lombok / `builder()` statique | BUILDER | ✅ | builder class résolue |
| `@Value` / `@Data` / `@AllArgsConstructor` | CONSTRUCTOR | ✅ | |
| ctor public à args | CONSTRUCTOR + **signatures exactes** | ✅ | Bug LL (`ConstructorSignatureTest`) |
| enum | ENUM + valeurs rendues | ✅ | Bug R3-A (constantes) |
| sealed | SEALED + sous-classes | ✅ | |
| factory statique `of`/`from`/`valueOf` | STATIC_FACTORY | ✅ | |
| `@JsonCreator` | CONSTRUCTOR | ✅ | |
| sinon | SETTER_BASED | ✅ | `new X()` + setters |
| **ctor no-arg PRIVÉ** (singleton) | ne pas proposer `new X()` | ❌ | **faille #2** |
| **aucun ctor public + aucun setter** (immutable opaque) | mocker ou UNTESTABLE | ⚠️ | à expliciter |
| **explosion en largeur des DTO** | borne le **nombre** de types | ❌ | **faille #6** : `maxDtoFieldDepth` borne la profondeur, pas la largeur (cas 260-DTO) |
| DTO profond (imbrication) | borne la profondeur | ✅ | `maxDtoFieldDepth` + Bug KK |

---

## C. Génériques (axe P structurel — résolution PSI)

| Feature | Attendu | Statut | Verrou / note |
|---|---|---|---|
| variable de type nue (`T`, `E`, `K`, `V`) dans signature | droppée / raw type | ✅ | Bug #E, Bug JJ (`TypeVarStubSignatureTest`) |
| substitution générique au call-site (`List<T>`→`List<Concrete>`) | type concret retenu | ✅ | Bug QQ (`ReceiverAttributionQQTest`) |
| retour `List<E>` non visible (getter chaîné) | `doReturn(...)` | ✅ | Bug RR (`ConstraintsYZAATest`) |
| type-arg d'un **param de target** | construit (descente DTO) | ✅ | Bug OO (TriDTO/OrdreTriEnum) |
| wildcard `? extends`/`? super` | rendu sain | ⚠️ | à confirmer |
| SUT générique (`class Foo<T>`) | liaison vue du curseur | ⚠️ | `listFieldsInContext` couvre les champs ; méthodes ? |
| générique borné multi-niveaux | — | ⚠️ | fragile sur grosses hiérarchies (axe P) |

---

## D. Hiérarchie / héritage / overriding

| Feature | Attendu | Statut | Verrou / note |
|---|---|---|---|
| champ hérité protected/package | MOCKITO_INJECT_MOCKS + hint **nom exact** | ✅ | `InheritedProtectedFieldTest` (4.1) |
| override vs méthode parente (même forme) | bon corps introspecté | ✅ | Bug II (`OverrideShadowingFixIITest`, `declaredIn`) |
| `super.method()` non visible depuis le call-type | remontée hiérarchie | ✅ | Bug #C bis (`findMethodIn`) |
| méthode chaînée sur retour intra-SUT (`super.getX().getY()`) | STUB_VIA_SPY | ✅ | Bug #C (`RecursiveDeepStrategyBugCBisTest`) |
| **surcharge** (même nom, args ≠, pas de match exact) | bon overload | ❌ | **faille #7** : fallback `candidates.first()` |
| hiérarchie d'entités JPA interconnectée | bornée | ⚠️ | partiellement (faille #6 largeur) |

---

## E. Visibilité / accessibilité (× package du test)

| Feature | Attendu | Statut | Verrou / note |
|---|---|---|---|
| **convention de package du test** | UNE seule, cohérente | ❌ | **faille #1** : `BaseConstraints` dit *same package*, warnings disent *different package* — contradiction |
| méthode interne protected/package | info, accessibilité = f(package du test) | ⚠️ | dépend de #1 |
| méthode privée jamais stub via spy | internal logic info | ✅ | Bug NN |
| init via méthode protected/package | CALL_SAME_PACKAGE | ⚠️ | valide **seulement** si test = même package (#1) |
| ctor non-public | ne pas proposer `new` | ❌ | **faille #2** |
| setter non-public utilisé en SETTER reconcile | vérifier visibilité | ⚠️ | dépend de #1 |

---

## F. Sémantique Mockito (axe L pur — devrait converger complètement)

| Feature | Règle/constraint | Statut | Verrou / note |
|---|---|---|---|
| `when().thenReturn()` générique typé | type-witness exact | ✅ | Bug Z |
| retour `List<E>`/type-var → `doReturn()` | DECISION RULE | ✅ | Bug RR |
| `thenThrow(checked)` ⇒ méthode déclarante | rendu `throws` + règle | ✅ | Bug UU (`ContextRenderStageTest`) |
| stub spy branch-specific | `lenient()` | ✅ | Bug VV |
| stub sur objet `new` (real) interdit | mock/spy seulement | ✅ | V1.4.5 (MissingMethodInvocation) |
| `@InjectMocks` désambiguïsation par **nom** | hint nom | ✅ | Fix D |
| UnnecessaryStubbing (strict) | tracer la consommation | ✅ | Bug FF |
| `verify` avec `new` in-body + mix any/eq | `any(T.class)` + `eq()` | ✅ | Bug AA |
| `mockStatic` valeur par défaut | pur vs mockable | ✅ | Bug HH |
| peupler `List<Element>` sans ctor | `mock(Element.class)` ad hoc | ✅ | Bug DD |
| sort/Comparator sur mocks | stub la clé | ✅ | Bug EE |
| `isSameAs` sur collection reconstruite | égalité de contenu | ✅ | Bug GG |
| **classe / méthode `final`** | non mockable (sans inline) | ❌ | **faille #5** |
| **injection par ctor vs champ/setter** | aligner stratégie d'init | ❌ | **faille #9** : ctor à args ⇒ Mockito fait ctor-injection |
| mock dans `HashSet`/`HashMap` (equals/hashCode) | stub equals/hashCode | ⚠️ | Bug EE partiel |
| `@Mock` d'un type avec génériques bruts | — | ⚠️ | |

---

## G. Structure du test (JUnit 5) — axe L

| Feature | Règle | Statut |
|---|---|---|
| `@Test` sur chaque méthode | R1 | ✅ |
| zéro commentaire | R2 | ✅ (`NoCommentsNoPublicConstraintTest`) |
| package-private (classe + méthodes) | R3 | ✅ |
| pas de `new` sur un type `# Mocks` | R4 | ✅ |
| `@InjectMocks` répliqué verbatim | R5 | ✅ (`InjectMocksInstantiationTest`) |
| checked exception sur signature de test | Bug Y | ✅ |
| nommage variable = type (camelCase) | style | ✅ (`PromptLanguageAndNamingTest`) |
| champ UNTESTABLE → `fail()` TODO | §6 | ✅ (`ContextRenderStageTest`) |

---

## H. Exceptions

| Feature | Attendu | Statut | Verrou / note |
|---|---|---|---|
| `throws` déclaré → exception **constructible** | matérialisée + ctors | ✅ | Bug OO |
| `thenThrow(checked)` autorisé seulement si déclarée | rendu `throws` + Bug UU | ✅ | |
| exception levée dans le corps (`throw new`) | branche couverte | ⚠️ | rendu info ; couverture ? |
| bloc `catch` (effets observables) | info | ⚠️ | rendu, pas d'assertion guidée |

---

## I. Couverture des branches

| Feature | Attendu | Statut | Verrou / note |
|---|---|---|---|
| `if` / condition | brancher la valeur | ✅ | rendu branches |
| `switch` + case labels (constantes FQN) | référencer la constante | ✅ | Bug SS (`SwitchCaseLabelsRRTest`) |
| discriminant = constante de classe | FQN résolu | ✅ | Bug SS |
| boucles / streams | — | ⚠️ | non modélisé spécifiquement |
| ternaire / court-circuit | — | ⚠️ | |

---

## J. Cas dégradés / fidélité PSI (axe P)

| Feature | Attendu | Statut | Verrou / note |
|---|---|---|---|
| type non résolvable | stub `SETTER_BASED` + signalé | ✅ | §8bis.1 |
| plugin Lombok inactif | synthèse du ctor | ✅ | §8bis.6 |
| **noms de params absents** (bytecode lib) | name-matching dégradé | ⚠️ | impacte R10bis (Bug TT) — fragile sur libs |
| substitution générique « qui lâche » sur grosse hiérarchie | — | ⚠️ | mentionné dans le code, non verrouillé |
| troncature (budget atteint) | `fail("TODO truncated")` | ✅ | truncationReasons |
| classpath incomplet (deps manquantes) | dégradé propre | ⚠️ | dépend de l'env (réduit par mock-everything) |

---

## K. Statiques / singletons

| Feature | Attendu | Statut | Verrou / note |
|---|---|---|---|
| utilitaire pur (commons/guava/JDK) | NE PAS mockStatic | ✅ | Bug HH (liste préfixes) |
| static métier à contrôler | mockStatic + stub tout | ✅ | Bug HH |
| **singleton `Foo.getInstance()`** | non injectable / mockStatic | ❌ | gap probable (classé pur ? non) |
| factory statique d'un DTO | STATIC_FACTORY | ✅ | |

---

## Synthèse — où sont les trous (à prioriser)

| # | Faille | Axe | Effort | Impact |
|---|--------|-----|--------|--------|
| 1 | Contradiction package du test | L | moyen | couverture perdue en silence |
| 2 | Ctor no-arg non-public traité comme public | L | **1 ligne** | ne compile pas |
| 3 | Champ SUT initialisé inline forcé MOCK | L+Mockito | faible | famille NPE 4.1 |
| 4 | `equals`/`compareTo` → MOCK à tort | L | faible | DTO mal mockés |
| 5 | Classe/méthode `final` non détectée | L (port) | moyen | échec runtime Mockito |
| 6 | Explosion en largeur des DTO | P | moyen | prompt ingérable |
| 7 | Overload résolu par `.first()` | P | moyen | mauvaise signature |
| 8 | Stéréotypes Spring méta-annotés | L+P | moyen | service → `new` |
| 9 | Injection ctor vs champ/setter | L+Mockito | moyen | stubs/setters redondants |

**Lecture pour le rapport** : 6 des 9 trous sont sur l'**axe L** (donc *fermables
définitivement* par règle + verrou → ils valident ta thèse de convergence). 2
sont sur l'**axe P structurel** (#6, #7 — réductibles par l'algo *demand-driven*,
pas supprimables). Aucun n'est sur l'axe G : le découplage LLM tient.

**Prochaine étape recommandée** : fermer #2 + #3 + #1 (les plus rentables), puis
faire évoluer la descente DTO (faille #6) vers le modèle *demand-driven* — ce
qui transformerait `maxDtoFieldDepth` (heuristique) en « contexte minimal »
(principe), fermant d'un coup la sur-récupération.

---

*Sources* : `core/classifier/DefaultContextAwareClassifier.kt`,
`strategies/recursive/refs/{ReferenceGraphBuilder,ResultMaterializer}.kt`,
`strategies/recursive/RecursiveDeepStrategy.kt`,
`core/prompt/constraints/{BaseConstraints,QwenTuningConstraints}.kt`,
`core/extractor/{ClassDescriptor,ClassField,MethodSignature}.kt`.
Voir `PIPELINE_EXTRACTION.md` pour le flux, `RAPPORT_CONTEXT.md` pour l'historique des bugs.

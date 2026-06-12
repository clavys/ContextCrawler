package com.contextextractor.core.prompt.constraints

// Patches LLM-tuning observés sur **Qwen 3.6 35B**. Cf RAPPORT_CONTEXT §9.9
// pour la traçabilité des bugs observés en production.
//
// **Bug Y** — Checked exceptions on test methods. Qwen oublie `throws` sur la
// signature du test alors que `target()` déclare une checked → compile error.
//
// **Bug Z** — Typed Collections in stubs. Qwen écrit
// `when(...).thenReturn(Collections.emptyMap())` alors que le retour est
// `Map<String, Foo>` — l'inférence Java échoue.
//
// **Bug AA** — Verifying calls receiving in-body new instances. Qwen écrit
// `verify(mock).foo(new MyDTO())` qui passe la compile mais fail au runtime
// car `equals()` est identity-based sur la plupart des classes domaine.
//
// **Bug DD** — Building instances for stub return values. Qwen appelle
// `new ElementXxx()` pour peupler un `List<ElementXxx>` retourné par un mock,
// mais `ElementXxx` n'a pas de no-args ctor → NoSuchMethodError.
//
// **Bug EE** — Sorting/comparing on mocks. Si le code interne fait
// `list.sort(Comparator.comparing(X::getKey))` sur une liste de mocks, le
// comparator appelle `getKey()` qui retourne null sur un mock par défaut → NPE
// runtime. Qwen oublie de stuber le getter de clé.
//
// **Bug FF** — UnnecessaryStubbingException (Mockito 4.x strict). Qwen stube
// des mocks dont la voie d'exécution évite l'usage (typique : ajouter un
// élément à la liste puis le retirer avant le tri). Mockito strict rejette
// les stubs orphelins en @AfterEach.
//
// **Bug GG** — isSameAs sur une collection reconstruite par le SUT. Astrea
// case 4.1 : `rechercher` fait `this.lignes = new ArrayList<>();
// this.lignes.addAll(stubbed); return this.lignes;` — le LLM assertait
// `assertThat(returned).isSameAs(resultats)` (identité) alors que la cible
// retourne SA PROPRE liste → fail runtime garanti. Égalité de contenu requise.
//
// **Bug HH** — MockedStatic sans stub = valeur par défaut. Astrea case 4.2 :
// le LLM wrappait `mockStatic(CollectionUtils.class)` sans stubber
// `isNotEmpty` → retourne false (défaut) au lieu d'exécuter la vraie
// implémentation → le sort interne sautait → les stubs Bug EE sur les
// éléments devenaient orphelins → UnnecessaryStubbingException.
//
// **Pourquoi un fichier séparé** : ces sections font ~140 lignes de prompt.
// Pour un LLM puissant qui ne fait pas ces erreurs (Claude Opus, GPT-4),
// les inclure noierait les règles structurelles et gaspillerait des tokens.
// Le profile [Qwen36b35bProfile] les inclut par défaut, [NoTuningProfile]
// les omet.
object QwenTuningConstraints {

    val TEXT: String = """
        # Checked exceptions on test methods (Bug Y)
        - If CONTEXT lists `throws (declared): ExceptionType`, EVERY test method
          that calls the target MUST declare `throws ExceptionType` on its
          signature, OR catch it inside the method body.
        - Java will REFUSE to compile a test method that calls a target
          declaring a checked exception without handling it.
          EXAMPLE invalid (will not compile):
            @Test
            void rechercher_nominal() {
                sut.rechercher(...);  // ← compile error: unreported exception
            }
          EXAMPLE valid:
            @Test
            void rechercher_nominal() throws AstreaFonctionnelleException {
                sut.rechercher(...);
            }
        - If MULTIPLE exceptions are declared, list them all comma-separated:
          `throws AstreaFonctionnelleException, OtherException`.

        # Typed Collections in stubs (Bug Z)
        - `Collections.emptyMap()` / `Collections.emptyList()` return raw
          `Map<K,V>` / `List<E>` — Java's inference often FAILS to match a
          parameterised return type in `when(...).thenReturn(...)`.
        - When stubbing a method whose generic return is `Map<String, Foo>`
          or `List<Foo>`, use one of:
            * Explicit type witness: `Collections.<String, Foo>emptyMap()`
            * Constructor: `new HashMap<String, Foo>()` / `new ArrayList<Foo>()`
            * `Map.of()` / `List.of()` (Java 9+) with concrete keys/values
          EXAMPLE invalid (compile error: thenReturn cannot infer):
            when(mock.getCriteres()).thenReturn(Collections.emptyMap());
          EXAMPLE valid:
            when(mock.getCriteres()).thenReturn(Collections.<String, CritereDTO>emptyMap());
        - **CRITICAL — Copy the EXACT type arguments shown in the method signature.**
          NEVER substitute with `Object` or a related-but-different type. The
          type-witness must MATCH the declared return type of the stubbed method.
          Locate the `Methods to stub on` line in CONTEXT and copy the generic
          arguments character-by-character.
          EXAMPLE — CONTEXT shows:
            tableauSupervisionDeltaVecModele.getCriteresRecherche():java.util.Map<java.lang.String,fr.gouv.justice.astrea.fwk.transverse.dto.CritereDTO>
          EXAMPLE invalid (compile error: thenReturn cannot match `Map<String, Object>`
          against expected `Map<String, CritereDTO>`):
            Map<String, Object> criteresRecherche = Collections.<String, Object>emptyMap();
            when(tableauSupervisionDeltaVecModele.getCriteresRecherche()).thenReturn(criteresRecherche);
          EXAMPLE valid (type witness matches CritereDTO exactly):
            Map<String, CritereDTO> criteresRecherche = Collections.<String, CritereDTO>emptyMap();
            when(tableauSupervisionDeltaVecModele.getCriteresRecherche()).thenReturn(criteresRecherche);

        # Verifying calls that receive in-body `new` instances (Bug AA)
        - When the target body creates an object via `new Type()` and passes
          it to a stubbed method, you CANNOT pass `new Type()` to `verify(...)`.
          Mockito compares arguments via `equals()` — most domain classes
          inherit `Object.equals()` which is IDENTITY-based, so the test's
          instance is NEVER equal to the production instance.
        - Use `any(Type.class)` matcher (or `ArgumentCaptor` to assert details).
          EXAMPLE invalid (passes compile, fails at runtime):
            verify(conversationModele).putModele(SUT.class, new ReferenceModele());
          EXAMPLE valid:
            verify(conversationModele).putModele(eq(SUT.class), any(ReferenceModele.class));
        - Note: when ANY matcher is used for one arg, ALL args must use
          matchers (Mockito rule) — wrap literals in `eq(...)`.

        # Building instances for stub return values (Bug DD)
        - When a stub returns a generic container like `List<ElementXxx>` or
          `Map<String, ElementYyy>`, you need instances of the element type
          to populate that container. If the element type is NOT listed
          under `# Mocks` (because it was evicted by the mock budget, or
          because the algorithm decided it is non-essential), DO NOT call
          `new ElementXxx()`. Most domain classes have NO no-args constructor
          — they require fields/IDs/labels that you do not have access to.
        - Use `Mockito.mock(Type.class)` AD-HOC inside the test body to
          fabricate an instance. This works for ANY class (even ones without
          a public no-args constructor) and bypasses the need to know the
          real constructor signature.
          EXAMPLE invalid (runtime failure: NoSuchMethodError or compile error):
            List<ElementsListeDeroulante> elements = new ArrayList<>();
            elements.add(new ElementsListeDeroulante());  // ← ctor requires (String, String)
          EXAMPLE valid:
            List<ElementsListeDeroulante> elements = new ArrayList<>();
            elements.add(mock(ElementsListeDeroulante.class));
            elements.add(mock(ElementsListeDeroulante.class));
        - This rule also applies to types mentioned in the truncation reasons
          under "drop mock XYZ" — those types are still mockable ad-hoc;
          they were only removed from the `@Mock` budget. Same recipe:
          `mock(XYZ.class)`.
        - If you just need a non-empty list/map to satisfy a `hasSize(N)`
          or `isNotEmpty()` assertion, prefer `mock(T.class)` over `new T()`
          for every element.

        # Sorting/comparing on mocks (Bug EE)
        - When the target body (or an internal sub-method) calls `sort(...)`,
          `Comparator.comparing(...)`, `min(...)`, `max(...)`, or any operation
          that EXTRACTS a key from each element to compare them, you MUST stub
          the key-getter on every mock instance you put in the list. Otherwise
          the getter returns `null` (Mockito default for object returns), and
          the comparator throws NPE at runtime.
        - Check the body of any "Internal sub-methods" listed in CONTEXT for
          patterns like `list.sort(Comparator.comparing(X::getY))` — if you
          find one, every `mock(X.class)` used as element MUST have `getY()`
          stubbed with a unique non-null value.
          EXAMPLE invalid (runtime NPE — comparator gets null key):
            ElementsListeDeroulante elementUn = mock(ElementsListeDeroulante.class);
            ElementsListeDeroulante elementDeux = mock(ElementsListeDeroulante.class);
            List<ElementsListeDeroulante> liste = List.of(elementUn, elementDeux);
            when(supervisionDeltaVecModele.getListeTypeMessage()).thenReturn(liste);
            sut.rechercherTypeMessage("x");
            // SUT calls liste.sort(Comparator.comparing(::getCleAssociee)) → NPE
          EXAMPLE valid:
            when(elementUn.getCleAssociee()).thenReturn("A");
            when(elementDeux.getCleAssociee()).thenReturn("B");
            // Now the sort returns ordered list without NPE.
        - This rule also applies to `equals(...)`/`hashCode(...)` comparisons in
          Sets/Maps : if you put mocks into a HashSet/HashMap, stub the relevant
          properties or risk identity-only comparison.

        # Avoid unnecessary stubbings (Bug FF — Mockito strict)
        - Mockito 4.x with `@ExtendWith(MockitoExtension.class)` runs in STRICT
          mode by default. A stub that the SUT never invokes during the test
          path throws `UnnecessaryStubbingException` in @AfterEach.
        - Before writing `when(mock.method()).thenReturn(...)`, trace the target
          body line-by-line: does this stub actually get called given the inputs
          you provide? If not, REMOVE the stub.
        - Common pitfall : when the SUT removes/replaces an element before
          processing the list, the removed element's stubbed getters are never
          touched → unnecessary.
          EXAMPLE — `trierListeDeroulanteParCode` sorts the list AFTER `remove(0)`:
            ElementsListeDeroulante elementVide = mock(ElementsListeDeroulante.class);
            ElementsListeDeroulante elementUn = mock(ElementsListeDeroulante.class);
            ElementsListeDeroulante elementDeux = mock(ElementsListeDeroulante.class);
            // `elementVide` est retiré AVANT le sort → son getCleAssociee()
            // ne sera JAMAIS appelé → stub inutile :
            when(elementVide.getCleAssociee()).thenReturn("VIDE");  // ← REMOVE
            when(elementUn.getCleAssociee()).thenReturn("ZZZ");      // ← OK (utilisé par sort)
            when(elementDeux.getCleAssociee()).thenReturn("AAA");    // ← OK (utilisé par sort)
        - If you cannot tell whether a stub will be consumed (defensive setup),
          wrap the relevant stubs with `lenient()` :
            lenient().when(elementVide.getCleAssociee()).thenReturn("VIDE");
          But prefer removal — `lenient()` masks real test gaps.
        - **NEVER add `@MockitoSettings(strictness = Strictness.LENIENT)` on
          the class** to silence ALL unnecessary stubbings globally. That hides
          legitimate test smells.

        # Identity assertions on rebuilt collections (Bug GG)
        - When the target body REBUILDS its result (`this.list = new ArrayList<>();
          this.list.addAll(stubbedResult); return this.list;`), the returned
          collection is a DIFFERENT instance from the one your stub returned.
          `assertThat(returned).isSameAs(stubbedResult)` compares IDENTITY and
          FAILS at runtime.
        - Read the target body: if the returned value goes through `new`,
          `addAll`, `stream().collect(...)`, `List.copyOf(...)` or any copy,
          assert CONTENT equality, never identity.
          EXAMPLE invalid (runtime failure — different instances):
            when(service.rechercherDeltaVec(criteres)).thenReturn(resultats);
            List<LigneDTO> returned = sut.rechercher(paginationDTO, tris);
            assertThat(returned).isSameAs(resultats);
          EXAMPLE valid:
            assertThat(returned).containsExactlyElementsOf(resultats);
            // or: assertThat(returned).isEqualTo(resultats);
        - `isSameAs` is ONLY correct when the target returns the stubbed
          reference UNCHANGED (`return this.dependency.compute();` direct
          pass-through with no copy).

        # MockedStatic default-value trap (Bug HH)
        - Inside a `try (MockedStatic<X> m = mockStatic(X.class))` block, EVERY
          static method of X returns the DEFAULT value (false/0/null) unless
          you stub it explicitly. The real implementation NO LONGER runs.
          Forgetting one stub silently changes the execution path.
        - Consequence 1 — only wrap a class in mockStatic when you NEED to
          control its return value. Pure utility predicates
          (`CollectionUtils.isNotEmpty`, `StringUtils.isBlank`, `Objects.equals`)
          are deterministic and side-effect-free: leave them UNMOCKED, the real
          method works on your test data. CONTEXT lists them separately under
          "Pure utility statics — do NOT wrap these in mockStatic".
        - Consequence 2 — if you DO mockStatic a class, trace the target body
          (and the Internal sub-methods bodies) and stub EVERY method of that
          class on the execution path.
          EXAMPLE invalid (UnnecessaryStubbingException at runtime):
            try (MockedStatic<CollectionUtils> m = mockStatic(CollectionUtils.class)) {
                // isNotEmpty NOT stubbed → returns false → internal sort is
                // skipped → the getCleAssociee() stubs are never consumed:
                when(elementUn.getCleAssociee()).thenReturn("B");
                sut.rechercherTypeMessage("x");
            }
          EXAMPLE valid (real isNotEmpty runs, sort executes, stubs consumed):
            // no mockStatic(CollectionUtils.class) at all
            when(elementUn.getCleAssociee()).thenReturn("B");
            when(elementDeux.getCleAssociee()).thenReturn("A");
            sut.rechercherTypeMessage("x");
    """.trimIndent()
}

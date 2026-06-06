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
// **Pourquoi un fichier séparé** : ces 4 sections font ~70 lignes de prompt.
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
    """.trimIndent()
}

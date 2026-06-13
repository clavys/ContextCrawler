package com.contextextractor.core.extractor

// PORT principal de la couche core/ — voir ARCHITECTURE.md §5.
// Tout ce qu'une stratégie sait du code passe par ici. PSI n'apparaît jamais
// dans le core. V1 : seul JavaPsiIntrospector implémente cette interface.
interface CodeIntrospector {

    fun resolveSymbolAt(file: SourceFile, offset: Int): Symbol?

    fun findEnclosingMethod(symbol: Symbol): MethodSignature?

    fun resolveClass(fqn: String): ClassDescriptor?

    fun listMethodCalls(method: MethodSignature): List<MethodCall>

    // Accès en lecture/écriture détectés dans le corps de la méthode.
    //
    // **Garantie d'ordre** : la liste retournée respecte l'ordre d'apparition
    // des accès dans le code source. Cette garantie est requise par BLOC 7
    // (§4.5 stratégie 10 `estAutoInitialisé`) qui compare les positions de
    // la première lecture et de la première assignation pour décider si un
    // champ s'auto-initialise dans la méthode cible.
    fun listFieldAccesses(method: MethodSignature): List<FieldAccess>

    // Assignations de champs détectées dans le corps de la méthode — distinct
    // de listFieldAccesses parce que §4.2 demande un contexte enrichi
    // (isConditional, conditionIsNullCheck) qui n'a de sens que pour les
    // écritures.
    //
    // **Garantie d'ordre** : la liste retournée respecte l'ordre d'apparition
    // des assignations dans le code source — même contrainte que
    // listFieldAccesses (cf estAutoInitialisé §4.5).
    fun listFieldAssignments(method: MethodSignature): List<FieldAssignment>

    // Champs déclarés directement sur cette classe — la stratégie remonte la
    // hiérarchie via listSuperClasses() pour collecter tous les champs visibles
    // (STRATEGIE.md §3.1 BLOC 1 « Champs de la SUT et héritage »).
    fun listFields(cls: ClassDescriptor): List<ClassField>

    // V1.4.3 Bug JJ — variante contextuelle de listFields : les variables de
    // type génériques d'un champ hérité sont substituées par leur liaison
    // concrète vue depuis `viewedFrom` (la SUT). Ex Astrea case 4.4 :
    // `modele : M` déclaré dans `AbstractSaisieMessageControleur<M>` devient
    // `modele : SaisieMessage01Modele` vu depuis `SaisieMessage01Controleur`.
    // Sans cette substitution, le protocole d'init rend `setModele(mockOfM)`
    // et le LLM devine un type (le bound) qui ne compile pas.
    // Implémentation par défaut : identique à listFields — les fakes ne
    // modélisent pas les génériques.
    fun listFieldsInContext(cls: ClassDescriptor, viewedFrom: ClassDescriptor): List<ClassField> =
        listFields(cls)

    // Méthodes déclarées directement sur cette classe, constructeurs inclus
    // (par convention name = "<init>" pour les constructeurs, returnType = la
    // classe elle-même). Requis par STRATEGIE.md §3.1 BLOCs 4-5 (choix du
    // constructeur, setters, @PostConstruct) et §4.3 (callGraph intra-SUT).
    fun listMethods(cls: ClassDescriptor): List<MethodSignature>

    fun listSuperClasses(cls: ClassDescriptor): List<ClassDescriptor>

    fun readMethodBody(method: MethodSignature): String

    // Analyse structurelle du corps — STRATEGIE.md §3.1 BLOC 2. Un seul
    // parcours AST collecte instanciations, lambdas, exceptions lancées /
    // catchées, branches conditionnelles et sources non-déterministes.
    //
    // **Frontière** : à n'appeler QUE sur des méthodes intra-SUT
    // (SUT_BOOTSTRAP / INTERNAL_LOGIC). §3.3 « STOP » interdit la lecture du
    // corps des méthodes externes — le respect de cette règle incombe à
    // l'appelant (RecursiveDeepStrategy ne l'invoque jamais pour MOCK_EXTERNAL).
    //
    // Implémentation par défaut neutre : un introspector qui ne sait pas
    // analyser le corps (cas dégradé §8bis, fake non configuré) retourne un
    // résultat vide sans casser le pipeline aval.
    fun analyzeMethodBody(method: MethodSignature): MethodBodyAnalysis = MethodBodyAnalysis()

    fun listAnnotations(target: AnnotatedTarget): List<AnnotationRef>
}

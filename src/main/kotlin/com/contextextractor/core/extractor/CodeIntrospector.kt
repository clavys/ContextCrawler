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

    // Méthodes déclarées directement sur cette classe, constructeurs inclus
    // (par convention name = "<init>" pour les constructeurs, returnType = la
    // classe elle-même). Requis par STRATEGIE.md §3.1 BLOCs 4-5 (choix du
    // constructeur, setters, @PostConstruct) et §4.3 (callGraph intra-SUT).
    fun listMethods(cls: ClassDescriptor): List<MethodSignature>

    fun listSuperClasses(cls: ClassDescriptor): List<ClassDescriptor>

    fun readMethodBody(method: MethodSignature): String

    fun listAnnotations(target: AnnotatedTarget): List<AnnotationRef>
}

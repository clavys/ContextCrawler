package com.contextextractor.core.extractor

// PORT principal de la couche core/ — voir ARCHITECTURE.md §5.
// Tout ce qu'une stratégie sait du code passe par ici. PSI n'apparaît jamais
// dans le core. V1 : seul JavaPsiIntrospector implémente cette interface.
interface CodeIntrospector {

    fun resolveSymbolAt(file: SourceFile, offset: Int): Symbol?

    fun findEnclosingMethod(symbol: Symbol): MethodSignature?

    fun resolveClass(fqn: String): ClassDescriptor?

    fun listMethodCalls(method: MethodSignature): List<MethodCall>

    fun listFieldAccesses(method: MethodSignature): List<FieldAccess>

    // Champs déclarés directement sur cette classe — la stratégie remonte la
    // hiérarchie via listSuperClasses() pour collecter tous les champs visibles
    // (STRATEGIE.md §3.1 BLOC 1 « Champs de la SUT et héritage »).
    fun listFields(cls: ClassDescriptor): List<ClassField>

    fun listSuperClasses(cls: ClassDescriptor): List<ClassDescriptor>

    fun readMethodBody(method: MethodSignature): String

    fun listAnnotations(target: AnnotatedTarget): List<AnnotationRef>
}

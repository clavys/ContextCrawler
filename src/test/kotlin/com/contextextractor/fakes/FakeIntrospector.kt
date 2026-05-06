package com.contextextractor.fakes

import com.contextextractor.core.extractor.AnnotatedTarget
import com.contextextractor.core.extractor.AnnotationRef
import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.FieldAccess
import com.contextextractor.core.extractor.FieldAssignment
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.SourceFile
import com.contextextractor.core.extractor.Symbol

// Implémentation in-memory du port CodeIntrospector — utilisée par les tests
// de l'étape 2+. Aucune dépendance PSI ; chaque scénario est construit via le
// DSL `fixture { ... }` défini dans FixtureBuilder.kt.
class FakeIntrospector : CodeIntrospector {

    private val classes = mutableMapOf<String, ClassDescriptor>()
    private val methodsByOwner = mutableMapOf<String, MutableList<MethodSignature>>()
    private val ownerByMethod = mutableMapOf<MethodSignature, String>()
    private val bodies = mutableMapOf<MethodSignature, String>()
    private val callsByMethod = mutableMapOf<MethodSignature, MutableList<MethodCall>>()
    private val accessesByMethod = mutableMapOf<MethodSignature, MutableList<FieldAccess>>()
    private val assignmentsByMethod = mutableMapOf<MethodSignature, MutableList<FieldAssignment>>()
    private val annotationsByTarget = mutableMapOf<AnnotatedTarget, MutableList<AnnotationRef>>()
    private val symbolsByLocation = mutableMapOf<Pair<String, Int>, Symbol>()
    private val enclosingMethodBySymbol = mutableMapOf<String, MethodSignature>()
    private val superFqnChains = mutableMapOf<String, List<String>>()
    private val fieldsByOwner = mutableMapOf<String, MutableList<ClassField>>()

    // -- Implémentation du port -----------------------------------------------

    override fun resolveSymbolAt(file: SourceFile, offset: Int): Symbol? =
        symbolsByLocation[file.path to offset]

    override fun findEnclosingMethod(symbol: Symbol): MethodSignature? =
        enclosingMethodBySymbol[symbol.id]

    override fun resolveClass(fqn: String): ClassDescriptor? = classes[fqn]

    override fun listMethodCalls(method: MethodSignature): List<MethodCall> =
        callsByMethod[method].orEmpty()

    override fun listFieldAccesses(method: MethodSignature): List<FieldAccess> =
        accessesByMethod[method].orEmpty()

    override fun listFieldAssignments(method: MethodSignature): List<FieldAssignment> =
        assignmentsByMethod[method].orEmpty()

    override fun listFields(cls: ClassDescriptor): List<ClassField> =
        fieldsByOwner[cls.fqn].orEmpty()

    override fun listMethods(cls: ClassDescriptor): List<MethodSignature> =
        methodsByOwner[cls.fqn].orEmpty()

    override fun listSuperClasses(cls: ClassDescriptor): List<ClassDescriptor> =
        superFqnChains[cls.fqn].orEmpty().mapNotNull { classes[it] }

    override fun readMethodBody(method: MethodSignature): String =
        bodies[method].orEmpty()

    override fun listAnnotations(target: AnnotatedTarget): List<AnnotationRef> =
        annotationsByTarget[target].orEmpty()

    // -- API d'écriture (utilisée par le builder DSL) -------------------------

    internal fun putClass(descriptor: ClassDescriptor) {
        classes[descriptor.fqn] = descriptor
    }

    internal fun putMethod(ownerFqn: String, signature: MethodSignature, body: String) {
        methodsByOwner.getOrPut(ownerFqn) { mutableListOf() }.add(signature)
        ownerByMethod[signature] = ownerFqn
        if (body.isNotEmpty()) bodies[signature] = body
    }

    internal fun putCall(from: MethodSignature, call: MethodCall) {
        callsByMethod.getOrPut(from) { mutableListOf() }.add(call)
    }

    internal fun putFieldAccess(from: MethodSignature, access: FieldAccess) {
        accessesByMethod.getOrPut(from) { mutableListOf() }.add(access)
    }

    internal fun putFieldAssignment(from: MethodSignature, assignment: FieldAssignment) {
        assignmentsByMethod.getOrPut(from) { mutableListOf() }.add(assignment)
    }

    internal fun putAnnotation(target: AnnotatedTarget, annotation: AnnotationRef) {
        annotationsByTarget.getOrPut(target) { mutableListOf() }.add(annotation)
    }

    internal fun putSymbolAt(file: SourceFile, offset: Int, symbol: Symbol) {
        symbolsByLocation[file.path to offset] = symbol
    }

    internal fun putEnclosing(symbol: Symbol, method: MethodSignature) {
        enclosingMethodBySymbol[symbol.id] = method
    }

    internal fun putSuperChain(classFqn: String, superFqns: List<String>) {
        superFqnChains[classFqn] = superFqns
    }

    internal fun putField(ownerFqn: String, field: ClassField) {
        fieldsByOwner.getOrPut(ownerFqn) { mutableListOf() }.add(field)
    }

    // -- Helpers d'oracle pour les tests --------------------------------------
    // listMethodsOf / listFieldsOf passent désormais par le port
    // (voir TestExtensions.kt). Ce qui reste ici est ce qui n'est PAS exposé
    // par CodeIntrospector — purement pour assertions sur l'index inverse.

    fun ownerOf(method: MethodSignature): String? = ownerByMethod[method]
}

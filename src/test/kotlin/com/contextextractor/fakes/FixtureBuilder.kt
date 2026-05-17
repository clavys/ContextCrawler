package com.contextextractor.fakes

import com.contextextractor.core.extractor.AnnotatedTarget
import com.contextextractor.core.extractor.AnnotationRef
import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.CaughtExceptionRef
import com.contextextractor.core.extractor.ConditionalBranchRef
import com.contextextractor.core.extractor.FieldAccess
import com.contextextractor.core.extractor.FieldAssignment
import com.contextextractor.core.extractor.MethodBodyAnalysis
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.extractor.ResolvedType
import com.contextextractor.core.extractor.ThrownExceptionRef

// DSL fluent pour construire un FakeIntrospector. Pensé pour ressembler à
// la lecture d'un fichier Java :
//
// fixture {
//     klass("com.example.Service", annotations = listOf("Service")) {
//         field("repo", T("com.example.Repo"))
//         method("findOrder", returns = T("com.example.OrderDTO")) {
//             param("ref", T("java.lang.String"))
//             reads("com.example.Service", "repo")
//             calls("com.example.Repo", "findById", "java.lang.Long")
//         }
//     }
// }

fun fixture(block: FixtureBuilder.() -> Unit): FakeIntrospector =
    FixtureBuilder().apply(block).build()

// Raccourci de construction d'un ResolvedType simple (pas de génériques).
// Pour les types paramétrés voir [Tg] et [Wildcard].
fun T(fqName: String, nullable: Boolean = false): ResolvedType =
    ResolvedType(rawType = fqName.substringAfterLast('.'), fqName = fqName, nullable = nullable)

// Raccourci type générique : Tg("java.util.List", T("Foo"))
fun Tg(fqName: String, vararg args: ResolvedType): ResolvedType {
    val raw = fqName.substringAfterLast('.')
    val isCollection = fqName in COLLECTION_FQNS
    val isContainer = fqName in CONTAINER_FQNS
    return ResolvedType(
        rawType = raw,
        fqName = fqName,
        typeArgs = args.toList(),
        isCollection = isCollection,
        isContainer = isContainer
    )
}

// Wildcard ? — STRATEGIE.md §8bis.1 : doit être marqué isWildcard.
val Wildcard: ResolvedType = ResolvedType(
    rawType = "?",
    fqName = "java.lang.Object",
    isWildcard = true
)

private val COLLECTION_FQNS = setOf(
    "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable"
)
private val CONTAINER_FQNS = setOf("java.util.Map", "java.util.Optional")

class FixtureBuilder {
    private val fake = FakeIntrospector()

    fun build(): FakeIntrospector = fake

    fun klass(
        fqn: String,
        annotations: List<String> = emptyList(),
        superFqn: String? = null,
        interfaces: List<String> = emptyList(),
        isAbstract: Boolean = false,
        isInterface: Boolean = false,
        isRecord: Boolean = false,
        isSealed: Boolean = false,
        isEnum: Boolean = false,
        visibility: String = "public",
        permittedSubclasses: List<String> = emptyList(),
        enumValues: List<String> = emptyList(),
        block: ClassScope.() -> Unit = {}
    ): ClassDescriptor {
        val descriptor = ClassDescriptor(
            fqn = fqn,
            simpleName = fqn.substringAfterLast('.'),
            superFqn = superFqn,
            interfaces = interfaces,
            isAbstract = isAbstract,
            isInterface = isInterface,
            isRecord = isRecord,
            isSealed = isSealed,
            isEnum = isEnum,
            annotations = annotations,
            visibility = visibility,
            packageName = fqn.substringBeforeLast('.', ""),
            permittedSubclasses = permittedSubclasses,
            enumValues = enumValues
        )
        fake.putClass(descriptor)
        annotations.forEach {
            fake.putAnnotation(AnnotatedTarget.OnClass(fqn), AnnotationRef(it))
        }
        ClassScope(fake, fqn).apply(block)
        return descriptor
    }

    // Déclare une chaîne d'héritage classFqn -> superFqns (du plus proche au plus lointain).
    fun superChain(classFqn: String, vararg superFqns: String) {
        fake.putSuperChain(classFqn, superFqns.toList())
    }
}

class ClassScope(
    private val fake: FakeIntrospector,
    val ownerFqn: String
) {
    fun field(
        name: String,
        type: ResolvedType,
        annotations: List<String> = emptyList(),
        visibility: String = "private",
        declaredIn: String = ownerFqn,
        isFinal: Boolean = false,
        initializerExpression: String? = null
    ) {
        fake.putField(
            ownerFqn,
            ClassField(
                name = name,
                type = type,
                visibility = visibility,
                annotations = annotations,
                declaredIn = declaredIn,
                isFinal = isFinal,
                initializerExpression = initializerExpression
            )
        )
        annotations.forEach {
            fake.putAnnotation(AnnotatedTarget.OnField(ownerFqn, name), AnnotationRef(it))
        }
    }

    fun method(
        name: String,
        returns: ResolvedType = T("void"),
        annotations: List<String> = emptyList(),
        visibility: String = "public",
        declaredThrows: List<String> = emptyList(),
        isStatic: Boolean = false,
        body: String = "",
        block: MethodScope.() -> Unit = {}
    ): MethodSignature {
        val scope = MethodScope(fake, ownerFqn, name, returns, annotations, visibility, declaredThrows, isStatic, body)
        scope.block()
        val signature = scope.toSignature()
        fake.putMethod(ownerFqn, signature, body)
        annotations.forEach {
            fake.putAnnotation(
                AnnotatedTarget.OnMethod(ownerFqn, signature.canonical()),
                AnnotationRef(it)
            )
        }
        scope.commit(signature)
        return signature
    }
}

class MethodScope(
    private val fake: FakeIntrospector,
    private val ownerFqn: String,
    private val name: String,
    private val returns: ResolvedType,
    private val annotations: List<String>,
    private val visibility: String,
    private val declaredThrows: List<String>,
    private val isStatic: Boolean,
    private val body: String
) {
    private val params = mutableListOf<Parameter>()
    private val pendingCalls = mutableListOf<MethodCall>()
    private val pendingAccesses = mutableListOf<FieldAccess>()
    private val pendingAssignments = mutableListOf<FieldAssignment>()

    // BLOC 2 — éléments structurels du corps (STRATEGIE.md §3.1). Alimentent
    // FakeIntrospector.analyzeMethodBody. Vides par défaut : une méthode qui
    // n'en déclare aucun produit un MethodBodyAnalysis() neutre.
    private val bodyInstantiations = mutableListOf<ResolvedType>()
    private val bodyLambdas = mutableListOf<String>()
    private val bodyThrown = mutableListOf<ThrownExceptionRef>()
    private val bodyCaught = mutableListOf<CaughtExceptionRef>()
    private val bodyBranches = mutableListOf<ConditionalBranchRef>()
    private val bodyNonDet = mutableListOf<String>()

    fun param(name: String, type: ResolvedType, annotations: List<String> = emptyList()) {
        params.add(Parameter(name, type, annotations))
    }

    fun calls(targetType: String, methodName: String, vararg argTypes: String, isStatic: Boolean = false) {
        pendingCalls.add(MethodCall(targetType, methodName, argTypes.toList(), isStatic))
    }

    fun reads(ownerType: String, fieldName: String) {
        pendingAccesses.add(FieldAccess(ownerType, fieldName, write = false))
    }

    // ⚠️ FOOTGUN — N'UTILISE PAS POUR LES TESTS BLOC 7. Utilise `assigns(...)`.
    //
    // `writes()` n'enregistre QUE FieldAccess(write=true). Il NE peuple PAS
    // listFieldAssignments — donc SourceCollector ne voit aucune assignation
    // et le champ tombe en UNTESTABLE_AS_IS branche 11 (faux négatif silencieux).
    //
    // Garde uniquement pour les tests BLOC 1-6 (résolution de classes, mocks,
    // DTO, internalLogics) qui ne dépendent pas du contenu d'assignation.
    // Tout test qui exerce BLOC 7 ou le pipeline complet (PromptBuilder) doit
    // passer par `assigns(ownerType, fieldName, rhsExpression = "...")`.
    //
    // Historique : régressions corrigées en 4e-ζ (case91) puis en 5-β (case92,
    // case93, case94, case95) — d'où l'avertissement bien visible ici.
    fun writes(ownerType: String, fieldName: String) {
        pendingAccesses.add(FieldAccess(ownerType, fieldName, write = true))
    }

    // Enregistre à la fois une FieldAccess(write=true) et une FieldAssignment.
    // Les deux ports doivent être cohérents : §4.5 stratégie 10 vérifie l'ordre
    // relatif d'une lecture vs assignation, et listFieldAccesses doit voir
    // l'écriture au même offset que l'assignation correspondante. Les fixtures
    // existantes utilisaient `writes(...)` ; le nouveau verbe `assigns(...)`
    // est pour les tests BLOC 7 qui ont besoin du contexte enrichi.
    fun assigns(
        ownerType: String,
        fieldName: String,
        rhsExpression: String = "",
        rhsType: ResolvedType? = null,
        isConditional: Boolean = false,
        conditionIsNullCheck: Boolean = false
    ) {
        pendingAccesses.add(FieldAccess(ownerType, fieldName, write = true))
        pendingAssignments.add(
            FieldAssignment(
                ownerType = ownerType,
                fieldName = fieldName,
                rhsExpression = rhsExpression,
                rhsType = rhsType,
                isConditional = isConditional,
                conditionIsNullCheck = conditionIsNullCheck
            )
        )
    }

    // ── BLOC 2 — verbes d'analyse du corps (§3.1) ────────────────────────────

    // `new {fqName}(...)` détecté dans le corps — crawlé en DATA_STRUCTURE (§6d).
    fun instantiates(fqName: String) {
        bodyInstantiations.add(T(fqName))
    }

    // `throw new {typeFqn}("message")` — message non-null si littéral constant.
    fun throwsInBody(typeFqn: String, message: String? = null) {
        bodyThrown.add(ThrownExceptionRef(typeFqn, message))
    }

    // Bloc `catch` — `types` supporte le multi-catch.
    fun catchesInBody(vararg types: String) {
        bodyCaught.add(CaughtExceptionRef(types.toList()))
    }

    // Branche conditionnelle — kind ∈ {IF, SWITCH, TERNARY}.
    fun branch(kind: String, condition: String, constants: List<String> = emptyList()) {
        bodyBranches.add(ConditionalBranchRef(kind, condition, constants))
    }

    fun expectsLambda(functionalType: String) {
        bodyLambdas.add(functionalType)
    }

    fun nonDeterministic(source: String) {
        bodyNonDet.add(source)
    }

    fun toSignature(): MethodSignature = MethodSignature(
        name = name,
        returnType = returns,
        parameters = params.toList(),
        annotations = annotations,
        declaredThrows = declaredThrows,
        visibility = visibility,
        isStatic = isStatic
    )

    // Persiste les calls/accesses/assignments une fois la signature définitivement
    // créée. L'ordre d'insertion est conservé — c'est lui qui simule l'ordre
    // source garanti par le port CodeIntrospector (§4.5).
    fun commit(signature: MethodSignature) {
        pendingCalls.forEach { fake.putCall(signature, it) }
        pendingAccesses.forEach { fake.putFieldAccess(signature, it) }
        pendingAssignments.forEach { fake.putFieldAssignment(signature, it) }
        val analysis = MethodBodyAnalysis(
            instantiations = bodyInstantiations.toList(),
            expectedLambdas = bodyLambdas.toList(),
            thrownExceptions = bodyThrown.toList(),
            caughtExceptions = bodyCaught.toList(),
            conditionalBranches = bodyBranches.toList(),
            nonDeterministicSources = bodyNonDet.toList()
        )
        // N'enregistre que si au moins un élément a été déclaré — sinon le
        // fake retombe sur MethodBodyAnalysis() par défaut (cohérent partout).
        if (analysis != MethodBodyAnalysis()) fake.putBodyAnalysis(signature, analysis)
    }
}

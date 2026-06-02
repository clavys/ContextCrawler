package com.contextextractor.adapters.psi

import com.contextextractor.core.extractor.AnnotatedTarget
import com.contextextractor.core.extractor.AnnotationRef
import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.FieldAccess
import com.contextextractor.core.extractor.FieldAssignment
import com.contextextractor.core.extractor.CaughtExceptionRef
import com.contextextractor.core.extractor.ConditionalBranchRef
import com.contextextractor.core.extractor.MethodBodyAnalysis
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.extractor.ResolvedType
import com.contextextractor.core.extractor.ThrownExceptionRef
import com.contextextractor.core.extractor.SourceFile
import com.contextextractor.core.extractor.Symbol
import com.contextextractor.core.extractor.SymbolKind
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiDisjunctionType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLoopStatement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiSwitchBlock
import com.intellij.psi.PsiSwitchExpression
import com.intellij.psi.PsiSwitchStatement
import com.intellij.psi.PsiThisExpression
import com.intellij.psi.PsiThrowStatement
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

// Adapter PSI — implémentation côté IntelliJ du port CodeIntrospector.
// Toute la logique PSI vit ici ; le `core/` ne dépend de rien d'IntelliJ.
//
// Règles STRATEGIE.md §7.2 :
//  - tout appel PSI doit être enveloppé dans une ReadAction côté caller
//  - utiliser getCanonicalText() pour les types
//  - findMethodsByName(name, true) pour inclure les supers
//  - PsiTypeParameter pour détecter les génériques non résolus
class JavaPsiIntrospector(
    private val project: Project
) : CodeIntrospector {

    private val facade = JavaPsiFacade.getInstance(project)
    private val psiManager = PsiManager.getInstance(project)

    // Cache stable signature ↔ PsiMethod pour éviter de re-résoudre par
    // la suite (STRATEGIE.md §7.3 — cache léger côté adapter).
    private val methodCache = mutableMapOf<MethodSignature, PsiMethod>()
    private val classCache = mutableMapOf<String, PsiClass>()

    // -- 1. resolveSymbolAt ---------------------------------------------------

    override fun resolveSymbolAt(file: SourceFile, offset: Int): Symbol? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(file.path) ?: return null
        val psiFile = psiManager.findFile(virtualFile) ?: return null
        val element = psiFile.findElementAt(offset) ?: return null
        return symbolFor(element)
    }

    private fun symbolFor(element: PsiElement): Symbol? {
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        if (method != null) {
            val ownerFqn = method.containingClass?.qualifiedName ?: return null
            return Symbol(
                id = "${ownerFqn}#${method.signatureKey()}",
                fqn = "${ownerFqn}#${method.name}",
                kind = SymbolKind.METHOD
            )
        }
        val cls = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
        if (cls != null) {
            val fqn = cls.qualifiedName ?: return null
            return Symbol(id = fqn, fqn = fqn, kind = SymbolKind.CLASS)
        }
        return null
    }

    // -- 2. findEnclosingMethod -----------------------------------------------

    override fun findEnclosingMethod(symbol: Symbol): MethodSignature? {
        if (symbol.kind != SymbolKind.METHOD) return null
        // Format de l'id : "owner.fqn#methodName(arg1,arg2)"
        val (ownerFqn, signatureKey) = symbol.id.split('#', limit = 2).let {
            if (it.size != 2) return null
            it[0] to it[1]
        }
        val cls = findPsiClass(ownerFqn) ?: return null
        val method = cls.methods.firstOrNull { it.signatureKey() == signatureKey } ?: return null
        return method.toSignature()
    }

    // -- 3. resolveClass ------------------------------------------------------

    override fun resolveClass(fqn: String): ClassDescriptor? {
        val psiClass = findPsiClass(fqn) ?: return null
        return psiClass.toDescriptor()
    }

    private fun findPsiClass(fqn: String): PsiClass? {
        classCache[fqn]?.let { return it }
        val scope = GlobalSearchScope.allScope(project)
        val resolved = facade.findClass(fqn, scope) ?: return null
        classCache[fqn] = resolved
        return resolved
    }

    // -- 4. listMethodCalls ---------------------------------------------------

    override fun listMethodCalls(method: MethodSignature): List<MethodCall> {
        val psiMethod = methodCache[method] ?: return emptyList()
        val body = psiMethod.body ?: return emptyList()
        val collected = mutableListOf<MethodCall>()
        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                val resolved = expression.resolveMethod() ?: return
                val targetType = resolved.containingClass?.qualifiedName ?: return
                val argTypes = expression.argumentList.expressionTypes.map {
                    PsiTypeMapper.toResolved(it).fqName
                }
                val isStatic = resolved.hasModifierProperty(PsiModifier.STATIC)
                collected.add(MethodCall(targetType, resolved.name, argTypes, isStatic))
            }
        })
        return collected
    }

    // -- 5. listFieldAccesses -------------------------------------------------

    override fun listFieldAccesses(method: MethodSignature): List<FieldAccess> {
        val psiMethod = methodCache[method] ?: return emptyList()
        val body = psiMethod.body ?: return emptyList()
        val collected = mutableListOf<FieldAccess>()
        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                super.visitReferenceExpression(expression)
                if (expression is PsiMethodCallExpression) return
                // On ignore les expressions `Foo.class` (PsiClassObjectAccessExpression
                // contient une PsiReferenceExpression interne mais ne correspond
                // pas à un accès champ d'instance).
                if (expression.parent is PsiClassObjectAccessExpression) return
                val resolved = expression.resolve() as? PsiField ?: return
                val ownerType = resolved.containingClass?.qualifiedName ?: return
                val isWrite = expression.isLeftHandOfAssignment()
                collected.add(FieldAccess(ownerType, resolved.name, isWrite))
            }
        })
        return collected
    }

    private fun PsiReferenceExpression.isLeftHandOfAssignment(): Boolean {
        val assignment = parent as? PsiAssignmentExpression ?: return false
        return assignment.lExpression === this
    }

    // -- 5bis. listFieldAssignments -------------------------------------------

    override fun listFieldAssignments(method: MethodSignature): List<FieldAssignment> {
        val psiMethod = methodCache[method] ?: return emptyList()
        val body = psiMethod.body ?: return emptyList()
        val collected = mutableListOf<FieldAssignment>()
        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
                super.visitAssignmentExpression(expression)
                val target = expression.lExpression as? PsiReferenceExpression ?: return
                val resolvedField = target.resolve() as? PsiField ?: return
                val ownerType = resolvedField.containingClass?.qualifiedName ?: return
                val rhs = expression.rExpression
                val rhsExpression = rhs?.text.orEmpty()
                val rhsType = rhs?.type?.let { PsiTypeMapper.toResolved(it) }
                val nearestCondition = nearestConditional(expression)
                val isConditional = nearestCondition != null
                val conditionIsNullCheck =
                    isConditional && conditionChecksFieldNull(nearestCondition, resolvedField.name)
                collected.add(
                    FieldAssignment(
                        ownerType = ownerType,
                        fieldName = resolvedField.name,
                        rhsExpression = rhsExpression,
                        rhsType = rhsType,
                        isConditional = isConditional,
                        conditionIsNullCheck = conditionIsNullCheck
                    )
                )
            }
        })
        return collected
    }

    // Trouve le PsiIfStatement / PsiLoopStatement / PsiSwitchBlock /
    // PsiConditionalExpression englobant le plus proche — null si l'assignation
    // est au top-level du corps de méthode.
    private fun nearestConditional(element: PsiElement): PsiElement? {
        var current: PsiElement? = element.parent
        while (current != null) {
            when (current) {
                is PsiIfStatement,
                is PsiLoopStatement,
                is PsiSwitchBlock,
                is PsiConditionalExpression -> return current
                is PsiMethod -> return null
            }
            current = current.parent
        }
        return null
    }

    // Vrai ssi la condition contient `<fieldName> == null` ou `null == <fieldName>`
    // — couvre le pattern lazy-init `if (this.x == null) this.x = ...`.
    // Le ternaire `(this.x == null) ? new X() : this.x` tombe ici aussi via la
    // PsiConditionalExpression englobante. Volontairement strict : pas de
    // détection des patterns Optional ou ternaires composés (V1).
    private fun conditionChecksFieldNull(conditional: PsiElement?, fieldName: String): Boolean {
        val condition: PsiElement = when (conditional) {
            is PsiIfStatement -> conditional.condition ?: return false
            is PsiConditionalExpression -> conditional.condition
            is PsiLoopStatement -> return false // peu de cas pratiques pour V1
            is PsiSwitchBlock -> return false
            else -> return false
        }
        var found = false
        condition.accept(object : JavaRecursiveElementVisitor() {
            override fun visitBinaryExpression(expression: PsiBinaryExpression) {
                super.visitBinaryExpression(expression)
                if (expression.operationSign.text != "==") return
                val left = expression.lOperand
                val right = expression.rOperand ?: return
                val (refSide, otherSide) = when {
                    isFieldRef(left, fieldName) -> left to right
                    isFieldRef(right, fieldName) -> right to left
                    else -> return
                }
                @Suppress("UNUSED_VARIABLE")
                val unused = refSide
                if (otherSide is PsiLiteralExpression && otherSide.value == null) found = true
            }
        })
        return found
    }

    private fun isFieldRef(expr: PsiElement?, fieldName: String): Boolean {
        val ref = expr as? PsiReferenceExpression ?: return false
        if (ref.referenceName != fieldName) return false
        // Accepte `this.x` ou `x` non qualifié — les deux résolvent vers le
        // même PsiField si le champ existe.
        val qualifier = ref.qualifierExpression
        if (qualifier != null && qualifier !is PsiThisExpression) return false
        val resolved = ref.resolve() as? PsiField ?: return false
        return resolved.name == fieldName
    }

    // -- 6. listFields --------------------------------------------------------

    override fun listFields(cls: ClassDescriptor): List<ClassField> {
        val psiClass = findPsiClass(cls.fqn) ?: return emptyList()
        // Filtrer les PsiEnumConstant — ils sont retournés par PsiClass.fields
        // pour les enums, mais ne sont pas des champs d'instance au sens BLOC 3.
        return psiClass.fields.filter { it !is PsiEnumConstant }.map { field ->
            ClassField(
                name = field.name,
                type = PsiTypeMapper.toResolved(field.type),
                visibility = field.visibilityKeyword(),
                annotations = field.modifierList?.annotationFqns().orEmpty(),
                declaredIn = cls.fqn,
                isFinal = field.hasModifierProperty(PsiModifier.FINAL),
                initializerExpression = field.initializer?.text
            )
        }
    }

    // -- 6bis. listMethods ----------------------------------------------------

    override fun listMethods(cls: ClassDescriptor): List<MethodSignature> {
        val psiClass = findPsiClass(cls.fqn) ?: return emptyList()
        return psiClass.methods.map { it.toSignature() }
    }

    // -- 7. listSuperClasses --------------------------------------------------

    override fun listSuperClasses(cls: ClassDescriptor): List<ClassDescriptor> {
        val psiClass = findPsiClass(cls.fqn) ?: return emptyList()
        val collected = mutableListOf<ClassDescriptor>()
        var current: PsiClass? = psiClass.superClass
        // Ignorer java.lang.Object (toujours implicite).
        while (current != null && current.qualifiedName != "java.lang.Object") {
            collected.add(current.toDescriptor())
            current = current.superClass
        }
        return collected
    }

    // -- 8. readMethodBody ----------------------------------------------------

    override fun readMethodBody(method: MethodSignature): String {
        val psiMethod = methodCache[method] ?: return ""
        return psiMethod.body?.text.orEmpty()
    }

    // -- 8bis. analyzeMethodBody ----------------------------------------------

    // STRATEGIE.md §3.1 BLOC 2 — un seul parcours AST collecte les éléments
    // structurels du corps. À n'appeler que sur des méthodes intra-SUT (la
    // frontière §3.3 « STOP » est garantie par RecursiveDeepStrategy).
    override fun analyzeMethodBody(method: MethodSignature): MethodBodyAnalysis {
        val psiMethod = methodCache[method] ?: return MethodBodyAnalysis()
        val body = psiMethod.body ?: return MethodBodyAnalysis()

        val instantiations = mutableListOf<ResolvedType>()
        val lambdas = mutableListOf<String>()
        val thrown = mutableListOf<ThrownExceptionRef>()
        val caught = mutableListOf<CaughtExceptionRef>()
        val branches = mutableListOf<ConditionalBranchRef>()
        val nonDeterministic = mutableListOf<String>()

        body.accept(object : JavaRecursiveElementVisitor() {

            // `new Foo(...)` — sauf les exceptions instanciées directement dans
            // un `throw` (capturées séparément ci-dessous, pas des DTO à bâtir).
            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                if (expression.parent is PsiThrowStatement) return
                if (expression.classReference == null) return
                expression.type?.let { instantiations.add(PsiTypeMapper.toResolved(it)) }
            }

            override fun visitLambdaExpression(expression: PsiLambdaExpression) {
                super.visitLambdaExpression(expression)
                expression.functionalInterfaceType?.let {
                    lambdas.add(PsiTypeMapper.toResolved(it).fqName)
                }
            }

            override fun visitMethodReferenceExpression(expression: PsiMethodReferenceExpression) {
                super.visitMethodReferenceExpression(expression)
                expression.functionalInterfaceType?.let {
                    lambdas.add(PsiTypeMapper.toResolved(it).fqName)
                }
            }

            override fun visitThrowStatement(statement: PsiThrowStatement) {
                super.visitThrowStatement(statement)
                val ex = statement.exception ?: return
                val typeFqn = ex.type?.let { PsiTypeMapper.toResolved(it).fqName }
                    ?: return
                // Message capturé seulement si littéral constant (§3.1).
                val message = (ex as? PsiNewExpression)
                    ?.argumentList?.expressions?.firstOrNull()
                    ?.let { it as? PsiLiteralExpression }
                    ?.value as? String
                thrown.add(ThrownExceptionRef(typeFqn, message))
            }

            override fun visitTryStatement(statement: PsiTryStatement) {
                super.visitTryStatement(statement)
                statement.catchSections.forEach { section ->
                    caught.add(catchSectionRef(section))
                }
            }

            override fun visitIfStatement(statement: PsiIfStatement) {
                super.visitIfStatement(statement)
                val condition = statement.condition ?: return
                branches.add(branchRef("IF", condition))
            }

            override fun visitConditionalExpression(expression: PsiConditionalExpression) {
                super.visitConditionalExpression(expression)
                branches.add(branchRef("TERNARY", expression.condition))
            }

            override fun visitSwitchStatement(statement: PsiSwitchStatement) {
                super.visitSwitchStatement(statement)
                statement.expression?.let { branches.add(branchRef("SWITCH", it)) }
            }

            override fun visitSwitchExpression(expression: PsiSwitchExpression) {
                super.visitSwitchExpression(expression)
                expression.expression?.let { branches.add(branchRef("SWITCH", it)) }
            }

            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                val resolved = expression.resolveMethod() ?: return
                val owner = resolved.containingClass?.qualifiedName ?: return
                val key = "$owner.${resolved.name}"
                if (key in NON_DETERMINISTIC_CALLS) nonDeterministic.add(key)
            }
        })

        return MethodBodyAnalysis(
            instantiations = instantiations,
            expectedLambdas = lambdas.distinct(),
            thrownExceptions = thrown,
            caughtExceptions = caught,
            conditionalBranches = branches,
            nonDeterministicSources = nonDeterministic.distinct()
        )
    }

    // Multi-catch supporté : `PsiDisjunctionType` énumère les types alternatifs.
    private fun catchSectionRef(section: PsiCatchSection): CaughtExceptionRef {
        val declared = section.parameter?.type
        val types = when (declared) {
            is PsiDisjunctionType -> declared.disjunctions.map { PsiTypeMapper.toResolved(it).fqName }
            null -> emptyList()
            else -> listOf(PsiTypeMapper.toResolved(declared).fqName)
        }
        val calls = mutableListOf<String>()
        section.catchBlock?.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                val resolved = expression.resolveMethod() ?: return
                val owner = resolved.containingClass?.qualifiedName ?: return
                calls.add("$owner.${resolved.name}")
            }
        })
        return CaughtExceptionRef(types, calls.distinct())
    }

    // Texte source de la condition + littéraux constants qui y apparaissent.
    private fun branchRef(kind: String, condition: PsiExpression): ConditionalBranchRef {
        val constants = mutableListOf<String>()
        condition.accept(object : JavaRecursiveElementVisitor() {
            override fun visitLiteralExpression(expression: PsiLiteralExpression) {
                super.visitLiteralExpression(expression)
                constants.add(expression.text)
            }
        })
        return ConditionalBranchRef(kind, condition.text, constants.distinct())
    }

    // -- 9. listAnnotations ---------------------------------------------------

    override fun listAnnotations(target: AnnotatedTarget): List<AnnotationRef> {
        return when (target) {
            is AnnotatedTarget.OnClass ->
                findPsiClass(target.fqn)?.modifierList?.annotationRefs().orEmpty()
            is AnnotatedTarget.OnMethod -> {
                val cls = findPsiClass(target.fqn) ?: return emptyList()
                val method = cls.methods.firstOrNull { it.signatureKey() == target.signature }
                method?.modifierList?.annotationRefs().orEmpty()
            }
            is AnnotatedTarget.OnField -> {
                val cls = findPsiClass(target.fqn) ?: return emptyList()
                val field = cls.fields.firstOrNull { it.name == target.name }
                field?.modifierList?.annotationRefs().orEmpty()
            }
            is AnnotatedTarget.OnParameter -> {
                val cls = findPsiClass(target.fqn) ?: return emptyList()
                val method = cls.methods.firstOrNull { it.signatureKey() == target.signature }
                val parameter = method?.parameterList?.parameters?.getOrNull(target.index)
                parameter?.modifierList?.annotationRefs().orEmpty()
            }
        }
    }

    // -- Helpers PSI internes -------------------------------------------------

    private fun PsiClass.toDescriptor(): ClassDescriptor {
        val fqn = qualifiedName ?: name ?: ""
        val isEnumClass = isEnum
        val isSealedClass = hasModifierProperty(PsiModifier.SEALED)
        // PsiClass.permitsList est exposé via permitsListTypes() côté plate-forme
        // — on extrait les FQN des types autorisés. Vide pour une classe non
        // sealed.
        val permitted: List<String> = if (isSealedClass) {
            permitsListTypes.mapNotNull { it.resolve()?.qualifiedName }
        } else emptyList()
        // Constantes d'enum dans l'ordre de déclaration. PsiClass.fields contient
        // à la fois les PsiEnumConstant et les vrais champs déclarés ; on filtre.
        val enumNames: List<String> = if (isEnumClass) {
            fields.filterIsInstance<PsiEnumConstant>().map { it.name }
        } else emptyList()
        return ClassDescriptor(
            fqn = fqn,
            simpleName = name ?: fqn.substringAfterLast('.'),
            superFqn = superClass?.qualifiedName?.takeIf { it != "java.lang.Object" },
            interfaces = interfaces.mapNotNull { it.qualifiedName },
            isAbstract = hasModifierProperty(PsiModifier.ABSTRACT),
            isInterface = isInterface,
            isRecord = isRecord,
            isSealed = isSealedClass,
            isEnum = isEnumClass,
            annotations = modifierList?.annotationFqns().orEmpty(),
            visibility = visibilityKeyword(),
            packageName = fqn.substringBeforeLast('.', missingDelimiterValue = ""),
            permittedSubclasses = permitted,
            enumValues = enumNames
        )
    }

    private fun PsiMethod.toSignature(): MethodSignature {
        // Convention « <init> » pour les constructeurs — alignée sur les fixtures
        // de FakeIntrospector et utilisée par RecursiveDeepStrategy pour repérer
        // les constructeurs candidats sans dépendre du nom de la classe.
        val signature = MethodSignature(
            name = if (isConstructor) "<init>" else name,
            returnType = returnType?.let { PsiTypeMapper.toResolved(it) }
                ?: ResolvedType("void", "void"),
            parameters = parameterList.parameters.map { p ->
                Parameter(
                    name = p.name,
                    type = PsiTypeMapper.toResolved(p.type),
                    annotations = p.modifierList?.annotationFqns().orEmpty()
                )
            },
            annotations = modifierList.annotationFqns(),
            declaredThrows = throwsList.referencedTypes.mapNotNull { it.canonicalText },
            visibility = visibilityKeyword(),
            isStatic = hasModifierProperty(PsiModifier.STATIC)
        )
        // On garde la table de correspondance signature→PsiMethod pour les
        // appels ultérieurs (listMethodCalls, listFieldAccesses, readMethodBody).
        methodCache[signature] = this
        return signature
    }

    // Clé canonique compatible avec MethodSignature.canonical().
    private fun PsiMethod.signatureKey(): String =
        "$name(${parameterList.parameters.joinToString(",") {
            PsiTypeMapper.toResolved(it.type).fqName
        }})"

    private fun PsiModifierList.annotationFqns(): List<String> =
        annotations.mapNotNull { it.qualifiedName }

    private fun PsiModifierList.annotationRefs(): List<AnnotationRef> =
        annotations.mapNotNull { it.toAnnotationRef() }

    private fun PsiAnnotation.toAnnotationRef(): AnnotationRef? {
        val fqn = qualifiedName ?: return null
        val attrs = parameterList.attributes.associate {
            (it.name ?: "value") to (it.value?.text ?: "")
        }
        return AnnotationRef(fqn = fqn, attributes = attrs)
    }

    private fun PsiClass.visibilityKeyword(): String = visibilityFor(modifierList)

    private fun PsiMethod.visibilityKeyword(): String = visibilityFor(modifierList)

    private fun PsiField.visibilityKeyword(): String = visibilityFor(modifierList)

    private fun visibilityFor(modifiers: PsiModifierList?): String {
        modifiers ?: return "package-private"
        return when {
            modifiers.hasModifierProperty(PsiModifier.PUBLIC) -> "public"
            modifiers.hasModifierProperty(PsiModifier.PROTECTED) -> "protected"
            modifiers.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
            else -> "package-private"
        }
    }

    companion object {
        // Sources non-déterministes — STRATEGIE.md §3.1 BLOC 2 « SourcesIndéter ».
        // Clé = `${classFqn}.${methodName}`. Le LLM doit savoir les neutraliser
        // (Clock injectable, mockStatic, valeur fixe) pour un test reproductible.
        private val NON_DETERMINISTIC_CALLS = setOf(
            "java.time.LocalDateTime.now",
            "java.time.LocalDate.now",
            "java.time.LocalTime.now",
            "java.time.Instant.now",
            "java.time.ZonedDateTime.now",
            "java.time.OffsetDateTime.now",
            "java.util.UUID.randomUUID",
            "java.lang.Math.random",
            "java.lang.System.currentTimeMillis",
            "java.lang.System.nanoTime"
        )
    }
}

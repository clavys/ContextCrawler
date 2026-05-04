package com.contextextractor.adapters.psi

import com.contextextractor.core.extractor.AnnotatedTarget
import com.contextextractor.core.extractor.AnnotationRef
import com.contextextractor.core.extractor.ClassDescriptor
import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.FieldAccess
import com.contextextractor.core.extractor.MethodCall
import com.contextextractor.core.extractor.MethodSignature
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.extractor.ResolvedType
import com.contextextractor.core.extractor.SourceFile
import com.contextextractor.core.extractor.Symbol
import com.contextextractor.core.extractor.SymbolKind
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList
import com.intellij.psi.PsiReferenceExpression
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

    // -- 6. listFields --------------------------------------------------------

    override fun listFields(cls: ClassDescriptor): List<ClassField> {
        val psiClass = findPsiClass(cls.fqn) ?: return emptyList()
        return psiClass.fields.map { field ->
            ClassField(
                name = field.name,
                type = PsiTypeMapper.toResolved(field.type),
                visibility = field.visibilityKeyword(),
                annotations = field.modifierList?.annotationFqns().orEmpty(),
                declaredIn = cls.fqn
            )
        }
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
        return ClassDescriptor(
            fqn = fqn,
            simpleName = name ?: fqn.substringAfterLast('.'),
            superFqn = superClass?.qualifiedName?.takeIf { it != "java.lang.Object" },
            interfaces = interfaces.mapNotNull { it.qualifiedName },
            isAbstract = hasModifierProperty(PsiModifier.ABSTRACT),
            isInterface = isInterface,
            isRecord = isRecord,
            isSealed = hasModifierProperty(PsiModifier.SEALED),
            isEnum = isEnum,
            annotations = modifierList?.annotationFqns().orEmpty(),
            visibility = visibilityKeyword(),
            packageName = fqn.substringBeforeLast('.', missingDelimiterValue = "")
        )
    }

    private fun PsiMethod.toSignature(): MethodSignature {
        val signature = MethodSignature(
            name = name,
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
            visibility = visibilityKeyword()
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
}

package com.contextextractor.fakes

import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.CodeIntrospector
import com.contextextractor.core.extractor.MethodSignature

// Helpers utilisés uniquement par les tests — chacun passe par les méthodes
// publiques du port `CodeIntrospector` pour rester un client conforme.

// Resolve la classe puis appelle `listFields()` du port. Retourne une liste
// vide si la classe est inconnue (n'expose pas null aux assertions).
fun CodeIntrospector.listFieldsOf(fqn: String): List<ClassField> =
    resolveClass(fqn)?.let { listFields(it) }.orEmpty()

// Resolve la classe puis appelle `listMethods()` du port. Même contrat que
// listFieldsOf — les fixtures stockent les constructeurs sous le nom "<init>".
fun CodeIntrospector.listMethodsOf(fqn: String): List<MethodSignature> =
    resolveClass(fqn)?.let { listMethods(it) }.orEmpty()

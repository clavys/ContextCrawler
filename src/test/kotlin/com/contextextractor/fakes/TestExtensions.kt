package com.contextextractor.fakes

import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.CodeIntrospector

// Helpers utilisés uniquement par les tests — chacun passe par les méthodes
// publiques du port `CodeIntrospector` pour rester un client conforme.

// Resolve la classe puis appelle `listFields()` du port. Retourne une liste
// vide si la classe est inconnue (n'expose pas null aux assertions).
fun CodeIntrospector.listFieldsOf(fqn: String): List<ClassField> =
    resolveClass(fqn)?.let { listFields(it) }.orEmpty()

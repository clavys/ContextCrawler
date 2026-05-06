package com.contextextractor.core.model.init

import com.contextextractor.core.extractor.MethodSignature

// Identifiant d'une méthode dans le graphe d'appels intra-SUT (BLOC 7).
//
// La spec §4.3 utilise « Methode » comme type abstrait pour les sommets ; ici
// on l'instancie en `(classFqn, canonical)` pour rester sérialisable et
// distinguer deux méthodes de même signature déclarées sur deux classes
// différentes de la hiérarchie (cas des overrides).
//
// **Localisation** : `core/model/init/` car ce type apparaît dans `InitPath`
// (chemin candidat retourné par EntryPointFinder) — précédent `VisitKey`
// vit dans `core/classifier/` pour la même raison (artifact d'algorithme
// publié dans le modèle de données partagé).
//
// **Contrat de normalisation** : `canonical` est produit par
// [MethodSignature.canonical] — `name(fqn1,fqn2,…)` — donc :
//   • pour un constructeur, `canonical = "<init>(java.lang.String,…)"`
//     (l'adapter PSI normalise déjà `isConstructor` en nom `<init>`)
//   • la chaîne est strictement identique à la valeur stockée dans
//     [com.contextextractor.core.classifier.VisitKey.methodCanonical]
//     en mode INTERNAL_LOGIC
//
// EntryPointFinder doit réutiliser CE type ou produire des chaînes via
// `MethodSignature.canonical()`. Toute divergence de format = lookup silencieux
// vide → UNTESTABLE_AS_IS injustifié.
data class MethodKey(
    val classFqn: String,
    val canonical: String
) {
    companion object {
        fun of(classFqn: String, signature: MethodSignature): MethodKey =
            MethodKey(classFqn, signature.canonical())
    }
}

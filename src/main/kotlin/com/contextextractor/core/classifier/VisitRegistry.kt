package com.contextextractor.core.classifier

// Registre anti-cycle de la stratégie récursive — STRATEGIE.md §2.2.
// Chaque mode de visite (SUT_BOOTSTRAP, INTERNAL_LOGIC, …) maintient son propre
// espace de clés : visiter `OrderRepository` en MOCK_EXTERNAL ne bloque pas
// une visite ultérieure en DATA_STRUCTURE (cas légitime quand un type est mocké
// puis instancié comme valeur de retour).
//
// Implémentation : un simple Set<VisitKey>. La séparation par mode est portée
// par le champ `mode` de VisitKey, donc aucune structure imbriquée requise.
class VisitRegistry {

    private val seen: MutableSet<VisitKey> = HashSet()

    // True si la clé n'a jamais été enregistrée — l'enregistre dans la foulée
    // pour que le prochain appel renvoie false. Idiome standard de la récursion :
    //   if (!registry.isNew(key)) return
    fun isNew(key: VisitKey): Boolean = seen.add(key)

    fun contains(key: VisitKey): Boolean = key in seen

    fun add(key: VisitKey) {
        seen.add(key)
    }

    fun size(): Int = seen.size
}

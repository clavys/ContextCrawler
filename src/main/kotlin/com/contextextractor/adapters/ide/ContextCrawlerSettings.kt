package com.contextextractor.adapters.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

// Settings persistantes du plugin — ARCHITECTURE.md §8 (IntellijSettingsSource).
//
// **Périmètre V1** : 4 champs essentiels que l'utilisateur DOIT pouvoir
// surcharger sans éditer un YAML :
//   • strategy   — id de la ContextStrategy active (ex: "recursive-deep")
//   • outputMode — COPY / LLM_CALL / ASK. **Défaut COPY** : verrou pivot 6-γ —
//     sans ça, le premier `runIde` produirait un comportement non fonctionnel
//     car ExtractContextAction (étape 7) n'aurait pas de routage vers
//     PromptCopyDialog par défaut.
//   • llm.provider — claude / openai / claude-code-cli (V1 : claude)
//   • llm.model    — claude-sonnet-4-6 (V1 défaut Anthropic)
//
// **Tout le reste** (templates, classification, budget) reste en YAML — c'est
// trop verbeux pour une UI Settings minimale et ces champs sont moins souvent
// modifiés que provider/model.
//
// **Cycle de vie persistant** déféré au gate runIde — comme JavaPsiIntrospector
// pour le wiring testFramework Platform (cf CLAUDE.md étape 3, limite connue).
// Ce qui est testable purement (mapping State → Map<String, Any?>) vit dans
// [IntellijSettingsSource], ce qui est IDE-coupled (annotations @State, @Service,
// chargement automatique) vit dans cette classe. Le test pur instancie un
// `State()` directement sans passer par le service.
@Service
@State(
    name = "ContextCrawlerSettings",
    storages = [Storage("contextcrawler.xml")]
)
class ContextCrawlerSettings : PersistentStateComponent<ContextCrawlerSettings.State> {

    // POKO mutable — exigé par le sérialiseur XmlSerializerUtil de la plateforme
    // (qui réfléchit sur les `var` publiques). Ne PAS basculer en `val` ou en
    // `data class` immutable : la plateforme ne saura plus désérialiser.
    class State {
        @JvmField var strategy: String = "recursive-deep"

        // Verrou pivot : COPY = mode par défaut. Cf doc de classe.
        @JvmField var outputMode: String = "COPY"

        @JvmField var llmProvider: String = "claude"
        @JvmField var llmModel: String = "claude-sonnet-4-6"
    }

    private var internalState: State = State()

    override fun getState(): State = internalState

    override fun loadState(state: State) {
        // Fusion in-place via le serializer plateforme — gère les champs
        // ajoutés/supprimés (les nouveaux gardent leur défaut, les supprimés
        // sont ignorés). Évite d'écraser brutalement avec un objet partiel.
        XmlSerializerUtil.copyBean(state, internalState)
    }

    companion object {
        fun getInstance(): ContextCrawlerSettings =
            ApplicationManager.getApplication().getService(ContextCrawlerSettings::class.java)
    }
}

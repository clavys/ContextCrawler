package com.contextextractor.adapters.llm

import com.contextextractor.core.llm.PromptRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Tests JUnit 5 pour ClaudeCodeClient. Portent uniquement sur ce qui est
// portable et déterministe :
//   1. Détection PATH absent (executable inexistant → PromptResponse.error
//      peuplé, text vide, model conservé).
//   2. Contrat de surface PromptResponse (model passe à travers, id stable).
//
// **Pas testés ici** (validation manuelle via runIde) :
//   • Chemin nominal succès — dépend d'une CLI claude réelle dans le PATH,
//     non garantie sur la machine de build / CI.
//   • Timeout — nécessiterait un fake process qui bloque, peu portable.
//   • Encodage UTF-8 — dépend de la locale système.
class ClaudeCodeClientTest {

    @Test
    fun `complete returns error response when executable is not on PATH`() {
        // Nom volontairement absurde — sur Unix garantit IOException
        // ("No such file"), sur Windows (où le code wrap via cmd /c) garantit
        // un exit code ≠ 0 ("'claude-…' is not recognized…"). Les deux
        // branches du client peuplent `error` ; le contenu exact diffère
        // selon la plateforme, donc on n'asserte que le contrat sémantique.
        val client = ClaudeCodeClient(
            executable = "claude-does-not-exist-xyz-${System.nanoTime()}"
        )
        val resp = client.complete(
            PromptRequest(prompt = "ping", model = "claude-opus-4-7")
        )

        // Contrat error : `error` peuplé, `text` vide, `model` conservé.
        // Plateforme-agnostique : peu importe si on emprunte la branche
        // IOException (Unix) ou exit-code (Windows via cmd /c), l'utilisateur
        // doit voir une erreur structurée et pas un texte parasite.
        assertNotNull(resp.error,
            "PromptResponse.error doit être peuplé quand l'exécutable n'existe pas")
        assertEquals("", resp.text,
            "text DOIT être vide en cas d'erreur (sémantique mutuellement exclusive)")
        assertEquals("claude-opus-4-7", resp.model,
            "model du PromptRequest doit transiter inchangé dans la réponse")
    }

    @Test
    fun `client exposes stable id`() {
        // L'id sert au routage Settings → backend choisi. Doit être stable
        // pour permettre une persistance par identifiant.
        val client = ClaudeCodeClient()
        assertEquals("claude-code", client.id)
    }

    @Test
    fun `complete preserves the request model in the error response`() {
        // Verrou : pour les downstream consumers qui pourraient logger la
        // réponse, le model doit toujours être présent — y compris en erreur.
        val client = ClaudeCodeClient(executable = "absolutely-not-a-real-binary")
        val resp = client.complete(
            PromptRequest(prompt = "x", model = "custom-model-name")
        )
        assertEquals("custom-model-name", resp.model)
        assertFalse(resp.text.isNotEmpty(),
            "pas de fuite de texte parasite quand le process refuse de démarrer")
        assertNull(resp.finishReason,
            "finishReason ne doit PAS être renseigné en cas d'erreur (réservé au succès)")
    }
}

package com.contextextractor.adapters.llm

import com.contextextractor.core.llm.LlmClient
import com.contextextractor.core.llm.PromptRequest
import com.contextextractor.core.llm.PromptResponse
import java.io.IOException
import java.util.concurrent.TimeUnit

// Adapter LLM : délègue à la CLI `claude` installée localement via ProcessBuilder.
// Voir CLAUDE.md "Étape 8 — Option B (ClaudeCodeClient)".
//
// **Pourquoi cette implémentation** : utilise l'abonnement Claude existant de
// l'utilisateur (pas de clé API à gérer, pas de PasswordSafe nécessaire). V1
// utilise le mode print `-p` non-streaming : un seul `process.waitFor()` puis
// lecture intégrale de stdout. Le streaming est repoussé à V2.
//
// **Contrat avec l'appelant** : NE PAS appeler sur l'EDT. ProcessBuilder bloque
// le thread jusqu'à `waitFor()` ; doit vivre dans un `Task.Backgroundable`.
//
// **Encodage** : stdout/stderr lus en UTF-8 explicite. Sans ça, Windows
// fallback sur cp1252 et certains caractères du prompt (`→`, `←`, accents
// dans les commentaires français) se corromperaient à l'aller-retour.
//
// **Timeout** : la durée par défaut (120s) doit couvrir une réponse claude
// typique sur un prompt de ~50k tokens. Réglable via le constructeur — le
// settings IDE pourra l'exposer en V1.1 si nécessaire.
//
// **Tests** : le client accepte `executable` paramétrable pour permettre des
// tests JUnit qui ciblent une commande inexistante (vérifie la branche
// "claude pas dans le PATH"). Le chemin nominal (claude réellement présent)
// est validé manuellement via `./gradlew runIde` — pas de fixture portable
// pour le succès, l'absence d'exécutable est suffisamment représentative.
class ClaudeCodeClient(
    private val executable: String = "claude",
    private val timeoutSeconds: Long = 120
) : LlmClient {

    override val id: String = "claude-code"

    override fun complete(req: PromptRequest): PromptResponse {
        // Sur Windows, ProcessBuilder ne passe PAS par le shell utilisateur et
        // ne voit donc PAS les entrées PATH ajoutées par les installateurs
        // (npm global, scoop, winget…) si elles sont dans le profil utilisateur
        // plutôt que dans System Environment. Wrapper via `cmd /c` force le
        // résolveur de commande de Windows à parcourir le PATH utilisateur
        // complet. Sur Unix, ProcessBuilder utilise execvp() qui voit déjà le
        // PATH du process parent — pas de wrap nécessaire.
        //
        // **Prompt sur stdin, PAS via `-p <prompt>`** : passer un prompt de
        // 30-50k caractères en argument cassait sur Windows à cause du parsing
        // cmd (les backticks markdown, sauts de ligne et guillemets du body
        // Java mangeaient l'arg ; la CLI tombait en mode interactif avec
        // « no stdin data received in 3s »). Stdin est binary-safe et
        // contourne intégralement la couche shell. La CLI Claude accepte le
        // prompt sur stdin sans flag — c'est le pattern documenté pour les
        // gros prompts.
        val command = if (isWindows()) {
            listOf("cmd", "/c", executable)
        } else {
            listOf(executable)
        }
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        val process: Process = try {
            pb.start()
        } catch (e: IOException) {
            // ProcessBuilder.start() jette IOException si le système refuse de
            // démarrer le process. Sur Windows avec le wrap `cmd /c`, ce cas
            // est rare (cmd existe toujours) ; l'échec "claude non trouvé"
            // remontera plutôt via exitCode ≠ 0 avec un message stderr de cmd.
            return PromptResponse(
                text = "",
                model = req.model,
                error = "Impossible de lancer `$executable` : ${e.message ?: e.javaClass.simpleName}. " +
                    "Vérifiez que la CLI Claude est installée et dans le PATH " +
                    "(${if (isWindows()) "where claude" else "which claude"})."
            )
        }

        // Étape 1 — écrire le prompt sur stdin et FERMER stdin pour signaler
        // l'EOF. Sans la fermeture, la CLI continuerait à attendre des données
        // et le `waitFor` plus bas ne se terminerait jamais. Le `use { }`
        // garantit close() même si write() jette.
        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(req.prompt)
            }
        } catch (e: IOException) {
            process.destroyForcibly()
            return PromptResponse(
                text = "",
                model = req.model,
                error = "Échec de l'écriture du prompt sur stdin de `$executable` : " +
                    (e.message ?: e.javaClass.simpleName)
            )
        }

        // Étape 2 — drainer stdout AVANT le waitFor. Si le buffer pipe stdout
        // se remplit pendant que la CLI génère sa réponse, la CLI bloque sur
        // sa propre écriture jusqu'à ce qu'on lise. `readText()` consomme
        // jusqu'à EOF, qui arrive quand la CLI ferme stdout (en sortie normale
        // ou en crash). redirectErrorStream(true) fusionne stderr → stdout.
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()

        // Étape 3 — attendre la terminaison effective. À ce stade le process
        // a déjà fermé stdout (sinon readText n'aurait pas retourné), donc
        // waitFor est typiquement immédiat. Le timeout reste là comme filet
        // de sécurité au cas où la CLI ait fermé stdout mais ne se termine
        // pas proprement (rare mais arrivé sur certains wrappers npm).
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return PromptResponse(
                text = "",
                model = req.model,
                error = "Délai d'attente dépassé (${timeoutSeconds}s) pour `$executable`. " +
                    "Augmentez le timeout dans les Settings ou réduisez la taille du prompt."
            )
        }

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            return PromptResponse(
                text = "",
                model = req.model,
                error = "La CLI `$executable` a échoué (exit code $exitCode). " +
                    "Sortie : ${output.trim().take(500)}"
            )
        }
        return PromptResponse(
            text = output,
            model = req.model,
            finishReason = "stop"
        )
    }

    // Détection plateforme — la décision wrap `cmd /c` se fait ici plutôt que
    // dans un init {} pour garder la classe immutable et testable (un test
    // pourra forcer le comportement via une sous-classe override).
    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")
}

package de.foldki.app.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import de.foldki.app.data.ChatMessage
import de.foldki.app.data.Role
import de.foldki.app.github.GitHubApi
import de.foldki.app.github.GitHubTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Claude with GitHub tools (own loop, separate from [ClaudeEngine]): searches, reads, builds and changes
 * repositories with the user's token. Every change waits for the user's approval ([approve]).
 */
class GitHubAgent(private val apiKey: String, private val model: String, token: String) {
    private val tools = GitHubTools(GitHubApi(token))

    companion object {
        private const val MAX_STEPS = 60

        val SYSTEM = """
Du arbeitest jetzt zusätzlich als Entwickler mit Zugang zum GitHub-Konto des Nutzers (über Werkzeuge).
Du kannst suchen, Code lesen, Bausteine aus anderen Repositorys übernehmen, Repositorys und Branches anlegen,
Dateien schreiben (ein Commit pro write_files), Pull Requests erstellen und zusammenführen, Issues schreiben
und mit GitHub Actions bauen (z. B. Android-APKs) und die Ergebnisse prüfen.

Regeln:
- Arbeite selbstständig Schritt für Schritt mit den Werkzeugen, bis die Aufgabe erledigt ist. Sag nie, dass du keinen Zugriff hast.
- Jede Änderung (Anlegen, Schreiben, Mergen, Workflow starten …) zeigt die App dem Nutzer vorher zur Freigabe. Wird sie abgelehnt,
  frag kurz nach, was er anders möchte, statt es nochmal gleich zu versuchen.
- Lies vor dem Ändern die betroffenen Dateien. Schreibe in write_files immer den vollständigen Dateiinhalt.
- Fremden Code nur übernehmen, wenn die Lizenz es erlaubt; nenne die Quelle.
- APK bauen: Das Repository braucht ein Android-Gradle-Projekt mit Gradle-Wrapper (gradlew, gradle/wrapper/gradle-wrapper.properties
  und gradle-wrapper.jar – die .jar mit copy_from aus einem anderen Repository übernehmen) und einen Workflow unter .github/workflows/,
  z. B. mit actions/checkout, actions/setup-java (temurin, 17), ./gradlew assembleDebug und einem Release-Upload
  (softprops/action-gh-release) der APK. Danach mit list_workflow_runs/wait den Build verfolgen, bei Fehlern get_run_jobs und
  get_job_log lesen, beheben und neu bauen. Am Ende den Download-Link aus list_releases nennen.
- Behaupte nie, etwas sei erledigt oder gebaut, ohne dass ein Werkzeug es bestätigt hat.
- Antworte auf Deutsch, kurz und verständlich; am Ende eine knappe Zusammenfassung mit Links.
""".trimIndent()
    }

    private fun toolDefs(): List<Tool> = GitHubTools.SPECS.map { s ->
        val props = Tool.InputSchema.Properties.builder()
        s.properties().forEach { (k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)) }
        Tool.builder().name(s.name).description(s.description)
            .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(s.required()).build())
            .build()
    }

    /**
     * Runs the task. [onText] gets Claude's text and short progress lines; [approve] shows a change to the user
     * and returns true only after "Freigeben".
     */
    suspend fun run(
        system: String,
        history: List<ChatMessage>,
        onText: suspend (String) -> Unit,
        approve: suspend (title: String, details: String) -> Boolean,
    ) {
        require(apiKey.isNotBlank()) { "Kein Claude-API-Schlüssel. Bitte in den Einstellungen eintragen." }
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        val defs = toolDefs()
        val turns = history.filter { !it.isError && it.text.isNotBlank() && !it.localOnly }.dropWhile { it.role != Role.USER }
        val loop = ArrayList<MessageParam>()

        for (step in 0 until MAX_STEPS) {
            val b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(system + "\n\n" + SYSTEM)
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            defs.forEach { b.addTool(it) }
            turns.forEach { m -> if (m.role == Role.USER) b.addUserMessage(m.text) else b.addAssistantMessage(m.text) }
            loop.forEach { b.addMessage(it) }

            val resp = withContext(Dispatchers.IO) { client.messages().create(b.build()) }
            for (c in resp.content()) {
                val t = c.text().orElse(null)?.text()
                if (!t.isNullOrBlank()) onText(t.trim() + "\n\n")
            }
            val stop = resp.stopReason().orElse(null)
            loop += resp.toParam()
            when (stop) {
                StopReason.TOOL_USE -> {
                    val results = ArrayList<ContentBlockParam>()
                    for (c in resp.content()) {
                        val use = c.toolUse().orElse(null) ?: continue
                        val input = runCatching { JSONObject(use._input().convert(Map::class.java) as Map<*, *>) }.getOrDefault(JSONObject())
                        var isError = false
                        val out = try {
                            when {
                                use.name() == "wait" -> {
                                    val s = input.optInt("seconds", 30).coerceIn(10, 120)
                                    onText(GitHubTools.progress("wait", input) + "\n")
                                    delay(s * 1000L); "$s Sekunden gewartet."
                                }
                                use.name() in GitHubTools.WRITE_NAMES -> {
                                    val (title, details) = GitHubTools.describe(use.name(), input)
                                    if (approve(title, details)) {
                                        onText("✅ Freigegeben: $title\n")
                                        withContext(Dispatchers.IO) { tools.run(use.name(), input) }
                                    } else {
                                        onText("⛔ Abgelehnt: $title\n")
                                        isError = true
                                        "Der Nutzer hat diese Änderung NICHT freigegeben. Nicht ausführen; frag ihn, was er anders möchte."
                                    }
                                }
                                else -> {
                                    onText(GitHubTools.progress(use.name(), input) + "\n")
                                    withContext(Dispatchers.IO) { tools.run(use.name(), input) }
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            isError = true
                            "Fehler: ${e.message ?: e.javaClass.simpleName}"
                        }
                        results += ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder().toolUseId(use.id()).content(GitHubTools.cap(out, 60_000)).isError(isError).build()
                        )
                    }
                    loop += MessageParam.ofUserOfContentBlockParams(results)
                }
                StopReason.PAUSE_TURN -> Unit // server paused a long turn: just continue
                StopReason.REFUSAL -> { onText("\n(Claude hat diese Anfrage abgelehnt.)"); return }
                StopReason.MAX_TOKENS -> { onText("\n(Antwort war zu lang und wurde abgeschnitten.)"); return }
                else -> return
            }
        }
        onText("\n(Abgebrochen nach $MAX_STEPS Schritten – sag „weiter“, dann mache ich weiter.)")
    }
}

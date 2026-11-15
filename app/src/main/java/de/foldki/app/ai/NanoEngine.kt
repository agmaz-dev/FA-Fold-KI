package de.foldki.app.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import de.foldki.app.data.ChatMessage
import de.foldki.app.data.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Gemini Nano on the device, via ML Kit GenAI Prompt API (Android AICore).
 * Works offline once the model is on the phone (e.g. Galaxy Z Fold7).
 */
class NanoEngine {
    private val model: GenerativeModel by lazy { Generation.getClient() }

    enum class State { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

    suspend fun state(): State = when (model.checkStatus()) {
        FeatureStatus.AVAILABLE -> State.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> State.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> State.DOWNLOADING
        else -> State.UNAVAILABLE
    }

    /** Downloads the model; emits a human-readable progress line. */
    fun download(): Flow<String> = flow {
        model.download().collect { s ->
            when (s) {
                is DownloadStatus.DownloadCompleted -> emit("Gemini Nano ist bereit.")
                is DownloadStatus.DownloadFailed -> throw IllegalStateException(
                    "Download fehlgeschlagen: ${s.e.message}"
                )
                is DownloadStatus.DownloadProgress ->
                    emit("Lade Gemini Nano… ${s.totalBytesDownloaded / 1_000_000} MB")
                else -> emit("Download gestartet…")
            }
        }
    }

    /**
     * The Prompt API is single-turn, so recent chat history is packed into one prompt.
     * Nano has a small context window, so only the last few turns are kept.
     */
    fun reply(system: String, history: List<ChatMessage>, webSearch: Boolean): Flow<String> = flow {
        var web = ""
        if (webSearch) {
            val question = history.lastOrNull { it.role == Role.USER }?.text.orEmpty()
            val terms = complete(
                "Gib nur 2 bis 5 Suchbegriffe (ohne Erklärung, eine Zeile) für eine Internetsuche zu dieser Frage aus:\n$question"
            ).lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '.', '*')?.take(100).orEmpty()
            web = WebLookup.search(terms.ifBlank { question.take(100) })
        }
        val prompt = buildPrompt(system, history, web)
        model.generateContentStream(prompt).collect { chunk ->
            val text = chunk.candidates.firstOrNull()?.text.orEmpty()
            if (text.isNotEmpty()) emit(text)
        }
    }

    private suspend fun complete(prompt: String): String {
        val sb = StringBuilder()
        model.generateContentStream(prompt).collect { sb.append(it.candidates.firstOrNull()?.text.orEmpty()) }
        return sb.toString()
    }

    private fun buildPrompt(system: String, history: List<ChatMessage>, web: String = ""): String {
        val turns = history.filter { !it.isError }.takeLast(6)
        val lines = turns.map { m ->
            val who = if (m.role == Role.USER) "Nutzer" else "Assistent"
            "$who: ${m.text.take(1500)}"
        }.toMutableList()
        // Keep the prompt within Nano's small context: drop the oldest turns first.
        while (lines.size > 1 && lines.sumOf { it.length } > 3000) lines.removeAt(0)

        // Text of attached documents/photos (latest ones first), limited to fit Nano.
        val docs = StringBuilder()
        var budget = 4500
        history.asReversed().flatMap { it.attachments }.forEach { a ->
            if (budget <= 0 || a.text.isBlank()) return@forEach
            val t = a.text.take(budget)
            budget -= t.length
            docs.append("--- Anhang: ${a.name} ---\n").append(t).append("\n\n")
        }
        val noText = history.flatMap { it.attachments }.filter { it.text.isBlank() }

        return buildString {
            append(system).append("\n\n")
            if (docs.isNotEmpty()) append("Inhalt der angehängten Dateien:\n").append(docs)
            if (web.isNotBlank()) append("Ergebnisse einer Internetsuche (nutze sie für deine Antwort und nenne die Quelle, z. B. Wikipedia):\n")
                .append(web.take(3000)).append("\n\n")
            if (noText.isNotEmpty()) append("(Aus ${noText.joinToString { it.name }} konnte kein Text gelesen werden.)\n\n")
            lines.forEach { append(it).append("\n") }
            append("Assistent:")
        }
    }
}

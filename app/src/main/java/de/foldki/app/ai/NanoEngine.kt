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
    fun reply(system: String, history: List<ChatMessage>): Flow<String> = flow {
        val prompt = buildPrompt(system, history)
        model.generateContentStream(prompt).collect { chunk ->
            val text = chunk.candidates.firstOrNull()?.text.orEmpty()
            if (text.isNotEmpty()) emit(text)
        }
    }

    private fun buildPrompt(system: String, history: List<ChatMessage>): String {
        val lines = history.filter { !it.isError }.takeLast(8).map { m ->
            val who = if (m.role == Role.USER) "Nutzer" else "Assistent"
            "$who: ${m.text.take(1500)}"
        }.toMutableList()
        // Keep the prompt within Nano's small context: drop the oldest turns first.
        while (lines.size > 1 && lines.sumOf { it.length } > 3500) lines.removeAt(0)
        return buildString {
            append(system).append("\n\n")
            lines.forEach { append(it).append("\n") }
            append("Assistent:")
        }
    }
}

package de.foldki.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Speech in (Android SpeechRecognizer) and speech out (Android TextToSpeech), German by default.
 * All callbacks are delivered on the main thread.
 */
class VoiceController(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var ttsReady = false
    private var onSpeechDone: (() -> Unit)? = null
    private var lastUtterance = ""

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale.GERMANY
            applyVoiceSettings()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == lastUtterance) main.post { val cb = onSpeechDone; onSpeechDone = null; cb?.invoke() }
                }
                @Deprecated("old API") override fun onError(id: String?) { onDone(id) }
                override fun onError(id: String?, errorCode: Int) { onDone(id) }
            })
        }
    }

    // Chosen voice, speed and pitch (applied as soon as the TTS engine is ready).
    var voiceName: String = ""
        set(v) { field = v; applyVoiceSettings() }
    var rate: Float = 1f
        set(v) { field = v; applyVoiceSettings() }
    var pitch: Float = 1f
        set(v) { field = v; applyVoiceSettings() }

    private fun applyVoiceSettings() {
        if (!ttsReady) return
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        if (voiceName.isNotBlank()) {
            runCatching { tts.voices?.firstOrNull { it.name == voiceName }?.let { tts.voice = it } }
        } else {
            tts.language = Locale.GERMANY
        }
    }

    /** German voices installed on the phone (offline ones first). */
    fun germanVoices(): List<Voice> {
        if (!ttsReady) return emptyList()
        val all = runCatching { tts.voices?.toList().orEmpty() }.getOrDefault(emptyList())
        val de = all.filter { it.locale.language == "de" && !it.features.orEmpty().contains("notInstalled") }
        return de.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
    }

    fun preview(v: Voice) {
        if (!ttsReady) return
        stopSpeaking()
        tts.voice = v
        tts.setSpeechRate(rate); tts.setPitch(pitch)
        tts.speak("Hallo, so klinge ich. Wie kann ich dir helfen?", TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    /** Restores the chosen voice after a preview. */
    fun resetVoice() = applyVoiceSettings()

    val recognitionAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(app)

    /**
     * Listens once. [onPartial] gets the text while speaking, [onFinal] the final text
     * (empty if nothing was understood), [onError] a German message.
     */
    fun listen(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit = {}) {
        stopSpeaking()
        cancelListening()
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                onFinal(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onFinal("")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError("Mikrofon-Berechtigung fehlt.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        onError("Spracherkennung braucht Internet oder das Offline-Sprachpaket Deutsch.")
                    else -> onError("Spracherkennung fehlgeschlagen (Code $error).")
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        r.startListening(intent)
    }

    fun stopListening() { recognizer?.stopListening() }

    fun cancelListening() {
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
    }

    /** Reads [text] aloud in chunks; [onDone] runs when finished (or right away if TTS is unavailable). */
    fun speak(text: String, onDone: () -> Unit = {}) {
        stopSpeaking()
        if (!ttsReady || text.isBlank()) { onDone(); return }
        onSpeechDone = onDone
        val chunks = chunk(text, minOf(TextToSpeech.getMaxSpeechInputLength() - 50, 3000))
        chunks.forEachIndexed { i, c ->
            val id = "u${System.nanoTime()}_$i"
            if (i == chunks.lastIndex) lastUtterance = id
            tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    val isSpeaking: Boolean get() = ttsReady && tts.isSpeaking

    fun stopSpeaking() {
        onSpeechDone = null
        if (ttsReady) tts.stop()
    }

    fun release() {
        cancelListening()
        tts.stop(); tts.shutdown()
    }

    private fun chunk(text: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        text.split(Regex("(?<=[.!?\\n])\\s+")).forEach { sentence ->
            if (sb.length + sentence.length > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            sb.append(sentence.take(max)).append(' ')
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }
}

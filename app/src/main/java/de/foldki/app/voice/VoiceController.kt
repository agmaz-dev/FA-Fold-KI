package de.foldki.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice in and out, fully on the phone and independent of Samsung/Google speech services:
 * - listening: own microphone recording + Whisper (like ChatGPT/Grok)
 * - speaking: Supertonic, only the voices Tessa and Caleb
 */
class VoiceController(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val supertonic = Supertonic(app)
    val whisper = WhisperStt(app)

    var supertonicVoice: String = Supertonic.DEFAULT_VOICE
    var rate: Float = 1f

    /** Both models downloaded (one-time setup). */
    val isReady: Boolean get() = supertonic.isReady && whisper.isReady

    private var listenJob: Job? = null
    @Volatile private var stopRequested = false
    private var focus: AudioFocusRequest? = null

    /**
     * Records until the user pauses, then recognizes the words with Whisper.
     * No intermediate text is shown anywhere: [onTranscribing] only signals that recognition runs,
     * [onFinal] gets the text (empty = nothing understood).
     */
    fun listen(
        onFinal: (String) -> Unit, onError: (String) -> Unit,
        onLevel: (Float) -> Unit = {}, onTranscribing: () -> Unit = {},
    ) {
        stopSpeaking()
        cancelListening()
        if (!whisper.isReady) { onError("Bitte zuerst die Sprachfunktion einrichten (Einstellungen)."); return }
        stopRequested = false
        scope.launch { whisper.warmUp() } // load Whisper in parallel to the recording (only the first time takes long)
        listenJob = scope.launch {
            requestFocus()
            try {
                val pcm = whisper.record(level = { l -> scope.launch { onLevel(l) } }, stopNow = { stopRequested })
                abandonFocus()
                if (pcm.isEmpty()) { onFinal(""); return@launch }
                onTranscribing()
                onFinal(whisper.transcribe(pcm))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError("Spracheingabe fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                abandonFocus()
            }
        }
    }

    /** Ends the recording now (the recorded part is still transcribed). */
    fun stopListening() { stopRequested = true }

    fun cancelListening() { listenJob?.cancel(); listenJob = null; abandonFocus() }

    /** Reads [text] aloud with Tessa or Caleb; [onDone] runs afterwards. */
    fun speak(text: String, onDone: () -> Unit = {}) {
        stopSpeaking()
        if (text.isBlank() || !supertonic.isReady) { onDone(); return }
        supertonic.speak(text, supertonicVoice, rate, onDone) { err ->
            android.util.Log.w("FoldKI", "Supertonic: $err")
            onDone()
        }
    }

    fun previewSupertonic(voice: String, onError: (String) -> Unit) {
        stopSpeaking()
        val name = Supertonic.VOICES[voice]?.second?.substringBefore(" (") ?: voice
        supertonic.speak("Hallo, ich bin $name. Wie kann ich dir helfen?", voice, rate, {}, onError)
    }

    val isSpeaking: Boolean get() = supertonic.isPlaying

    fun stopSpeaking() = supertonic.stop()

    fun release() { cancelListening(); supertonic.stop() }

    /** Pauses music (e.g. Spotify) while listening, like other assistant apps do. */
    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        runCatching { audio.requestAudioFocus(req) }
        focus = req
    }

    private fun abandonFocus() {
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
    }
}

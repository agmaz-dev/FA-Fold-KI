package de.foldki.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** What the microphone side is doing right now (drives buttons, so nothing can hang). */
    enum class ListenPhase { IDLE, RECORDING, TRANSCRIBING }

    private val _listenPhase = MutableStateFlow(ListenPhase.IDLE)
    val listenPhase: StateFlow<ListenPhase> = _listenPhase.asStateFlow()

    private var listenJob: Job? = null
    /** Every listen() gets a new number; callbacks of an older (stopped) session are dropped. */
    private var session = 0L
    @Volatile private var stopRequested = false
    private var focus: AudioFocusRequest? = null

    /**
     * Records until the user pauses, then recognizes the words with Whisper.
     * No intermediate text is shown anywhere: [onTranscribing] only signals that recognition runs,
     * [onFinal] gets the text (empty = nothing understood). [onEnd] runs exactly once at the end
     * of the session, also after a stop or cancel, so callers can always reset their state.
     * Starting again while a session runs ends the old one first (no double start, the
     * microphone of the old session is released before the new recording begins).
     */
    fun listen(
        onFinal: (String) -> Unit, onError: (String) -> Unit,
        onLevel: (Float) -> Unit = {}, onTranscribing: () -> Unit = {}, onEnd: () -> Unit = {},
    ) {
        stopSpeaking()
        val previous = listenJob
        previous?.cancel()
        val id = ++session
        if (!whisper.isReady) {
            _listenPhase.value = ListenPhase.IDLE
            onError("Bitte zuerst die Sprachfunktion einrichten (Einstellungen)."); onEnd(); return
        }
        stopRequested = false
        _listenPhase.value = ListenPhase.RECORDING
        scope.launch { whisper.warmUp() } // load Whisper in parallel to the recording (only the first time takes long)
        // UNDISPATCHED: the try/finally is entered at once, so onEnd runs even if stopped right away.
        listenJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.join()
                requestFocus()
                val pcm = whisper.record(
                    level = { l -> scope.launch { if (id == session) onLevel(l) } },
                    stopNow = { stopRequested || id != session },
                )
                abandonFocus()
                if (id != session) return@launch
                if (pcm.isEmpty()) { onFinal(""); return@launch }
                _listenPhase.value = ListenPhase.TRANSCRIBING
                onTranscribing()
                val text = whisper.transcribe(pcm)
                if (id == session) onFinal(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (id == session) onError("Spracheingabe fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                abandonFocus()
                if (id == session) _listenPhase.value = ListenPhase.IDLE
                onEnd()
            }
        }
    }

    /** Ends the recording now; the recorded part is still recognized (like "fertig"). */
    fun stopListening() {
        if (_listenPhase.value == ListenPhase.RECORDING) stopRequested = true
    }

    /** Stops everything at once: recording or recognition, nothing is delivered. */
    fun cancelListening() {
        session++
        stopRequested = true
        listenJob?.cancel(); listenJob = null
        _listenPhase.value = ListenPhase.IDLE
        abandonFocus()
    }

    /** Stop for the round button: ends recording, recognition and speech output immediately. */
    fun stopAll() { cancelListening(); stopSpeaking() }

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

    fun release() { stopAll(); whisper.release() }

    /** Keeps Whisper loaded so the first dictation does not wait for the model (~250 MB RAM). */
    fun prewarm() { if (whisper.isReady && !whisper.isLoaded) scope.launch { whisper.warmUp() } }

    /** Android needs memory: free Whisper unless it is in use; it loads again when needed. */
    fun trimMemory() { if (_listenPhase.value == ListenPhase.IDLE) whisper.release() }

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

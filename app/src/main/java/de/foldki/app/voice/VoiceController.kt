package de.foldki.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.ComponentName
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognitionService
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

    /** Supertonic voices (Tessa, Caleb). Used when [engine] is "supertonic" and the model is installed. */
    val supertonic = Supertonic(app)
    var engine: String = "android"
    var supertonicVoice: String = Supertonic.DEFAULT_VOICE

    val useSupertonic: Boolean get() = engine == "supertonic" && supertonic.isReady

    /** True if any recognizer exists – or Google's voice dialog can be used as a fallback. */
    val recognitionAvailable: Boolean get() = true

    /** One way of getting a SpeechRecognizer; tried in order until one works. */
    private sealed class Rec(val label: String) {
        object OnDevice : Rec("offline")
        object Default : Rec("standard")
        class Service(val cn: ComponentName) : Rec(cn.packageName)
    }

    private var workingRec: Int = 0

    private fun recognizers(): List<Rec> {
        val list = mutableListOf<Rec>()
        if (Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) }.getOrDefault(false)) {
            list += Rec.OnDevice
        }
        list += Rec.Default
        // Every installed recognition service (Google first), in case the default one is broken.
        val services = runCatching {
            app.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        }.getOrDefault(emptyList())
        services.sortedBy { if (it.packageName.contains("google")) 0 else 1 }.forEach { list += Rec.Service(it) }
        return list
    }

    private fun create(rec: Rec): SpeechRecognizer = when (rec) {
        Rec.OnDevice -> if (Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                        else SpeechRecognizer.createSpeechRecognizer(app)
        Rec.Default -> SpeechRecognizer.createSpeechRecognizer(app)
        is Rec.Service -> SpeechRecognizer.createSpeechRecognizer(app, rec.cn)
    }

    /**
     * Listens once. [onPartial] gets the text while speaking, [onFinal] the final text
     * (empty if nothing was understood), [onError] a German message.
     * If a recognizer fails with a service error, the next one is tried automatically.
     * [onNeedsDialog] is called when no recognizer works, so the UI can open Google's voice dialog.
     */
    fun listen(
        onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit,
        onLevel: (Float) -> Unit = {}, onNeedsDialog: (() -> Unit)? = null,
    ) {
        stopSpeaking()
        cancelListening()
        val recs = recognizers()
        val order = (recs.indices.drop(workingRec.coerceIn(0, recs.lastIndex)) + recs.indices.take(workingRec.coerceIn(0, recs.lastIndex)))
        attempt(recs, order, 0, onPartial, onFinal, onError, onLevel, onNeedsDialog)
    }

    private fun attempt(
        recs: List<Rec>, order: List<Int>, pos: Int,
        onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit,
        onLevel: (Float) -> Unit, onNeedsDialog: (() -> Unit)?,
    ) {
        if (pos >= order.size) {
            if (onNeedsDialog != null) onNeedsDialog()
            else onError("Spracherkennung auf diesem Gerät nicht erreichbar.")
            return
        }
        val idx = order[pos]
        val r = runCatching { create(recs[idx]) }.getOrNull()
        if (r == null) { attempt(recs, order, pos + 1, onPartial, onFinal, onError, onLevel, onNeedsDialog); return }
        recognizer = r
        var heardSomething = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { workingRec = idx }
            override fun onBeginningOfSpeech() { heardSomething = true }
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                workingRec = idx
                onFinal(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onFinal("")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError("Mikrofon-Berechtigung fehlt.")
                    else -> {
                        // Service problem (e.g. code 11 "server disconnected", 12/13 language missing):
                        // try the next recognizer, unless the user had already started talking.
                        android.util.Log.w("FoldKI", "Recognizer ${recs[idx].label} error $error")
                        runCatching { r.destroy() }
                        if (recognizer === r) recognizer = null
                        if (!heardSomething) {
                            main.post { attempt(recs, order, pos + 1, onPartial, onFinal, onError, onLevel, onNeedsDialog) }
                        } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                            onError("Spracherkennung braucht Internet oder das Offline-Sprachpaket Deutsch.")
                        } else {
                            onError("Spracherkennung fehlgeschlagen (Code $error). Bitte nochmal versuchen.")
                        }
                    }
                }
            }
        })
        r.startListening(recognizeIntent())
    }

    /** Intent for both the in-app recognizer and Google's voice dialog. */
    fun recognizeIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Sprich jetzt")
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
    }

    fun stopListening() { recognizer?.stopListening() }

    fun cancelListening() {
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
    }

    /** Reads [text] aloud in chunks; [onDone] runs when finished (or right away if TTS is unavailable). */
    fun speak(text: String, onDone: () -> Unit = {}) {
        stopSpeaking()
        if (useSupertonic && text.isNotBlank()) {
            supertonic.speak(text, supertonicVoice, rate, onDone) { err ->
                // Fall back to the Android voice if Supertonic fails.
                android.util.Log.w("FoldKI", "Supertonic: $err")
                speakAndroid(text, onDone)
            }
            return
        }
        speakAndroid(text, onDone)
    }

    /** Short sample with a Supertonic voice. */
    fun previewSupertonic(voice: String, onError: (String) -> Unit) {
        stopSpeaking()
        supertonic.speak("Hallo, ich bin ${Supertonic.VOICES[voice]?.second?.substringBefore(" (") ?: voice}. Wie kann ich dir helfen?", voice, rate, {}, onError)
    }

    private fun speakAndroid(text: String, onDone: () -> Unit) {
        if (!ttsReady || text.isBlank()) { onDone(); return }
        onSpeechDone = onDone
        val chunks = chunk(text, minOf(TextToSpeech.getMaxSpeechInputLength() - 50, 3000))
        chunks.forEachIndexed { i, c ->
            val id = "u${System.nanoTime()}_$i"
            if (i == chunks.lastIndex) lastUtterance = id
            tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    val isSpeaking: Boolean get() = (ttsReady && tts.isSpeaking) || supertonic.isPlaying

    fun stopSpeaking() {
        onSpeechDone = null
        supertonic.stop()
        if (ttsReady) tts.stop()
    }

    fun release() {
        supertonic.stop()
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

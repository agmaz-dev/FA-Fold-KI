package de.foldki.app.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Own speech recognition, like ChatGPT/Grok do it: the app records the microphone itself and
 * transcribes on the phone with OpenAI Whisper (small, int8) via ONNX Runtime.
 * No Samsung/Google speech service involved. Model (~250 MB) is downloaded once.
 */
class WhisperStt(context: Context) {
    companion object {
        private const val REPO = "https://huggingface.co/Xenova/whisper-small/resolve/main/"
        private val FILES = listOf(
            "onnx/encoder_model_quantized.onnx",
            "onnx/decoder_model_quantized.onnx",
            "vocab.json",
        )
        const val SAMPLE_RATE = 16000
        private const val N_FFT = 400
        private const val HOP = 160
        private const val N_MELS = 80
        private const val N_FRAMES = 3000 // 30 s
        private const val N_SAMPLES = SAMPLE_RATE * 30
        /** Pause after speech that ends the recording (was 1.2 s; shorter = text comes sooner). */
        private const val END_SILENCE_MS = 900
        private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

        // Special tokens of the multilingual Whisper vocabulary
        private const val EOT = 50257
        private const val SOT = 50258
        private const val LANG_DE = 50261
        private const val TRANSCRIBE = 50359
        private const val NO_TIMESTAMPS = 50363
    }

    private val dir = File(context.filesDir, "whisper")
    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var idToToken: Array<String>? = null

    val isReady: Boolean get() = FILES.all { File(dir, it).exists() }

    // ---------------------------------------------------------------- download

    suspend fun prepare(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val missing = FILES.filter { !File(dir, it).exists() }
        missing.forEachIndexed { i, rel ->
            val label = "Spracherkennung: Datei ${i + 1} von ${missing.size} (${rel.substringAfterLast('/')})"
            download(REPO + rel, File(dir, rel)) { got, total ->
                progress(label + if (total > 0) " – ${got * 100 / total} %" else " – ${got / 1_000_000} MB")
            }
        }
        progress("Spracherkennung ist eingerichtet.")
    }

    private suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15000; c.readTimeout = 30000
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("${target.name}: HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var got = 0L; var last = 0L
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (got - last > 1_000_000) { last = got; onProgress(got, total) }
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IllegalStateException("Speichern fehlgeschlagen")
        } finally {
            c.disconnect(); tmp.delete()
        }
    }

    // ---------------------------------------------------------------- recording

    /**
     * Records until the speaker pauses (~0.9 s silence after speech), max 30 s.
     * [level] gets 0..1 for the animation. Returns 16 kHz mono samples, or empty if nobody spoke.
     * [stopNow] can be set to true from outside to end the recording early.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(level: (Float) -> Unit, stopNow: () -> Boolean): FloatArray = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SAMPLE_RATE),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("Mikrofon nicht verfügbar.") }
        val frame = SAMPLE_RATE * 30 / 1000 // 30 ms
        val buf = ShortArray(frame)
        val out = FloatArray(N_SAMPLES)
        var n = 0
        var noise = 0.0; var noiseFrames = 0
        var speech = false
        var silentMs = 0; var waitedMs = 0
        try {
            rec.startRecording()
            var failedReads = 0
            while (n + frame <= N_SAMPLES) {
                coroutineContext.ensureActive()
                if (stopNow()) break // also during the first 300 ms
                val r = rec.read(buf, 0, frame)
                if (r <= 0) {
                    // Microphone taken by another app or broken: give up instead of hanging.
                    if (++failedReads > 20) throw IllegalStateException("Mikrofon liefert keine Daten.")
                    continue
                }
                failedReads = 0
                var sum = 0.0
                for (i in 0 until r) { val v = buf[i] / 32768f; out[n + i] = v; sum += v * v }
                n += r
                val rms = sqrt(sum / r)
                if (noiseFrames < 10) { noise += rms; noiseFrames++; continue } // ~300 ms calibration
                val floor = noise / noiseFrames
                val threshold = max(min(floor, 0.02) * 2.5, 0.01)
                level(((rms / (threshold * 4)).toFloat()).coerceIn(0f, 1f))
                if (rms > threshold) { speech = true; silentMs = 0 } else if (speech) silentMs += 30 else waitedMs += 30
                if (speech && silentMs >= END_SILENCE_MS) break
                if (!speech && waitedMs >= 8000) break
            }
        } finally {
            runCatching { rec.stop() }; rec.release(); level(0f)
        }
        if (!speech) FloatArray(0) else out.copyOf(n)
    }

    // ---------------------------------------------------------------- transcription

    /**
     * Loads the model ahead of time (when the app opens and while the user is still speaking),
     * so the text appears sooner. Also prepares the lookup tables of the feature computation.
     */
    suspend fun warmUp() = withContext(Dispatchers.Default) {
        if (!isReady) return@withContext
        runCatching { load(); cosT; sinT; melFilters; unicodeToByte }
    }

    /** True while a model is loaded (kept warm for the next dictation). */
    val isLoaded: Boolean get() = encoder != null

    @Volatile private var busy = 0

    /** Frees the model memory (Android asks for memory); it loads again on the next use. */
    @Synchronized
    fun release() {
        if (busy > 0 || encoder == null) return
        runCatching { encoder?.close() }; runCatching { decoder?.close() }
        encoder = null; decoder = null; idToToken = null
    }

    @Synchronized
    private fun load() {
        if (encoder != null) return
        if (!isReady) throw IllegalStateException("Spracherkennung ist noch nicht eingerichtet.")
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(THREADS)
        }
        encoder = e.createSession(File(dir, FILES[0]).absolutePath, opts)
        decoder = e.createSession(File(dir, FILES[1]).absolutePath, opts)
        val vocab = JSONObject(File(dir, "vocab.json").readText())
        val arr = Array(51865) { "" }
        vocab.keys().forEach { k -> val id = vocab.getInt(k); if (id in arr.indices) arr[id] = k }
        idToToken = arr
        env = e
    }

    @Synchronized
    private fun acquire(): Triple<OrtSession, OrtSession, Array<String>> {
        load(); busy++
        return Triple(encoder!!, decoder!!, idToToken!!)
    }

    @Synchronized
    private fun releaseBusy() { busy = (busy - 1).coerceAtLeast(0) }

    suspend fun transcribe(audio: FloatArray): String = withContext(Dispatchers.Default) {
        if (audio.isEmpty()) return@withContext ""
        val (enc, dec, vocabArr) = acquire()
        try {
            val e = env!!
            val mel = logMel(audio)
            val tokens = mutableListOf(SOT.toLong(), LANG_DE.toLong(), TRANSCRIBE.toLong(), NO_TIMESTAMPS.toLong())
            val prompt = tokens.size
            OnnxTensor.createTensor(e, FloatBuffer.wrap(mel), longArrayOf(1, N_MELS.toLong(), N_FRAMES.toLong())).use { feats ->
                // The encoder output goes straight into the decoder (no copy into a new array).
                enc.run(mapOf("input_features" to feats)).use { encOut ->
                    val encStates = encOut.get(0) as OnnxTensor
                    while (tokens.size < 200) {
                        coroutineContext.ensureActive()
                        val next = OnnxTensor.createTensor(e, LongBuffer.wrap(tokens.toLongArray()), longArrayOf(1, tokens.size.toLong())).use { ids ->
                            dec.run(mapOf("input_ids" to ids, "encoder_hidden_states" to encStates)).use { r ->
                                val logits = r.get(0) as OnnxTensor
                                val vocab = logits.info.shape[2].toInt()
                                val fb = logits.floatBuffer
                                val base = (tokens.size - 1) * vocab
                                // Greedy: best normal text token or end-of-text; never timestamps/specials.
                                var best = EOT; var bestV = fb.get(base + EOT)
                                for (i in 0 until EOT) { val v = fb.get(base + i); if (v > bestV) { bestV = v; best = i } }
                                best
                            }
                        }
                        if (next == EOT) break
                        tokens += next.toLong()
                        // Stop on obvious loops (same 4 tokens repeating)
                        if (tokens.size > prompt + 12 && tokens.takeLast(4) == tokens.dropLast(4).takeLast(4)) break
                    }
                }
            }
            decode(vocabArr, tokens.drop(prompt).map { it.toInt() }).trim()
        } finally {
            releaseBusy()
        }
    }

    // GPT-2 byte-level BPE: map the vocabulary's unicode characters back to bytes.
    private val unicodeToByte: Map<Char, Int> by lazy {
        val bs = mutableListOf<Int>()
        bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + n; n++ }
        bs.indices.associate { cs[it].toChar() to bs[it] }
    }

    private fun decode(vocab: Array<String>, ids: List<Int>): String {
        val bytes = java.io.ByteArrayOutputStream()
        ids.filter { it < EOT }.forEach { id -> vocab[id].forEach { ch -> unicodeToByte[ch]?.let { bytes.write(it) } } }
        return bytes.toString("UTF-8")
    }

    // ---------------------------------------------------------------- features

    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
    private val cosT by lazy { Array(N_FFT / 2 + 1) { k -> FloatArray(N_FFT) { n -> cos(2 * PI * k * n / N_FFT).toFloat() } } }
    private val sinT by lazy { Array(N_FFT / 2 + 1) { k -> FloatArray(N_FFT) { n -> sin(2 * PI * k * n / N_FFT).toFloat() } } }
    private val melFilters: Array<FloatArray> by lazy { slaneyMel() }

    /**
     * Log-mel spectrogram exactly like openai/whisper (80 x 3000, padded to 30 s).
     * Only frames with audio are computed, split over several cores.
     */
    private suspend fun logMel(audio: FloatArray): FloatArray = coroutineScope {
        val samples = FloatArray(N_SAMPLES)
        audio.copyInto(samples, 0, 0, min(audio.size, N_SAMPLES))
        val mel = FloatArray(N_MELS * N_FRAMES)
        val lastSpeechFrame = min(N_FRAMES, audio.size / HOP + 3)
        val silentLog = log10(1e-10).toFloat()
        for (t in lastSpeechFrame until N_FRAMES) for (m in 0 until N_MELS) mel[m * N_FRAMES + t] = silentLog
        val parts = THREADS.coerceAtMost(max(1, lastSpeechFrame / 50))
        val step = (lastSpeechFrame + parts - 1) / parts
        var maxV = (0 until parts).map { p ->
            async(Dispatchers.Default) { melFrames(samples, mel, p * step, min(lastSpeechFrame, (p + 1) * step)) }
        }.awaitAll().maxOrNull() ?: silentLog
        if (maxV < silentLog) maxV = silentLog
        val floor = maxV - 8f
        for (i in mel.indices) mel[i] = (max(mel[i], floor) + 4f) / 4f
        mel
    }

    /** Computes frames [from, to) into [mel]; returns the largest value. Each call has its own buffers. */
    private fun melFrames(samples: FloatArray, mel: FloatArray, from: Int, to: Int): Float {
        val pad = N_FFT / 2
        fun at(i: Int): Float { // reflect padding (center=True)
            val j = when { i < 0 -> -i; i >= N_SAMPLES -> 2 * (N_SAMPLES - 1) - i; else -> i }
            return samples[j]
        }
        val bins = N_FFT / 2 + 1
        val frameBuf = FloatArray(N_FFT)
        val power = FloatArray(bins)
        var maxV = -Float.MAX_VALUE
        for (t in from until to) {
            val start = t * HOP - pad
            for (n in 0 until N_FFT) frameBuf[n] = at(start + n) * window[n]
            for (k in 0 until bins) {
                val c = cosT[k]; val s = sinT[k]
                var re = 0f; var im = 0f
                for (n in 0 until N_FFT) { val x = frameBuf[n]; re += x * c[n]; im -= x * s[n] }
                power[k] = re * re + im * im
            }
            for (m in 0 until N_MELS) {
                val f = melFilters[m]
                var acc = 0f
                for (k in 0 until bins) acc += f[k] * power[k]
                val v = log10(max(acc, 1e-10f))
                mel[m * N_FRAMES + t] = v
                if (v > maxV) maxV = v
            }
        }
        return maxV
    }

    /** librosa.filters.mel(sr=16000, n_fft=400, n_mels=80), Slaney scale and norm (as used by Whisper). */
    private fun slaneyMel(): Array<FloatArray> {
        val fSp = 200.0 / 3; val minLogHz = 1000.0; val minLogMel = minLogHz / fSp; val logStep = ln(6.4) / 27
        fun hzToMel(f: Double) = if (f < minLogHz) f / fSp else minLogMel + ln(f / minLogHz) / logStep
        fun melToHz(m: Double) = if (m < minLogMel) m * fSp else minLogHz * kotlin.math.exp(logStep * (m - minLogMel))
        val bins = N_FFT / 2 + 1
        val fft = DoubleArray(bins) { it * (SAMPLE_RATE / 2.0) / (bins - 1) }
        val mMin = hzToMel(0.0); val mMax = hzToMel(SAMPLE_RATE / 2.0)
        val hz = DoubleArray(N_MELS + 2) { melToHz(mMin + (mMax - mMin) * it / (N_MELS + 1)) }
        return Array(N_MELS) { i ->
            val enorm = 2.0 / (hz[i + 2] - hz[i])
            FloatArray(bins) { k ->
                val lower = (fft[k] - hz[i]) / (hz[i + 1] - hz[i])
                val upper = (hz[i + 2] - fft[k]) / (hz[i + 2] - hz[i + 1])
                (max(0.0, min(lower, upper)) * enorm).toFloat()
            }
        }
    }
}

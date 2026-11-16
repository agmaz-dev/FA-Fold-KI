package de.foldki.app.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import de.foldki.app.data.ChatMessage
import de.foldki.app.data.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Optional Gemma 4 E2B on the device via LiteRT-LM. Same reply signature as [NanoEngine].
 * GPU first, CPU as fallback; every failure (also a missing native library) is caught,
 * so the caller can always fall back to Gemini Nano.
 */
class GemmaEngine(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var engine: Engine? = null
    @Volatile var backendUsed: String? = null
        private set
    private var loading: Deferred<String>? = null

    val isLoaded: Boolean get() = engine != null

    /**
     * Loads the model (only once, guarded by a mutex) in its own scope, so a caller can stop
     * waiting (timeout) without leaving a half-initialised native engine behind.
     * [preferGpu] false = CPU directly (e.g. after the GPU failed before). Returns "GPU" or "CPU".
     */
    fun loadAsync(modelPath: String, preferGpu: Boolean, onAttempt: (String) -> Unit = {}): Deferred<String> = synchronized(this) {
        loading?.takeIf { it.isActive }?.let { return it }
        loadScope.async {
            mutex.withLock {
                engine?.let { return@withLock backendUsed ?: "CPU" }
                var lastError: Throwable? = null
                val order = if (preferGpu) listOf("GPU", "CPU") else listOf("CPU")
                for (b in order) {
                    onAttempt(b)
                    val e = try {
                        Engine(
                            EngineConfig(
                                modelPath = modelPath,
                                backend = if (b == "GPU") Backend.GPU() else Backend.CPU(),
                                maxNumTokens = 4096,
                                cacheDir = app.cacheDir.path,
                            )
                        )
                    } catch (t: Throwable) { lastError = t; continue }
                    try {
                        e.initialize()
                        engine = e; backendUsed = b
                        return@withLock b
                    } catch (t: Throwable) { // incl. UnsatisfiedLinkError / missing OpenCL
                        lastError = t
                        runCatching { e.close() }
                    }
                }
                throw IllegalStateException("Gemma konnte nicht geladen werden: ${lastError?.message ?: lastError?.javaClass?.simpleName}", lastError)
            }
        }.also { loading = it }
    }

    fun reply(system: String, history: List<ChatMessage>, webSearch: Boolean): Flow<String> = flow {
        val e = engine ?: throw IllegalStateException("Gemma ist nicht geladen.")
        var web = ""
        if (webSearch) {
            // Same as Nano: the existing web switch allows Wikipedia + news lookup, otherwise fully offline.
            val question = history.lastOrNull { it.role == Role.USER }?.text.orEmpty()
            val terms = NanoEngine.firstLineTerms(complete(e, NanoEngine.searchTermsPrompt(question)))
            web = WebLookup.search(terms.ifBlank { question.take(100) })
        }
        val prompt = NanoEngine.buildPrompt(system, history, web)
        stream(e, prompt).collect { emit(it) }
    }

    private fun stream(e: Engine, prompt: String): Flow<String> = flow {
        val conv = e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)))
        var done = false
        try {
            conv.sendMessageAsync(prompt).collect { m ->
                val t = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                if (t.isNotEmpty()) emit(t)
            }
            done = true
        } finally {
            if (!done) runCatching { conv.cancelProcess() }
            runCatching { conv.close() }
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun complete(e: Engine, prompt: String): String {
        val sb = StringBuilder()
        stream(e, prompt).collect { sb.append(it) }
        return sb.toString()
    }

    /** Frees the model memory (~0.7–1.7 GB). Safe to call any time. */
    fun close() {
        synchronized(this) { loading?.cancel(); loading = null }
        val e = engine
        engine = null; backendUsed = null
        runCatching { e?.close() }
    }
}

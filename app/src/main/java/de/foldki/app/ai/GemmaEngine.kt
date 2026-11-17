package de.foldki.app.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
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
        // Real chat format (like ChatGPT/Jarvis): instruction as system, earlier turns as real messages,
        // the question as its own message. Small models otherwise copy their old answers from one text block.
        val turns = history.filter { !it.isError && it.text.isNotBlank() }
        val last = turns.lastOrNull { it.role == Role.USER } ?: return@flow
        val earlier = turns.subList(0, turns.lastIndexOf(last)).takeLast(10).dropWhile { it.role != Role.USER }
        val sys = buildString {
            append(system.trim())
            val docs = recentDocs(history)
            if (docs.isNotBlank()) append("\n\nInhalt der angehängten Dateien:\n").append(docs)
            if (web.isNotBlank()) append("\n\nErgebnisse einer Internetsuche (nutze sie, nenne die Quelle):\n").append(web.take(6000))
        }
        val initial = earlier.map { m ->
            val t = de.foldki.app.export.DocumentParser.display(m.text).take(2000)
            if (m.role == Role.USER) Message.user(t) else Message.model(t)
        }
        // Letters/documents and reading a letter: low temperature = exact words and numbers. Normal talk stays lively.
        val precise = system.contains("---DOKUMENT---") || system.contains("BRIEFDATUM:")
        chat(e, sys, initial, last.text, precise).collect { emit(it) }
    }

    /** Text of files from the last 3 questions only (older attachments do not take over every answer). */
    private fun recentDocs(history: List<ChatMessage>): String {
        val recentUser = history.filter { it.role == Role.USER }.takeLast(3)
        // The most recently attached letter always stays visible in this chat (otherwise Gemma says "no file").
        val latest = history.lastOrNull { it.role == Role.USER && it.attachments.isNotEmpty() }
        val files = (listOfNotNull(latest) + recentUser.asReversed()).flatMap { it.attachments }.distinctBy { it.id }
        val sb = StringBuilder()
        var budget = 16000
        files.forEach { a ->
            if (budget <= 0 || a.text.isBlank()) return@forEach
            val t = a.text.take(budget); budget -= t.length
            sb.append("--- ").append(a.name).append(" ---\n").append(t).append("\n\n")
        }
        return sb.toString()
    }

    private fun chat(e: Engine, system: String, initial: List<Message>, question: String, precise: Boolean = false): Flow<String> = flow {
        val conv = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                initialMessages = initial,
                samplerConfig = if (precise) SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2)
                    else SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
            ),
        )
        var done = false
        val pieces = ArrayList<String>() // for the error report: exactly what Gemma delivers
        try {
            conv.sendMessageAsync(question).collect { m ->
                val t = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                if (t.isNotEmpty()) { if (pieces.size < 60) pieces += t; emit(t) }
            }
            done = true
        } finally {
            if (!done) runCatching { conv.cancelProcess() }
            runCatching { conv.close() }
            de.foldki.app.data.DebugLog.write(
                "Gemma Rohteile (" + backendUsed + ", " + (if (precise) "genau" else "locker") + "): " +
                    pieces.joinToString("|") { it.replace("\n", "⏎") }.take(1500),
            )
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Multi-step conversation (GitHub tasks): Gemma answers, [onTurn] gets the whole answer and returns the next
     * message (e.g. a tool result) or null to stop. One conversation, so Gemma keeps the context of earlier steps.
     */
    suspend fun session(system: String, first: String, maxSteps: Int, onTurn: suspend (String) -> String?) =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            val e = engine ?: throw IllegalStateException("Gemma ist nicht geladen.")
            val conv = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2),
                ),
            )
            try {
                var msg: String? = first
                var step = 0
                while (msg != null && step < maxSteps) {
                    step++
                    val sb = StringBuilder()
                    conv.sendMessageAsync(msg).collect { m ->
                        sb.append(m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
                    }
                    de.foldki.app.data.DebugLog.write("Gemma GitHub Schritt $step: " + sb.toString().replace("\n", "⏎").take(800))
                    msg = onTurn(sb.toString())
                }
                step
            } finally {
                runCatching { conv.close() }
            }
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

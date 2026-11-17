package de.foldki.app.ai

import android.content.Context
import de.foldki.app.data.ChatMessage
import de.foldki.app.data.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * The only AI of FA Fold KI: a GGUF model on the phone via llama.cpp (CPU, arm64 with dotprod/i8mm/KleidiAI).
 * Port of `Llm.java` from Jarvis-Android. Context 8192 tokens, up to 4000 answer tokens; if the phone has
 * not enough memory, the native side falls back to 4096 and then 2048 tokens of context.
 * The chat template (Jinja) comes from the GGUF file. Output is raw text incl. thinking; see [ThinkParser].
 */
class LlamaEngine(context: Context) {
    private val app = context.applicationContext
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadMutex = Mutex()
    private val genMutex = Mutex()
    @Volatile var loadedPath: String? = null
        private set
    @Volatile var contextSize: Int = 0
        private set
    /** JSON stats of the last generation (tokens, speed, …) for the error report. */
    @Volatile var lastStats: String = ""
        private set
    private var loading: Deferred<Unit>? = null

    val isLoaded: Boolean get() = loadedPath != null

    companion object {
        const val N_CTX = 8192
        const val MAX_ANSWER_TOKENS = 4000

        /** Thinking stops after this many tokens ("</think>" is inserted natively), so an answer always follows. */
        const val THINK_BUDGET = 1200
        const val MAX_TASK_TOKENS = 1024
        /** Characters of attachment text that fit next to 4000 answer tokens in 8192 tokens of context. */
        const val DOC_CHARS = 8000
        const val HISTORY_CHARS = 5000
        const val WEB_CHARS = 3000
    }

    /** Loads [path] (once; another path replaces the loaded model). Throws with a German message on failure. */
    fun loadAsync(path: String): Deferred<Unit> = synchronized(this) {
        loading?.takeIf { it.isActive }?.let { return it }
        loadScope.async {
            loadMutex.withLock {
                if (loadedPath == path) return@withLock
                if (!LlamaNative.available) throw IllegalStateException("llama.cpp fehlt in dieser APK: ${LlamaNative.loadError}")
                genMutex.withLock {
                    if (loadedPath != null) { LlamaNative.nativeUnload(); loadedPath = null }
                    val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
                    if (!LlamaNative.nativeLoad(path, N_CTX, threads)) {
                        throw IllegalStateException(LlamaNative.nativeError().ifBlank { "Modell konnte nicht geladen werden." })
                    }
                    contextSize = LlamaNative.nativeContextSize()
                    loadedPath = path
                }
            }
        }.also { loading = it }
    }

    /** One generation; emits raw text pieces (thinking included). Stops natively when the collector is cancelled. */
    fun generate(
        messages: List<Pair<String, String>>, maxTokens: Int, think: Boolean, precise: Boolean = false,
        onThinkOpen: (Boolean) -> Unit = {},
    ): Flow<String> = callbackFlow {
        if (loadedPath == null) throw IllegalStateException("Kein Modell geladen.")
        val job = launch(Dispatchers.Default) {
            genMutex.withLock {
                var first = true
                val stats = LlamaNative.nativeGenerate(
                    messages.map { it.first }.toTypedArray(), messages.map { it.second }.toTypedArray(),
                    maxTokens, if (precise) 0.3f else 0.6f, 20, 0.95f, think, if (think) THINK_BUDGET else 0,
                ) { bytes ->
                    if (first) { first = false; onThinkOpen(LlamaNative.nativeThinkOpen()) }
                    trySend(String(bytes, Charsets.UTF_8))
                }
                lastStats = stats
                val err = runCatching { JSONObject(stats).optString("error") }.getOrDefault("")
                if (err.isNotBlank()) close(IllegalStateException(err)) else close()
            }
        }
        awaitClose { if (job.isActive) LlamaNative.nativeStop() }
    }.flowOn(Dispatchers.Default)

    /**
     * Chat answer. System message = [system] exactly as given (the user's own instruction + his active memory entries);
     * if blank there is no system message at all. Attached-file text and web results are plain data, put before the
     * question in the last user turn. [correction] = the user marked the previous answer as wrong: one hint line, thinking on.
     */
    fun reply(
        system: String, history: List<ChatMessage>, webSearch: Boolean, correction: Boolean = false,
        onThinkOpen: (Boolean) -> Unit = {},
    ): Flow<String> = flow {
        var web = ""
        if (webSearch) {
            val question = history.lastOrNull { it.role == Role.USER }?.text.orEmpty()
            val terms = Prompts.firstLineTerms(complete("", Prompts.searchTermsPrompt(question)))
            web = WebLookup.search(terms.ifBlank { question.take(100) })
        }
        val turns = history.filter { !it.isError && it.text.isNotBlank() }
        val last = turns.lastOrNull { it.role == Role.USER } ?: return@flow
        val data = Prompts.userData(
            docs = recentDocs(history),
            noTextNames = history.flatMap { it.attachments }.filter { it.text.isBlank() }.map { it.name },
            web = web.take(WEB_CHARS),
            correction = correction,
        )
        // Earlier turns: only answers (never the thoughts), newest kept first when the budget is tight.
        val earlier = turns.subList(0, turns.lastIndexOf(last)).takeLast(10)
        val msgs = ArrayList<Pair<String, String>>()
        var budget = HISTORY_CHARS
        for (m in earlier.asReversed()) {
            val t = (if (m.role == Role.USER) m.text else de.foldki.app.export.DocumentParser.display(m.text)).take(1500)
            if (budget - t.length < 0) break
            budget -= t.length
            msgs.add(0, (if (m.role == Role.USER) "user" else "assistant") to t)
        }
        while (msgs.isNotEmpty() && msgs.first().first != "user") msgs.removeAt(0)
        val all = Prompts.chatMessages(system, msgs, data, last.text)
        val think = correction || Prompts.wantsThinking(last.text, history.any { it.attachments.isNotEmpty() })
        generate(all, MAX_ANSWER_TOKENS, think = think, onThinkOpen = onThinkOpen).collect { emit(it) }
    }

    /** Short internal task without thinking (search terms, letter reading, briefing). Returns only the answer. */
    suspend fun complete(system: String, user: String, maxTokens: Int = MAX_TASK_TOKENS, precise: Boolean = true): String {
        val sb = StringBuilder()
        val open = java.util.concurrent.atomic.AtomicBoolean(false)
        val msgs = (if (system.isBlank()) emptyList() else listOf("system" to system)) + ("user" to user)
        generate(msgs, maxTokens, think = false, precise = precise) { open.set(it) }.collect { sb.append(it) }
        return ThinkParser.answerOnly(sb.toString(), open.get()).trim()
    }

    /** Letter reading (FA-PDF style) with the attachment's text. */
    suspend fun analyze(system: String, question: String, docs: List<de.foldki.app.data.Attachment>): String {
        val text = buildString {
            var budget = DOC_CHARS
            docs.forEach { a ->
                if (budget <= 0 || a.text.isBlank()) return@forEach
                val t = a.text.take(budget); budget -= t.length
                append("--- ").append(a.name).append(" ---\n").append(t).append("\n\n")
            }
        }
        return complete(system + if (text.isBlank()) "" else "\n\nInhalt der angehängten Dateien:\n$text", question)
    }

    /** Background learning from a correction: one short line in the memory form, or "" (no thinking, short). */
    suspend fun correctionLine(question: String, wrong: String, userMessage: String, newAnswer: String): String =
        de.foldki.app.data.Corrections.parseExtraction(
            complete("", de.foldki.app.data.Corrections.extractionPrompt(question, wrong, userMessage, newAnswer), maxTokens = 200),
        ).orEmpty()

    /** Daily briefing: own short instruction, facts at most 3500 characters. */
    suspend fun brief(facts: String): String = complete(Prompts.BRIEF_SYSTEM, facts.take(3500) + "\n\nTagesstart:", precise = false)

    /**
     * Multi-step conversation (GitHub tasks): the model answers (with thinking), [onTurn] gets the answer part and
     * returns the next message (e.g. a tool result) or null to stop. Old steps are dropped when the context gets full.
     */
    suspend fun session(system: String, first: String, maxSteps: Int, onTurn: suspend (String) -> String?): Int {
        val msgs = ArrayList<Pair<String, String>>()
        msgs += "user" to first
        var step = 0
        var next: String? = first
        while (next != null && step < maxSteps) {
            step++
            // Keep system + first question + the newest steps within ~16 000 characters.
            while (msgs.size > 3 && msgs.sumOf { it.second.length } > 16_000) { msgs.removeAt(1); msgs.removeAt(1) }
            val sb = StringBuilder()
            val open = java.util.concurrent.atomic.AtomicBoolean(false)
            generate(listOf("system" to system) + msgs, MAX_ANSWER_TOKENS, think = true, precise = true) { open.set(it) }
                .collect { sb.append(it) }
            val answer = ThinkParser.answerOnly(sb.toString(), open.get())
            de.foldki.app.data.DebugLog.write("Lokal GitHub Schritt $step: " + answer.replace("\n", "⏎").take(800))
            msgs += "assistant" to answer
            next = onTurn(answer)
            if (next != null) msgs += "user" to next
        }
        return step
    }

    /** Text of files from the last 3 questions only (the newest attached letter always stays visible). */
    private fun recentDocs(history: List<ChatMessage>): String {
        val recentUser = history.filter { it.role == Role.USER }.takeLast(3)
        val latest = history.lastOrNull { it.role == Role.USER && it.attachments.isNotEmpty() }
        val files = (listOfNotNull(latest) + recentUser.asReversed()).flatMap { it.attachments }.distinctBy { it.id }
        val sb = StringBuilder()
        var budget = DOC_CHARS
        files.forEach { a ->
            if (budget <= 0 || a.text.isBlank()) return@forEach
            val t = a.text.take(budget); budget -= t.length
            sb.append("Angehängte Datei ").append(a.name).append(":\n").append(t).append("\n\n")
        }
        return sb.toString()
    }

    fun stop() { if (isLoaded) runCatching { LlamaNative.nativeStop() } }

    /** Frees the model memory. Safe to call any time (waits for a running answer to stop). */
    fun close() {
        synchronized(this) { loading?.cancel(); loading = null }
        if (loadedPath == null) return
        loadScope.launch {
            runCatching { LlamaNative.nativeStop() }
            genMutex.withLock { runCatching { LlamaNative.nativeUnload() }; loadedPath = null; contextSize = 0 }
        }
    }
}

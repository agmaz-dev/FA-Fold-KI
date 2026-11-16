package de.foldki.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.foldki.app.ai.ClaudeEngine
import de.foldki.app.ai.GemmaEngine
import de.foldki.app.ai.LocalModelManager
import de.foldki.app.ai.NanoEngine
import de.foldki.app.data.Attachment
import de.foldki.app.data.Attachments
import de.foldki.app.data.ChatMessage
import de.foldki.app.data.ChatStore
import de.foldki.app.data.Conversation
import de.foldki.app.data.Engine
import de.foldki.app.data.Role
import de.foldki.app.data.Settings
import de.foldki.app.export.DocumentParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val conversations: List<Conversation> = emptyList(),
    val currentId: String? = null,
    val engine: Engine = Engine.NANO,
    val model: String = Settings.DEFAULT_MODEL,
    val apiKeySet: Boolean = false,
    val generating: Boolean = false,
    val nanoStatus: String = "Prüfe Gemini Nano…",
    val nanoReady: Boolean = false,
    val nanoDownloadable: Boolean = false,
    val pending: List<Attachment> = emptyList(),
    val importing: Boolean = false,
    val notice: String? = null,
    val webSearch: Boolean = false,
    /** Optional Gemma 4 E2B: download state and the switch "Gemma verwenden". */
    val gemma: LocalModelManager.State = LocalModelManager.State.NotDownloaded,
    val gemmaEnabled: Boolean = false,
) {
    /** Gemma answers instead of Nano (Nano stays the fallback). */
    val gemmaActive: Boolean get() = engine == Engine.NANO && gemmaEnabled && gemma is LocalModelManager.State.Ready
    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }
}

/** Words that mean "please write me a letter". */
private val LETTER_WORDS = Regex(
    "brief|antwortschreiben|gegenschreiben|widerspruch|einspruch|kündigung|anschreiben|bewerbung|mahnung|beschwerde|" +
        "ein schreiben|das schreiben|formelles schreiben|schreib (ihm|ihr|ihnen|denen|zurück)|antworte (ihm|ihr|ihnen|denen|darauf)|" +
        "formulier",
    RegexOption.IGNORE_CASE,
)

const val READY = "Sprachfunktion ist eingerichtet."

private const val VOICE_LETTER_RULES = """
Das Gespräch läuft per Sprache. Nur der Text außerhalb von ---DOKUMENT--- … ---ENDE--- wird vorgelesen: Halte ihn kurz und gesprochen."""

private const val VOICE_RULES = """
Das Gespräch läuft gerade per Sprache: Deine Antwort wird vorgelesen.
Antworte kurz und natürlich gesprochen (meist 1 bis 4 Sätze), ohne Listen, Tabellen, Emojis oder Sonderzeichen."""

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app)
    val settings = Settings(app)
    private val nano = NanoEngine()
    private val claude = ClaudeEngine()
    val models = LocalModelManager(app)
    private val gemma = GemmaEngine(app)
    private val gemmaBusy = AtomicInteger(0)
    private var gemmaGenerating = false
    private var idleJob: Job? = null
    val attachments = Attachments(app)
    val voice = de.foldki.app.voice.VoiceController(app).apply {
        rate = settings.ttsRate; supertonicVoice = settings.stVoice
    }

    // One-time setup of the voice function: Tessa & Caleb (Supertonic) + speech recognition (Whisper).
    private val _stStatus = MutableStateFlow(if (voice.isReady) READY else "Noch nicht eingerichtet.")
    val stStatus: StateFlow<String> = _stStatus.asStateFlow()
    private val _stBusy = MutableStateFlow(false)
    val stBusy: StateFlow<Boolean> = _stBusy.asStateFlow()
    private var stJob: Job? = null

    fun setupVoice() {
        if (_stBusy.value) return
        _stBusy.value = true
        stJob = viewModelScope.launch {
            try {
                voice.supertonic.prepare { line -> _stStatus.value = line }
                voice.whisper.prepare { line -> _stStatus.value = line }
                _stStatus.value = READY
            } catch (e: kotlinx.coroutines.CancellationException) {
                _stStatus.value = "Abgebrochen."
            } catch (e: Throwable) {
                _stStatus.value = "Fehler: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _stBusy.value = false
            }
        }
    }

    fun cancelVoiceSetup() { stJob?.cancel() }

    fun saveVoice(stVoice: String, rate: Float) {
        settings.stVoice = stVoice; settings.ttsRate = rate
        voice.supertonicVoice = stVoice; voice.rate = rate
    }

    override fun onCleared() { voice.release(); gemma.close() }

    private var job: Job? = null

    private val _ui = MutableStateFlow(
        UiState(
            conversations = store.load(),
            engine = settings.engine,
            model = settings.claudeModel,
            apiKeySet = settings.apiKey.isNotBlank(),
            webSearch = settings.webSearch,
            gemmaEnabled = settings.gemmaEnabled,
            notice = gemmaCrashCheck(),
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        checkNano()
        models.beforeDelete = { gemma.close() }
        viewModelScope.launch {
            models.state.collect { st ->
                _ui.update { it.copy(gemma = st) }
                // Model gone (deleted/failed): the switch goes off, Nano answers again.
                if (st is LocalModelManager.State.NotDownloaded || st is LocalModelManager.State.Failed) {
                    if (settings.gemmaEnabled) settings.gemmaEnabled = false
                    _ui.update { it.copy(gemmaEnabled = false) }
                }
            }
        }
        // Too hot: free Gemma's memory, Nano answers.
        if (android.os.Build.VERSION.SDK_INT >= 29) runCatching {
            power.addThermalStatusListener(app.mainExecutor) { status ->
                if (status >= android.os.PowerManager.THERMAL_STATUS_SEVERE && !gemmaGenerating) gemma.close()
            }
        }
    }

    private val power get() = getApplication<Application>().getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager

    /** Crash guard: Gemma was still loading/answering when the app died last time -> switch off, Nano again. */
    private fun gemmaCrashCheck(): String? {
        if (!settings.gemmaLoading) return null
        settings.gemmaLoading = false
        settings.gemmaCrashCount = settings.gemmaCrashCount + 1
        settings.gemmaEnabled = false
        if (settings.gemmaAttempt == "GPU") settings.gemmaBackend = "CPU" // next try goes straight to the CPU
        return "Gemma hat die App beendet – Nano ist wieder aktiv"
    }

    private fun tooHot(): Boolean = android.os.Build.VERSION.SDK_INT >= 29 &&
        runCatching { power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE }.getOrDefault(false)

    fun downloadGemma() = models.download()
    fun cancelGemma() = models.cancel()
    fun deleteGemma() {
        if (gemmaGenerating) stop()
        settings.gemmaEnabled = false
        _ui.update { it.copy(gemmaEnabled = false) }
        models.delete()
    }

    fun setGemmaEnabled(on: Boolean) {
        val v = on && models.isReady
        settings.gemmaEnabled = v
        if (v) settings.gemmaCrashCount = 0
        if (!v) gemma.close()
        _ui.update { it.copy(gemmaEnabled = v) }
    }

    /** App in the background or memory is low: free Gemma's memory (it loads again on the next question). */
    fun onBackground() { if (!gemmaGenerating) gemma.close() }

    private fun gemmaBegin() { if (gemmaBusy.incrementAndGet() == 1) settings.gemmaLoading = true }
    private fun gemmaEnd() { if (gemmaBusy.decrementAndGet() <= 0) { gemmaBusy.set(0); settings.gemmaLoading = false } }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch { delay(5 * 60_000L); if (!gemmaGenerating) gemma.close() }
    }

    /**
     * Tries Gemma. Returns false if it fails or times out BEFORE the first word (the caller then asks Nano).
     * Errors after the first word are thrown like with any other engine.
     */
    private suspend fun replyWithGemma(convId: String, msgId: String, system: String, history: List<ChatMessage>, web: Boolean): Boolean {
        if (tooHot()) return false
        idleJob?.cancel()
        gemmaBegin()
        gemmaGenerating = true
        try {
            if (!gemma.isLoaded) {
                val d = gemma.loadAsync(models.modelFile.absolutePath, settings.gemmaBackend == "GPU") { b -> settings.gemmaAttempt = b }
                gemmaBegin(); d.invokeOnCompletion { gemmaEnd() } // stays guarded until the native load really ends
                val backend = try { withTimeoutOrNull(60_000) { d.await() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { null }
                    ?: return false
                if (backend == "CPU" && settings.gemmaBackend == "GPU") settings.gemmaBackend = "CPU"
            }
            settings.gemmaAttempt = gemma.backendUsed ?: settings.gemmaBackend
            editMessage(convId, msgId) { it.copy(engine = Engine.GEMMA) }
            return coroutineScope {
                val ch = produce(capacity = Channel.UNLIMITED) {
                    try { gemma.reply(system, history, web).collect { send(it) } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Throwable) { close(e) }
                }
                val first = withTimeoutOrNull(if (web) 45_000 else 30_000) { ch.receiveCatching() }
                val firstText = first?.getOrNull()
                if (firstText == null) {
                    ch.cancel()
                    editMessage(convId, msgId) { it.copy(engine = Engine.NANO) }
                    return@coroutineScope false
                }
                editMessage(convId, msgId) { it.copy(text = it.text + firstText) }
                for (t in ch) editMessage(convId, msgId) { it.copy(text = it.text + t) }
                true
            }
        } finally {
            gemmaGenerating = false
            gemmaEnd()
            scheduleIdleUnload()
        }
    }

    fun checkNano() = viewModelScope.launch {
        val (text, ready, downloadable) = try {
            when (nano.state()) {
                NanoEngine.State.AVAILABLE -> Triple("Gemini Nano bereit (offline)", true, false)
                NanoEngine.State.DOWNLOADABLE -> Triple("Gemini Nano kann heruntergeladen werden", false, true)
                NanoEngine.State.DOWNLOADING -> Triple("Gemini Nano wird geladen…", false, false)
                NanoEngine.State.UNAVAILABLE -> Triple("Gemini Nano ist auf diesem Gerät nicht verfügbar", false, false)
            }
        } catch (e: Throwable) {
            Triple("Gemini Nano nicht verfügbar: ${e.message ?: e.javaClass.simpleName}", false, false)
        }
        _ui.update { it.copy(nanoStatus = text, nanoReady = ready, nanoDownloadable = downloadable) }
    }

    fun downloadNano() = viewModelScope.launch {
        _ui.update { it.copy(nanoDownloadable = false, nanoStatus = "Download gestartet…") }
        try {
            nano.download().collect { line -> _ui.update { it.copy(nanoStatus = line) } }
        } catch (e: Throwable) {
            _ui.update { it.copy(nanoStatus = e.message ?: "Download fehlgeschlagen") }
        }
        checkNano()
    }

    // ---- Tagesstart (daily briefing), only on the device ----
    private val openItems = de.foldki.app.brief.OpenItemsStore(app)
    private val _brief = MutableStateFlow<de.foldki.app.brief.Briefing?>(null)
    val brief: StateFlow<de.foldki.app.brief.Briefing?> = _brief.asStateFlow()
    private var briefJob: Job? = null
    /** Nano refused because the app was not in the foreground yet: try the summary again on the next open. */
    private var briefRetryAi = false

    /** Called when the app comes to the foreground (from the launcher or another app). */
    fun onAppResumed() {
        if (!settings.dailyBriefEnabled || briefJob?.isActive == true) return
        val current = _brief.value
        if (current != null) { if (briefRetryAi) summarizeBrief(current); return }
        if (!de.foldki.app.brief.DailyBriefing.inWindow()) return
        if (settings.lastBriefDate == de.foldki.app.brief.DailyBriefing.today()) return
        startBrief()
    }

    /** "Heute erneut zeigen" in the settings: shows the briefing right away. */
    fun showBriefAgain() {
        if (!settings.dailyBriefEnabled) return
        briefJob?.cancel(); _brief.value = null
        newChat() // the card is shown on the empty chat page
        startBrief()
    }

    private fun startBrief() {
        val app = getApplication<Application>()
        briefJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val events = if (settings.briefCalendar) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                de.foldki.app.brief.CalendarSource.read(app, now)
            } else emptyList()
            val notifAccess = de.foldki.app.brief.NotificationCollector.isEnabled(app)
            val notifs = if (settings.briefNotifications) de.foldki.app.brief.NotificationCollector.current(app) else emptyList()
            val items = if (settings.briefChats) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val msgs = _ui.value.conversations.flatMap { c ->
                    c.messages.filter { it.role == Role.USER && !it.localOnly }.map { de.foldki.app.brief.TaskExtractor.Message(it.text, c.updatedAt) }
                }
                openItems.merge(de.foldki.app.brief.TaskExtractor.extract(msgs, now), now)
            } else emptyList()
            val b = de.foldki.app.brief.Briefing(
                date = de.foldki.app.brief.DailyBriefing.dateLabel(now),
                events = events, notifications = notifs, openItems = items,
                loading = true, notificationsAllowed = !settings.briefNotifications || notifAccess,
            )
            settings.lastBriefDate = de.foldki.app.brief.DailyBriefing.today(now)
            _brief.value = b
            summarize(b)
        }
    }

    private fun summarizeBrief(b: de.foldki.app.brief.Briefing) {
        briefJob = viewModelScope.launch { summarize(b.copy(loading = true).also { _brief.value = it }) }
    }

    private suspend fun summarize(b: de.foldki.app.brief.Briefing) {
        briefRetryAi = false
        if (b.isEmpty) { _brief.value = b.copy(loading = false); return }
        val summary = try {
            delay(400) // Nano only answers apps in the foreground
            nano.brief(de.foldki.app.brief.DailyBriefing.facts(b)).ifBlank { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Not in the foreground (yet): next open tries again. Otherwise: plain list without AI.
            if (NanoEngine.errorCode(e) == com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED) briefRetryAi = true
            null
        }
        _brief.update { cur -> (cur ?: b).copy(summary = summary, loading = false) }
    }

    fun closeBrief() { briefJob?.cancel(); briefRetryAi = false; _brief.value = null }

    fun toggleOpenItem(id: String, done: Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { openItems.setDone(id, done) }
        _brief.update { b -> b?.copy(openItems = b.openItems.map { if (it.id == id) it.copy(done = done) else it }) }
    }

    /** "Als Chat öffnen": always Gemini Nano; the briefing is marked local and never sent to Claude. */
    fun openBriefAsChat() {
        val b = _brief.value ?: return
        stop()
        val conv = Conversation(
            title = "Tagesstart " + b.date,
            messages = listOf(ChatMessage(role = Role.ASSISTANT, text = de.foldki.app.brief.DailyBriefing.asText(b), engine = Engine.NANO, localOnly = true)),
        )
        setEngine(Engine.NANO)
        upsert(conv)
        _ui.update { it.copy(currentId = conv.id) }
        closeBrief()
    }

    fun setDailyBrief(on: Boolean) {
        settings.dailyBriefEnabled = on
        if (!on) {
            closeBrief()
            openItems.clear()
            de.foldki.app.brief.NotificationCollector.release()
        } else if (settings.briefNotifications) de.foldki.app.brief.NotificationCollector.rebind(getApplication())
    }

    fun setBriefSources(calendar: Boolean, notifications: Boolean, chats: Boolean) {
        settings.briefCalendar = calendar
        settings.briefChats = chats
        if (!chats) openItems.clear()
        if (notifications != settings.briefNotifications) {
            settings.briefNotifications = notifications
            if (notifications && settings.dailyBriefEnabled) de.foldki.app.brief.NotificationCollector.rebind(getApplication())
            else de.foldki.app.brief.NotificationCollector.release()
        }
    }

    fun toggleWeb() {
        val on = !_ui.value.webSearch
        settings.webSearch = on
        _ui.update { it.copy(webSearch = on, notice = if (on) "Websuche an" else "Websuche aus") }
    }

    fun setEngine(e: Engine) { settings.engine = e; _ui.update { it.copy(engine = e) } }

    fun addAttachment(uri: android.net.Uri, deleteAfter: java.io.File? = null) = viewModelScope.launch {
        _ui.update { it.copy(importing = true) }
        try {
            val a = attachments.import(uri, ownNames())
            _ui.update { it.copy(pending = it.pending + a) }
        } catch (e: Throwable) {
            _ui.update { it.copy(notice = "Datei konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            deleteAfter?.let { runCatching { it.delete() } } // camera original, the import made its own copy
            _ui.update { it.copy(importing = false) }
        }
    }

    fun removePending(id: String) = _ui.update { s -> s.copy(pending = s.pending.filterNot { it.id == id }) }

    fun notify(text: String?) = _ui.update { it.copy(notice = text) }

    fun saveSender(sender: String) { settings.sender = sender }

    fun saveSettings(apiKey: String, model: String, system: String) {
        settings.apiKey = apiKey
        settings.claudeModel = model
        settings.systemPrompt = system.ifBlank { Settings.DEFAULT_SYSTEM }
        _ui.update { it.copy(model = model, apiKeySet = apiKey.isNotBlank()) }
    }

    fun newChat() { stop(); _ui.update { it.copy(currentId = null) } }

    fun open(id: String) { stop(); _ui.update { it.copy(currentId = id) } }

    fun delete(id: String) {
        _ui.value.conversations.firstOrNull { it.id == id }?.messages?.flatMap { it.attachments }
            ?.forEach { runCatching { java.io.File(it.path).delete() } }
        _ui.update { s ->
            s.copy(
                conversations = s.conversations.filterNot { it.id == id },
                currentId = if (s.currentId == id) null else s.currentId,
            )
        }
        persist()
    }

    fun stop() { job?.cancel(); job = null; _ui.update { it.copy(generating = false) } }

    /** [voice] = asked in voice mode: answer short and speakable. */
    fun send(text: String, voice: Boolean = false) {
        val pendingFiles = _ui.value.pending
        val prompt = text.trim().ifEmpty { if (pendingFiles.isNotEmpty()) "Was steht in diesem Dokument? Fasse es kurz zusammen." else "" }
        if (prompt.isEmpty() || _ui.value.generating || _ui.value.importing) return
        val engine = _ui.value.engine
        val web = _ui.value.webSearch

        // Create conversation if needed and append user + empty assistant message.
        var conv = _ui.value.current ?: Conversation(title = prompt.take(40))
        val userMsg = ChatMessage(role = Role.USER, text = prompt, attachments = pendingFiles)
        val botMsg = ChatMessage(role = Role.ASSISTANT, text = "", engine = engine, web = web)
        conv = conv.copy(messages = conv.messages + userMsg + botMsg, updatedAt = System.currentTimeMillis())
        upsert(conv)
        _ui.update { it.copy(currentId = conv.id, generating = true, pending = emptyList()) }

        val history = conv.messages.dropLast(1)
        // Document in the previous answer -> keep the rules for follow-ups like "mach es kürzer".
        val lastDoc = history.lastOrNull { it.role == Role.ASSISTANT && !it.isError }
            ?.let { DocumentParser.parse(it.text) }?.takeIf { it.document != null }
        val wantsLetter = (history.filter { it.role == Role.USER }.takeLast(2).map { it.text })
            .any { LETTER_WORDS.containsMatchIn(it) } || lastDoc?.isLetter == true
        val sentDoc = pendingFiles.isNotEmpty() && !wantsLetter
        val system = buildSystem(wantsLetter, followUpDoc = lastDoc != null, history = history) +
            (if (sentDoc) Settings.DOC_RULES + "\n" else "") +
            (if (voice) (if (wantsLetter) VOICE_LETTER_RULES else VOICE_RULES) else "")
        var finished = false
        job = viewModelScope.launch {
            try {
                val useGemma = engine == Engine.NANO && _ui.value.gemmaActive
                val byGemma = useGemma && replyWithGemma(conv.id, botMsg.id, system, history, web)
                if (!byGemma) {
                    if (useGemma) _ui.update { it.copy(notice = "Gemma nicht verfügbar – Antwort von Nano") }
                    val flow = when (engine) {
                        Engine.NANO, Engine.GEMMA -> nano.reply(system, history, web)
                        Engine.CLAUDE -> claude.reply(settings.apiKey, settings.claudeModel, system, history.filterNot { it.localOnly }, web)
                    }
                    flow.collect { delta -> editMessage(conv.id, botMsg.id) { it.copy(text = it.text + delta) } }
                }
                finished = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                editMessage(conv.id, botMsg.id) {
                    it.copy(
                        text = (if (it.text.isBlank()) "" else it.text + "\n\n") +
                            "Fehler: ${e.message ?: e.javaClass.simpleName}",
                        isError = it.text.isBlank(),
                    )
                }
            } finally {
                // No PDF from a half-written letter (stopped or failed).
                if (finished) attachDocumentPdf(conv.id, botMsg.id, wantsLetter)
                // Only the still-current job may end "generating" (a stopped job must not end the next one).
                if (job == null || job === coroutineContext[Job]) _ui.update { it.copy(generating = false) }
                persist()
            }
        }
    }

    /** Puts a finished letter (or a document the user asked for as letter) into the chat as a PDF. */
    private fun attachDocumentPdf(convId: String, msgId: String, wantsLetter: Boolean) {
        val msg = _ui.value.conversations.firstOrNull { it.id == convId }?.messages?.firstOrNull { it.id == msgId } ?: return
        if (msg.isError) return
        val parts = DocumentParser.parse(msg.text)
        val doc = parts.document ?: return
        if (!parts.complete || !(wantsLetter || parts.isLetter)) return
        val pdf = runCatching { attachments.createPdf(pdfText(doc)) }.getOrNull() ?: return
        editMessage(convId, msgId) { it.copy(attachments = it.attachments + pdf) }
    }

    /** The user's own names (never taken as recipient of a reply). */
    private fun ownNames(): List<String> = listOfNotNull(settings.sender.lines().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() })

    private fun today(): String = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY).format(java.util.Date())

    /** Letter rules only when the user actually asks for a letter – otherwise the AI talks normally. */
    private fun buildSystem(letter: Boolean, followUpDoc: Boolean, history: List<ChatMessage> = emptyList()): String = buildString {
        append(settings.systemPrompt).append("\n")
        append(Settings.DOCUMENT_MARKER_RULE).append("\n")
        if (followUpDoc) append(Settings.DOCUMENT_FOLLOWUP_RULE).append("\n")
        append("Heutiges Datum: ").append(today()).append("\n")
        if (letter) {
            append(Settings.LETTER_RULES).append("\n")
            append("Datum für das neue Schreiben (heute): ").append(today()).append("\n")
            // Data of the attached original letter, read by the app from its first page.
            val original = history.lastOrNull { it.role == Role.USER && it.attachments.isNotEmpty() }?.attachments?.lastOrNull()
            if (original != null) {
                append("Datum des angehängten Schreibens: ").append(original.docDate ?: "unbekannt").append("\n")
                append("Empfänger (aus dem Originalschreiben): ").append(original.recipient?.let { "\n$it" } ?: "unbekannt").append("\n")
                original.refNo?.let { append("Zeichen des Originalschreibens: ").append(it).append("\n") }
                original.subject?.let { append("Betreff des Originalschreibens: ").append(it).append("\n") }
            }
            if (settings.sender.isNotBlank()) append("Absender des Nutzers:\n").append(settings.sender).append("\n")
        }
    }

    /** Text that goes into a PDF: letters without a date line get today's date (safety net, no duplicates). */
    fun pdfText(doc: String): String = DocumentParser.ensureDateLine(doc, today(), place = null)

    private fun editMessage(convId: String, msgId: String, f: (ChatMessage) -> ChatMessage) {
        _ui.update { s ->
            s.copy(conversations = s.conversations.map { c ->
                if (c.id != convId) c else c.copy(messages = c.messages.map { if (it.id == msgId) f(it) else it })
            })
        }
    }

    private fun upsert(conv: Conversation) {
        _ui.update { s -> s.copy(conversations = listOf(conv) + s.conversations.filterNot { it.id == conv.id }) }
        persist()
    }

    /** One writer at a time, in order, so an older snapshot can never overwrite a newer one. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val saveDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

    private fun persist() {
        val snapshot = _ui.value.conversations
        viewModelScope.launch(saveDispatcher) { runCatching { store.save(snapshot) } }
    }
}

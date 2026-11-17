package de.foldki.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.foldki.app.ai.LlamaEngine
import de.foldki.app.ai.LocalModelManager
import de.foldki.app.ai.ThinkParser
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
    val engine: Engine = Engine.LOCAL,
    val generating: Boolean = false,
    val pending: List<Attachment> = emptyList(),
    val importing: Boolean = false,
    val notice: String? = null,
    val webSearch: Boolean = false,
    /** Local GGUF models (llama.cpp): running download/import, downloaded files, the chosen one. */
    val modelOp: LocalModelManager.State = LocalModelManager.State.Idle,
    val localModels: List<LocalModelManager.LocalModel> = emptyList(),
    val activeModel: String? = null,
    /** The model of the current answer is still thinking (text before </think>). */
    val thinking: Boolean = false,
    /** A GitHub change waiting for the user's "Freigeben" / "Ablehnen". */
    val approval: Approval? = null,
    val githubSet: Boolean = false,
) {
    /** A model is downloaded and chosen. */
    val modelReady: Boolean get() = activeModel != null && localModels.any { it.file == activeModel }
    /** Display name of the chosen model ("Kein Modell" if none). */
    val modelName: String get() = localModels.firstOrNull { it.file == activeModel }?.name ?: "Kein Modell"
    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }
}

data class Approval(val title: String, val details: String)

/** Clearly a GitHub task (without a token the app explains how to connect). */
private val GITHUB_STRONG = Regex("github|\\brepo(s|sitor(y|ys|ies|ium|ien))?\\b|pull ?request|\\bcommit|\\bapk\\b", RegexOption.IGNORE_CASE)

/** GitHub tasks (go to the local model with GitHub tools when a token is set). */
private val GITHUB_WORDS = Regex(
    "github|\\brepo(s|sitor(y|ys|ies|ium|ien))?\\b|pull ?request|\\bcommit|\\bbranch|workflow|\\bapk\\b|programmier|quellcode|\\bcode\\b|" +
        "\\bbuild\\b|bauen? (die|eine|mir eine) app|app bauen|kotlin|gradle",
    RegexOption.IGNORE_CASE,
)

/** Words that mean "please write me a letter". */
private val LETTER_WORDS = Regex(
    "brief|antwortschreiben|gegenschreiben|widerspruch|einspruch|kündigung|anschreiben|bewerbung|mahnung|beschwerde|" +
        "ein schreiben|das schreiben|formelles schreiben|schreib (ihm|ihr|ihnen|denen|zurück)|antworte (ihm|ihr|ihnen|denen|darauf)|" +
        "formulier|gegengeschreib|gegen-schreiben|antwortbrief|stellungnahme|fertig ?machen|schreiben fertig|fertigstellen|" +
        "schreib (eine|einen|das|die) (antwort|widerspruch|stellungnahme)",
    RegexOption.IGNORE_CASE,
)

/** With a letter in the chat, these mean "write the reply" (rejecting, objecting, not paying …). */
private val REPLY_INTENT = Regex("(nicht (be)?zahl|nicht begleich|lehne .* ab|ablehn|widersprech|antwort|zurückschreib|schreib)", RegexOption.IGNORE_CASE)
private val JUST_READ = Regex("(was steht|zusammenfass|fass .* zusammen|erklär|worum geht|was bedeutet|was heißt)", RegexOption.IGNORE_CASE)

const val READY = "Sprachfunktion ist eingerichtet."

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app)
    val settings = Settings(app)
    val models = LocalModelManager(app)
    private val llm = LlamaEngine(app)
    private val llmBusy = AtomicInteger(0)
    private var llmGenerating = false
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

    override fun onCleared() { voice.release(); llm.close() }

    private var job: Job? = null

    private val _ui = MutableStateFlow(
        UiState(
            conversations = store.load(),
            engine = Engine.LOCAL, // the only AI: the local GGUF model
            githubSet = settings.githubToken.isNotBlank(),
            webSearch = settings.webSearch,
            notice = llmCrashCheck(),
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { de.foldki.app.export.PdfExporter.cleanup(app) } }
        models.beforeDelete = { f -> if (llm.loadedPath == f.absolutePath) { if (llmGenerating) stop(); llm.close() } }
        viewModelScope.launch { models.state.collect { st -> _ui.update { it.copy(modelOp = st) } } }
        viewModelScope.launch { models.models.collect { list -> _ui.update { it.copy(localModels = list) } } }
        viewModelScope.launch {
            models.active.collect { a ->
                _ui.update { it.copy(activeModel = a) }
                // Another model chosen: free the old one, the new one loads with the next question.
                if (llm.loadedPath != null && llm.loadedPath != models.activeFile?.absolutePath && !llmGenerating) llm.close()
            }
        }
        // Too hot: free the model's memory (it loads again with the next question).
        if (android.os.Build.VERSION.SDK_INT >= 29) runCatching {
            power.addThermalStatusListener(app.mainExecutor) { status ->
                if (status >= android.os.PowerManager.THERMAL_STATUS_SEVERE && !llmGenerating) llm.close()
            }
        }
    }

    private val power get() = getApplication<Application>().getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager

    /** Crash guard: the model was still loading/answering when the app died last time -> do not load it automatically. */
    private fun llmCrashCheck(): String? {
        if (!settings.llmLoading) return null
        settings.llmLoading = false
        settings.llmCrashed = settings.llmAttempt
        return "Das Modell hat die App beendet (zu wenig Arbeitsspeicher?) – bitte unter Einstellungen → KI-Modelle prüfen"
    }

    private fun tooHot(): Boolean = android.os.Build.VERSION.SDK_INT >= 29 &&
        runCatching { power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE }.getOrDefault(false)

    // ---- Local models (GGUF, llama.cpp) ----
    /** Download one .gguf file from Hugging Face (null = started, else error shown as notice). */
    fun downloadModel(f: de.foldki.app.ai.HfModels.GgufFile) { models.download(f)?.let { notify(it) } }
    fun cancelModelOp() { if (models.state.value is LocalModelManager.State.Importing) models.cancelImport() else models.cancel() }
    fun importModel(uri: android.net.Uri) = models.import(uri)
    fun selectModel(file: String) {
        if (llmGenerating) stop()
        if (settings.llmCrashed == file) settings.llmCrashed = "" // the user picked it again on purpose
        models.setActive(file)
    }
    fun deleteModel(file: String) {
        if (llmGenerating && llm.loadedPath == models.fileOf(file).absolutePath) stop()
        models.delete(file)
    }
    fun clearModelError() = models.clearError()

    // ---- Long-term memory (on-device only) ----
    val memory = de.foldki.app.data.MemoryStore(app)
    private val _memories = MutableStateFlow(memory.all())
    val memories: StateFlow<List<de.foldki.app.data.MemoryEntry>> = _memories.asStateFlow()
    /** Adds an entry; false if it was empty, too short or already there. */
    fun addMemory(text: String, category: de.foldki.app.data.MemoryCategory = de.foldki.app.data.MemoryCategory.PERSONAL): Boolean {
        val ok = memory.add(text, category) != null
        _memories.value = memory.all()
        return ok
    }
    fun updateMemory(id: String, text: String, category: de.foldki.app.data.MemoryCategory): Boolean {
        val ok = memory.update(id, text, category)
        _memories.value = memory.all()
        return ok
    }
    fun setMemoryActive(id: String, on: Boolean) { memory.setActive(id, on); _memories.value = memory.all() }
    fun removeMemory(id: String) { memory.remove(id); _memories.value = memory.all() }

    private val _autoLearn = MutableStateFlow(settings.autoLearn)
    /** "Automatisch lernen" (default on). */
    val autoLearn: StateFlow<Boolean> = _autoLearn.asStateFlow()
    fun setAutoLearn(on: Boolean) { settings.autoLearn = on; _autoLearn.value = on }

    /** Writes all entries and the flag as readable JSON to the file the user picked (off the main thread). [done] gets a German message. */
    fun exportMemory(uri: android.net.Uri, done: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val json = memory.exportJson(settings.autoLearn)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("Datei konnte nicht geöffnet werden.")
                    "${memory.all().size} Einträge exportiert."
                }.getOrElse { "Export fehlgeschlagen: ${it.message ?: it.javaClass.simpleName}" }
            }
            done(msg)
        }
    }

    /** Reads a memory file (off the main thread), validates it and merges it. Invalid file: nothing changes. */
    fun importMemory(uri: android.net.Uri, done: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw de.foldki.app.data.MemoryStore.InvalidImport("Datei konnte nicht geöffnet werden.")
                    if (bytes.size > 2_000_000) throw de.foldki.app.data.MemoryStore.InvalidImport("Die Datei ist zu groß für eine Gedächtnis-Datei.")
                    val r = memory.merge(de.foldki.app.data.MemoryStore.parseImport(String(bytes, Charsets.UTF_8)))
                    _memories.value = memory.all()
                    "${r.imported} Einträge importiert, ${r.duplicates} doppelt."
                } catch (e: de.foldki.app.data.MemoryStore.InvalidImport) {
                    "Import nicht möglich: ${e.message}"
                } catch (e: Exception) {
                    "Import fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            done(msg)
        }
    }

    /** Background learning from a correction; cancelled when a new message is sent. */
    private var extractJob: Job? = null
    /** Entry written after the last correction (conversation id to entry id); removed if the user objects again. */
    private var lastCorrection: Pair<String, String>? = null
    /** App in the background or memory is low: free the model's memory (it loads again on the next question). */
    fun onBackground() { if (!llmGenerating) llm.close() }

    /** Android is short of memory (app in the background): also free the speech recognition model. */
    fun onLowMemory() { voice.trimMemory() }

    private fun llmBegin() { if (llmBusy.incrementAndGet() == 1) { settings.llmAttempt = models.active.value.orEmpty(); settings.llmLoading = true } }
    private fun llmEnd() { if (llmBusy.decrementAndGet() <= 0) { llmBusy.set(0); settings.llmLoading = false } }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch { delay(5 * 60_000L); if (!llmGenerating) llm.close() }
    }

    /** Text shown when no model is there (chat answer instead of an AI reply). */
    private fun noModelText(): String = when {
        models.models.value.isEmpty() -> "Es ist noch kein KI-Modell auf dem Handy. Bitte unter Einstellungen → KI-Modelle ein GGUF-Modell " +
            "herunterladen (Vorschlag: Qwen3 4B Thinking, 2,5 GB) oder eine eigene .gguf-Datei importieren."
        else -> "Bitte unter Einstellungen → KI-Modelle ein Modell auswählen."
    }

    /** Loads the chosen model if needed (max. 120 s). Returns null if ready, else the reason (German). */
    private suspend fun ensureLoaded(): String? {
        val f = models.activeFile ?: return noModelText()
        if (llm.loadedPath == f.absolutePath) return null
        if (settings.llmCrashed == f.name) return "Dieses Modell hat die App beim letzten Mal beendet (zu wenig Arbeitsspeicher?). " +
            "Wähle es unter Einstellungen → KI-Modelle erneut aus, um es nochmal zu versuchen, oder nimm ein kleineres Modell."
        if (tooHot()) return "Das Handy ist gerade zu heiß. Bitte kurz warten und nochmal fragen."
        val ramGb = models.totalRam() / 1e9
        if (ramGb > 0 && de.foldki.app.ai.HfModels.tooBigFor(models.totalRam(), f.length()))
            de.foldki.app.data.DebugLog.write("Warnung: Modell ${f.name} (${LocalModelManager.gb(f.length())}) ist groß für $ramGb GB RAM")
        val d = llm.loadAsync(f.absolutePath)
        llmBegin(); d.invokeOnCompletion { llmEnd() } // stays guarded until the native load really ends
        return try {
            withTimeoutOrNull(120_000) { d.await() } ?: run {
                de.foldki.app.data.DebugLog.write("Modell nicht geladen (> 120 s)"); return "Das Modell lädt zu lange. Bitte nochmal fragen."
            }
            de.foldki.app.data.DebugLog.write("Modell geladen: ${f.name} · Kontext ${llm.contextSize}")
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            de.foldki.app.data.DebugLog.write("Ladefehler: ${e.stackTraceToString().take(1500)}")
            e.message ?: "Das Modell konnte nicht geladen werden."
        }
    }

    /** Runs [block] with the loaded model (crash guard, idle unload). Returns the reason if no model is usable. */
    private suspend fun <T> withModel(block: suspend () -> T): Pair<T?, String?> {
        idleJob?.cancel()
        llmBegin()
        llmGenerating = true
        try {
            ensureLoaded()?.let { return null to it }
            return block() to null
        } finally {
            llmGenerating = false
            llmEnd()
            scheduleIdleUnload()
        }
    }

    // ---- Tagesstart (daily briefing), only on the device ----
    private val openItems = de.foldki.app.brief.OpenItemsStore(app)
    private val _brief = MutableStateFlow<de.foldki.app.brief.Briefing?>(null)
    val brief: StateFlow<de.foldki.app.brief.Briefing?> = _brief.asStateFlow()
    private var briefJob: Job? = null
    /** Summary could not be written yet: try again on the next open. */
    private var briefRetryAi = false

    /** Called when the app comes to the foreground (from the launcher or another app). */
    fun onAppResumed() {
        voice.prewarm() // speech input: model ready before the first tap
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
            // Without a model (or if it cannot load): plain list without AI.
            withModel { llm.brief(de.foldki.app.brief.DailyBriefing.facts(b)) }.first?.ifBlank { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        _brief.update { cur -> (cur ?: b).copy(summary = summary, loading = false) }
    }

    fun closeBrief() { briefJob?.cancel(); briefRetryAi = false; _brief.value = null }

    fun toggleOpenItem(id: String, done: Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { openItems.setDone(id, done) }
        _brief.update { b -> b?.copy(openItems = b.openItems.map { if (it.id == id) it.copy(done = done) else it }) }
    }

    /** "Als Chat öffnen": the briefing is marked local (stays on the phone). */
    fun openBriefAsChat() {
        val b = _brief.value ?: return
        stop()
        val conv = Conversation(
            title = "Tagesstart " + b.date,
            messages = listOf(ChatMessage(role = Role.ASSISTANT, text = de.foldki.app.brief.DailyBriefing.asText(b), engine = Engine.LOCAL, model = _ui.value.modelName, localOnly = true)),
        )
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

    // ---- PDF preview (before saving or sharing) ----
    data class PdfPreviewState(
        val text: String,
        val fileName: String,
        val file: java.io.File? = null,
        val busy: Boolean = true,
        val editing: Boolean = false,
        /** Shared once: the temp file stays until the cleanup at a later start (the receiver still reads it). */
        val shared: Boolean = false,
    )
    private val _preview = MutableStateFlow<PdfPreviewState?>(null)
    val preview: StateFlow<PdfPreviewState?> = _preview.asStateFlow()
    private var previewJob: Job? = null

    /** Every PDF/share button goes through here: build the PDF (with date line + signature) and show it. */
    fun openPreview(doc: String) {
        val old = _preview.value
        if (old?.file != null && !old.shared) de.foldki.app.export.PdfExporter.discard(old.file)
        val text = pdfText(doc)
        _preview.value = PdfPreviewState(text = text, fileName = de.foldki.app.export.PdfExporter.fileName(text))
        renderPreview()
    }

    private fun renderPreview() {
        val st = _preview.value ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val file = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    de.foldki.app.export.PdfExporter.writeToCache(getApplication(), st.text, st.fileName, activeSignature())
                }
            }.getOrElse {
                notify("PDF konnte nicht erstellt werden: ${it.message ?: it.javaClass.simpleName}")
                _preview.value = null
                return@launch
            }
            _preview.update { cur -> if (cur == null) { de.foldki.app.export.PdfExporter.discard(file); null } else cur.copy(file = file, busy = false, shared = false) }
        }
    }

    fun editPreview(on: Boolean) = _preview.update { it?.copy(editing = on) }

    /** "Text ändern" -> new PDF from the edited text, shown again. */
    fun applyPreviewText(text: String) {
        val st = _preview.value ?: return
        if (st.file != null && !st.shared) de.foldki.app.export.PdfExporter.discard(st.file)
        _preview.value = st.copy(text = DocumentParser.ensureDateLine(text.trim(), today(), settings.activeSender?.city), file = null, busy = true, editing = false)
        renderPreview()
    }

    fun markPreviewShared() = _preview.update { it?.copy(shared = true) }

    /** "Verwerfen" / back gesture. */
    fun closePreview() {
        previewJob?.cancel()
        val st = _preview.value ?: return
        if (st.file != null && !st.shared) de.foldki.app.export.PdfExporter.discard(st.file)
        _preview.value = null
    }

    fun toggleWeb() {
        val on = !_ui.value.webSearch
        settings.webSearch = on
        _ui.update { it.copy(webSearch = on, notice = if (on) "Websuche an" else "Websuche aus") }
    }

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

    // ---- GitHub (local model with tools; every change needs the user's approval) ----
    val githubToken: String get() = settings.githubToken
    fun saveGitHubToken(token: String) {
        if (token.trim() != settings.githubToken) { settings.githubLogin = ""; _githubLogin.value = "" }
        settings.githubToken = token
        _ui.update { it.copy(githubSet = token.isNotBlank()) }
    }

    /** GitHub account name after "Verbinden & prüfen" ("" = not checked / not connected). */
    private val _githubLogin = MutableStateFlow(settings.githubLogin)
    val githubLogin: StateFlow<String> = _githubLogin.asStateFlow()
    private val _githubChecking = MutableStateFlow(false)
    val githubChecking: StateFlow<Boolean> = _githubChecking.asStateFlow()

    /** Saves the token and asks GitHub who it belongs to. */
    fun checkGitHub(token: String) {
        saveGitHubToken(token)
        if (token.isBlank()) { _githubLogin.value = ""; return }
        _githubChecking.value = true
        viewModelScope.launch {
            val r = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    org.json.JSONObject(de.foldki.app.github.GitHubApi(token.trim()).get("/user")).getString("login")
                }
            }
            _githubChecking.value = false
            r.onSuccess { login -> settings.githubLogin = login; _githubLogin.value = login; notify("Verbunden als $login") }
                .onFailure { e -> settings.githubLogin = ""; _githubLogin.value = ""; notify("GitHub: Token wurde nicht angenommen (${e.message?.take(80)})") }
        }
    }

    private var approvalAnswer: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /** Shows the change and waits for "Freigeben" (true) or "Ablehnen" (false). Stopping the answer counts as no. */
    private suspend fun askApproval(title: String, details: String): Boolean {
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        approvalAnswer = d
        _ui.update { it.copy(approval = Approval(title, details)) }
        return try { d.await() } finally {
            approvalAnswer = null
            _ui.update { it.copy(approval = null) }
        }
    }

    fun answerApproval(ok: Boolean) { approvalAnswer?.complete(ok) }

    /** GitHub task: the local model with the token's tools, if a token is there. */
    private fun isGitHubTask(prompt: String, history: List<ChatMessage>): Boolean {
        if (settings.githubToken.isBlank()) return false
        if (GITHUB_WORDS.containsMatchIn(prompt)) return true
        // follow-ups ("ja, mach weiter") in a chat that already worked on GitHub
        return history.takeLast(4).any { m ->
            m.role == Role.ASSISTANT && listOf("✅ Freigegeben", "⛔ Abgelehnt", "🔎 Suche:", "📄 Lese ", "📁 ", "⚙️ Build-Status").any { m.text.contains(it) }
        }
    }

    fun saveSettings(system: String) {
        settings.systemPrompt = system
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

    fun stop() { job?.cancel(); job = null; extractJob?.cancel(); llm.stop(); _ui.update { it.copy(generating = false, thinking = false) } }

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
        val botMsg = ChatMessage(role = Role.ASSISTANT, text = "", engine = engine, web = web, model = _ui.value.modelName)
        conv = conv.copy(messages = conv.messages + userMsg + botMsg, updatedAt = System.currentTimeMillis())
        upsert(conv)
        _ui.update { it.copy(currentId = conv.id, generating = true, pending = emptyList()) }

        val history = conv.messages.dropLast(1)
        // Document in the previous answer -> keep the rules for follow-ups like "mach es kürzer".
        val lastDoc = history.lastOrNull { it.role == Role.ASSISTANT && !it.isError }
            ?.let { DocumentParser.parse(it.text) }?.takeIf { it.document != null }
        val hasLetterInChat = history.any { m -> m.attachments.any { it.analysis != null || it.docDate != null || it.recipient != null } }
        val wantsLetter = (history.filter { it.role == Role.USER }.takeLast(2).map { it.text })
            .any { LETTER_WORDS.containsMatchIn(it) } || lastDoc?.isLetter == true ||
            (hasLetterInChat && REPLY_INTENT.containsMatchIn(prompt) && !JUST_READ.containsMatchIn(prompt))
        // Correction: the user says the previous answer was wrong -> one hint line + thinking for this turn; the lesson is
        // written in the background after the answer (extractJob).
        val prior = history.dropLast(1)
        val prevAnswer = prior.lastOrNull { it.role == Role.ASSISTANT && !it.isError && it.text.isNotBlank() }
        val prevQuestion = prevAnswer?.let { a -> prior.lastOrNull { it.role == Role.USER && prior.indexOf(it) < prior.indexOf(a) } }
        val correcting = prevAnswer != null && prevQuestion != null && de.foldki.app.data.Corrections.isSignal(prompt)
        // The user objects again: the lesson saved after the last correction was wrong too -> remove it.
        lastCorrection?.let { (cid, eid) -> if (correcting && cid == conv.id) { memory.remove(eid); _memories.value = memory.all() } }
        lastCorrection = null
        val prevExtract = extractJob
        prevExtract?.cancel()
        extractJob = null
        // Memory, fully automatic (no dialog): "Merk dir …" and statements about the user ("Ich heiße …") are saved right away
        // as an active entry (category Persönlich), at most 2 per turn (a correction counts too). The notice is informational;
        // the user can edit, switch off or delete every entry in the settings.
        val learned = if (!settings.autoLearn) emptyList() else
            de.foldki.app.data.MemoryStore.factsIn(prompt).take(if (correcting) 1 else 2)
                .map { de.foldki.app.data.MemoryStore.fromChat(it) }
                .filter { memory.add(it, de.foldki.app.data.MemoryCategory.PERSONAL, auto = true) != null }
        if (learned.isNotEmpty()) {
            _memories.value = memory.all()
            _ui.update { it.copy(notice = "Gemerkt: ${learned.joinToString("; ")}") }
        }
        var finished = false
        val t0 = System.currentTimeMillis()
        var logSystem = ""
        var logHistory = 0
        var logError: String? = null
        job = viewModelScope.launch {
            try {
                prevExtract?.join() // a cancelled background extraction must be over before this answer starts
                // Like FA-PDF: the AI reads a new letter once (date, sender, case number, deadline, suggestions).
                analyzeDocs(conv.id, history.flatMap { it.attachments }, engine)
                val history = _ui.value.conversations.firstOrNull { it.id == conv.id }?.messages?.dropLast(1) ?: history
                // System message = the user's own instruction + his ACTIVE memory entries (empty = no system message).
                val localSystem = de.foldki.app.data.MemoryStore.systemMessage(settings.systemPrompt, memory.all(), prompt)
                logSystem = localSystem
                logHistory = history.size
                if (isGitHubTask(prompt, history)) {
                    // GitHub with the local model and tools (only the GitHub token is needed).
                    val (_, problem) = withModel {
                        de.foldki.app.ai.LocalGitHubAgent(llm, settings.githubToken).run(
                            baseSystem = settings.systemPrompt, question = prompt,
                            onText = { t -> editMessage(conv.id, botMsg.id) { it.copy(text = it.text + t) } },
                            approve = { title, details -> askApproval(title, details) },
                        )
                    }
                    if (problem != null) editMessage(conv.id, botMsg.id) { it.copy(text = it.text + problem) }
                    finished = true
                    return@launch
                }
                if (settings.githubToken.isBlank() && GITHUB_STRONG.containsMatchIn(prompt)) {
                    editMessage(conv.id, botMsg.id) {
                        it.copy(text = "Für GitHub-Aufgaben trag bitte unter Einstellungen → GitHub deinen Token ein und tippe auf „Verbinden & prüfen“. " +
                            "Danach arbeitet das lokale Modell auf dem Handy mit deinem GitHub – jede Änderung nur mit deiner Freigabe.")
                    }
                    finished = true
                    return@launch
                }
                val (_, problem) = withModel {
                    val raw = StringBuilder()
                    val open = java.util.concurrent.atomic.AtomicBoolean(false)
                    var shown = ""
                    llm.reply(localSystem, history, web, correction = correcting) { open.set(it) }.collect { delta ->
                        raw.append(delta)
                        val sp = ThinkParser.split(raw.toString(), open.get(), finished = false)
                        if (sp.answer != shown || sp.thinking != _ui.value.thinking) {
                            shown = sp.answer
                            _ui.update { it.copy(thinking = sp.thinking) }
                        }
                        editMessage(conv.id, botMsg.id) { it.copy(text = sp.answer, thoughts = sp.thoughts) }
                    }
                    val end = ThinkParser.split(raw.toString(), open.get(), finished = true)
                    editMessage(conv.id, botMsg.id) { it.copy(text = end.answer, thoughts = end.thoughts) }
                    de.foldki.app.data.DebugLog.write("Lokal Statistik: ${llm.lastStats}")
                }
                _ui.update { it.copy(thinking = false) }
                if (problem != null) editMessage(conv.id, botMsg.id) { it.copy(text = problem) }
                finished = true
                // After the (re-thought) answer: write the lesson in the background (never blocks the answer).
                if (correcting && problem == null && settings.autoLearn) learnCorrection(conv.id, botMsg.id, prevQuestion!!.text, prevAnswer!!.text, prompt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logError = e.stackTraceToString().take(2000)
                editMessage(conv.id, botMsg.id) {
                    it.copy(
                        text = (if (it.text.isBlank()) "" else it.text + "\n\n") +
                            "Fehler: ${e.message ?: e.javaClass.simpleName}",
                        isError = it.text.isBlank(),
                    )
                }
            } finally {
                // Error report (stays on the phone until the user shares it)
                val answer = _ui.value.conversations.firstOrNull { it.id == conv.id }?.messages?.firstOrNull { it.id == botMsg.id }
                de.foldki.app.data.DebugLog.request(
                    engine = (answer?.engine ?: engine).label + (if (voice) " · Sprachmodus" else "") + (if (web) " · Web" else "") +
                        (if (!finished && logError == null) " · abgebrochen" else ""),
                    system = logSystem, historyCount = logHistory, question = prompt, answer = answer?.text.orEmpty(),
                    firstTokenMs = null, totalMs = System.currentTimeMillis() - t0, error = logError,
                )
                // No PDF from a half-written letter (stopped or failed).
                if (finished) attachDocumentPdf(conv.id, botMsg.id, wantsLetter, openPreview = !voice)
                // Only the still-current job may end "generating" (a stopped job must not end the next one).
                if (job == null || job === coroutineContext[Job]) _ui.update { it.copy(generating = false, thinking = false) }
                persist()
            }
        }
    }

    /**
     * Background: one short model call (no thinking, internal prompt [de.foldki.app.data.Corrections.extractionPrompt]) turns
     * question + wrong answer + correction (+ the corrected answer) into one memory entry, category "Rätsel & Logik".
     */
    private fun learnCorrection(convId: String, botId: String, question: String, wrong: String, userMsg: String) {
        val answer = _ui.value.conversations.firstOrNull { it.id == convId }?.messages?.firstOrNull { it.id == botId }?.text.orEmpty()
        val newAnswer = if (de.foldki.app.data.Corrections.givesAnswer(userMsg)) "" else DocumentParser.display(answer)
        extractJob = viewModelScope.launch {
            val line = runCatching {
                withModel { llm.correctionLine(question, DocumentParser.display(wrong), userMsg, newAnswer) }.first.orEmpty()
            }.getOrDefault("")
            if (line.isBlank() || !isActive) return@launch
            val e = memory.add(line, de.foldki.app.data.MemoryCategory.PUZZLE, auto = true) ?: return@launch
            _memories.value = memory.all()
            lastCorrection = convId to e.id
            _ui.update { it.copy(notice = "Gemerkt: $line") }
        }
    }

    /** Puts a finished letter (or a document the user asked for as letter) into the chat as a PDF. */
    private fun attachDocumentPdf(convId: String, msgId: String, wantsLetter: Boolean, openPreview: Boolean = false) {
        val msg = _ui.value.conversations.firstOrNull { it.id == convId }?.messages?.firstOrNull { it.id == msgId } ?: return
        if (msg.isError) return
        val parts = DocumentParser.parse(msg.text)
        val rawDoc = parts.document ?: return
        if (!parts.complete || !(wantsLetter || parts.isLetter)) return
        // FA-PDF: fill leftover placeholders from the letter's data, end a cut-off text cleanly.
        val original = _ui.value.conversations.firstOrNull { it.id == convId }?.messages
            ?.lastOrNull { it.role == Role.USER && it.attachments.isNotEmpty() }?.attachments?.lastOrNull()
        val facts = original?.analysis?.let { de.foldki.app.export.LetterAnalysis.parse(it) }
        val ref = original?.refNo ?: facts?.reference
        val fixed = de.foldki.app.export.LetterAnalysis.repairNumbers(
            de.foldki.app.export.LetterAnalysis.fixPlaceholders(rawDoc, original?.docDate, ref, facts?.amount, facts?.deadline, facts?.contact),
            amount = facts?.amount,
            dates = listOfNotNull(original?.docDate ?: facts?.date?.ifBlank { null }, facts?.deadlineDate?.ifBlank { null }, today()),
            reference = ref,
        )
        val recipient = de.foldki.app.export.LetterMeta.completeRecipient(
            original?.recipient ?: facts?.sender?.ifBlank { null }, original?.text.orEmpty(), facts?.sender,
        )
        val profile = settings.activeSender
        // Like FA-PDF: the model wrote subject + body, the app sets the letter head and the name.
        val doc = de.foldki.app.export.LetterAnalysis.assemble(
            fixed,
            sender = profile?.toLegacyText()?.ifBlank { null },
            recipient = recipient,
            placeDate = listOfNotNull(profile?.city?.ifBlank { null }, today()).joinToString(", "),
            name = profile?.name?.ifBlank { null },
            refs = listOfNotNull(
                original?.docDate?.ifBlank { null }?.let { "Ihr Schreiben vom: $it" },
                de.foldki.app.export.LetterAnalysis.cleanRef(ref).ifBlank { null }?.let { "Ihr Zeichen: $it" },
            ),
        )
        if (doc != rawDoc) editMessage(convId, msgId) { it.copy(text = it.text.replace(rawDoc, doc)) }
        de.foldki.app.export.LetterAnalysis.recipientKey(original?.recipient)?.let { key ->
            settings.activeSender?.id?.let { settings.rememberProfile(key, it) }
        }
        val pdf = runCatching { attachments.createPdf(pdfText(doc), activeSignature()) }.getOrNull() ?: return
        editMessage(convId, msgId) { it.copy(attachments = it.attachments + pdf) }
        // A finished letter opens in the preview right away (check, save, share or change the text).
        if (openPreview && (parts.isLetter || wantsLetter)) openPreview(doc)
    }

    /**
     * FA-PDF style reading: every not yet read letter/photo is read once by the local model (without thinking).
     * The result fills gaps of the rule-based LetterMeta and is stored on the attachment.
     */
    private suspend fun analyzeDocs(convId: String, files: List<Attachment>, engine: Engine) {
        val todo = files.filter { it.analysis == null && it.text.isNotBlank() }
        if (todo.isNotEmpty() && !models.isReady) return
        for (a in todo) {
            _ui.update { it.copy(notice = "Lese das Schreiben …") }
            val raw = withTimeoutOrNull(120_000) { analyzeOne(a, engine) }.orEmpty()
            val r = de.foldki.app.export.LetterAnalysis.parse(raw)
            val updated = a.copy(
                analysis = raw.ifBlank { "-" }, // "-" = tried, do not read again
                docDate = a.docDate ?: r.date.ifBlank { null },
                recipient = a.recipient ?: r.sender.ifBlank { null },
                refNo = a.refNo ?: r.reference.ifBlank { null },
            )
            _ui.update { st -> st.copy(conversations = st.conversations.map { c ->
                if (c.id != convId) c else c.copy(messages = c.messages.map { m ->
                    m.copy(attachments = m.attachments.map { x -> if (x.id == a.id) updated else x })
                })
            }) }
            // Same recipient as before -> the sender profile used last time (FA-PDF).
            de.foldki.app.export.LetterAnalysis.recipientKey(updated.recipient)?.let { key ->
                val pid = settings.rememberedProfile(key)
                val p = settings.senderProfiles.firstOrNull { it.id == pid }
                if (p != null && p.id != settings.activeSender?.id) {
                    setActiveProfile(p.id)
                    _ui.update { it.copy(notice = "Absender: ${p.label.ifBlank { p.name }} (wie beim letzten Mal)") }
                }
            }
        }
        if (todo.isNotEmpty()) persist()
    }

    private suspend fun analyzeOne(a: Attachment, engine: Engine): String = runCatching {
        withModel {
            llm.analyze(de.foldki.app.export.LetterAnalysis.ANALYZE, "Lies das angehängte Schreiben und antworte im verlangten Zeilenformat.", listOf(a))
        }.first.orEmpty()
    }.getOrDefault("")

    /** The user's own names (never taken as recipient of a reply). */
    private fun ownNames(): List<String> = settings.senderProfiles.map { it.name.trim() }.filter { it.isNotEmpty() }

    private fun today(): String = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY).format(java.util.Date())

    /** Text that goes into a PDF: letters without a date line get today's date (safety net, no duplicates). */
    fun pdfText(doc: String): String = DocumentParser.ensureDateLine(doc, today(), place = settings.activeSender?.city)

    /** Signature of the active profile; always drawn into letter PDFs (never sent to any AI). */
    fun activeSignature(): android.graphics.Bitmap? = settings.activeSender?.let { signatures.load(it.id) }

    // ---- Sender profiles (letters) ----
    val signatures = de.foldki.app.data.SignatureStore(app)
    private val _profiles = MutableStateFlow(settings.senderProfiles to settings.activeSender?.id.orEmpty())
    /** All profiles + id of the active one. */
    val profiles: StateFlow<Pair<List<de.foldki.app.data.SenderProfile>, String>> = _profiles.asStateFlow()
    private val _sigVersion = MutableStateFlow(0)
    val sigVersion: StateFlow<Int> = _sigVersion.asStateFlow()

    private fun publishProfiles() { _profiles.value = settings.senderProfiles to (settings.activeSender?.id.orEmpty()) }

    fun addProfile(): de.foldki.app.data.SenderProfile {
        val list = settings.senderProfiles
        val p = de.foldki.app.data.SenderProfile(label = if (list.isEmpty()) "Privat" else "Profil ${list.size + 1}")
        settings.senderProfiles = list + p
        if (list.isEmpty()) settings.activeSenderId = p.id
        publishProfiles()
        return p
    }

    fun updateProfile(p: de.foldki.app.data.SenderProfile) {
        settings.senderProfiles = settings.senderProfiles.map { if (it.id == p.id) p else it }
        publishProfiles()
    }

    fun setActiveProfile(id: String) { settings.activeSenderId = id; publishProfiles() }

    /** Deletes the profile and its signature image. */
    fun deleteProfile(id: String) {
        val rest = settings.senderProfiles.filterNot { it.id == id }
        settings.senderProfiles = rest
        if (settings.activeSenderId == id) settings.activeSenderId = rest.firstOrNull()?.id.orEmpty()
        signatures.delete(id)
        _sigVersion.value++
        publishProfiles()
    }

    fun saveSignature(id: String, drawn: android.graphics.Bitmap): Boolean =
        signatures.save(id, drawn).also { _sigVersion.value++; notify(if (it) "Unterschrift gespeichert" else "Keine Unterschrift erkannt") }

    fun deleteSignature(id: String) { signatures.delete(id); _sigVersion.value++ }

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

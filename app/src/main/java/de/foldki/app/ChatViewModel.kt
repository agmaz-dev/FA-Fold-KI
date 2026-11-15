package de.foldki.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.foldki.app.ai.ClaudeEngine
import de.foldki.app.ai.NanoEngine
import de.foldki.app.data.Attachment
import de.foldki.app.data.Attachments
import de.foldki.app.data.ChatMessage
import de.foldki.app.data.ChatStore
import de.foldki.app.data.Conversation
import de.foldki.app.data.Engine
import de.foldki.app.data.Role
import de.foldki.app.data.Settings
import kotlinx.coroutines.Job
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
) {
    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }
}

const val READY = "Sprachfunktion ist eingerichtet."

private const val VOICE_RULES = """
Das Gespräch läuft gerade per Sprache: Deine Antwort wird vorgelesen.
Antworte kurz und natürlich gesprochen (meist 1 bis 4 Sätze), ohne Listen, Tabellen, Emojis oder Sonderzeichen."""

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app)
    val settings = Settings(app)
    private val nano = NanoEngine()
    private val claude = ClaudeEngine()
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

    override fun onCleared() { voice.release() }

    private var job: Job? = null

    private val _ui = MutableStateFlow(
        UiState(
            conversations = store.load(),
            engine = settings.engine,
            model = settings.claudeModel,
            apiKeySet = settings.apiKey.isNotBlank(),
            webSearch = settings.webSearch,
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init { checkNano() }

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

    fun toggleWeb() {
        val on = !_ui.value.webSearch
        settings.webSearch = on
        _ui.update { it.copy(webSearch = on, notice = if (on) "Websuche an" else "Websuche aus") }
    }

    fun setEngine(e: Engine) { settings.engine = e; _ui.update { it.copy(engine = e) } }

    fun addAttachment(uri: android.net.Uri) = viewModelScope.launch {
        _ui.update { it.copy(importing = true) }
        try {
            val a = attachments.import(uri)
            _ui.update { it.copy(pending = it.pending + a) }
        } catch (e: Throwable) {
            _ui.update { it.copy(notice = "Datei konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
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
        val system = buildSystem() + if (voice) VOICE_RULES else ""
        job = viewModelScope.launch {
            try {
                val flow = when (engine) {
                    Engine.NANO -> nano.reply(system, history, web)
                    Engine.CLAUDE -> claude.reply(settings.apiKey, settings.claudeModel, system, history, web)
                }
                flow.collect { delta -> editMessage(conv.id, botMsg.id) { it.copy(text = it.text + delta) } }
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
                _ui.update { it.copy(generating = false) }
                persist()
            }
        }
    }

    private fun buildSystem(): String = buildString {
        append(settings.systemPrompt).append("\n")
        append(Settings.LETTER_RULES).append("\n")
        append("Heutiges Datum: ").append(java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY).format(java.util.Date())).append("\n")
        if (settings.sender.isNotBlank()) append("Absender des Nutzers (für Briefe):\n").append(settings.sender).append("\n")
    }

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

    private fun persist() {
        val snapshot = _ui.value.conversations
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { store.save(snapshot) } }
    }
}

package de.foldki.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.foldki.app.ai.ClaudeEngine
import de.foldki.app.ai.NanoEngine
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
) {
    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app)
    val settings = Settings(app)
    private val nano = NanoEngine()
    private val claude = ClaudeEngine()
    private var job: Job? = null

    private val _ui = MutableStateFlow(
        UiState(
            conversations = store.load(),
            engine = settings.engine,
            model = settings.claudeModel,
            apiKeySet = settings.apiKey.isNotBlank(),
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

    fun setEngine(e: Engine) { settings.engine = e; _ui.update { it.copy(engine = e) } }

    fun saveSettings(apiKey: String, model: String, system: String) {
        settings.apiKey = apiKey
        settings.claudeModel = model
        settings.systemPrompt = system.ifBlank { Settings.DEFAULT_SYSTEM }
        _ui.update { it.copy(model = model, apiKeySet = apiKey.isNotBlank()) }
    }

    fun newChat() { stop(); _ui.update { it.copy(currentId = null) } }

    fun open(id: String) { stop(); _ui.update { it.copy(currentId = id) } }

    fun delete(id: String) {
        _ui.update { s ->
            s.copy(
                conversations = s.conversations.filterNot { it.id == id },
                currentId = if (s.currentId == id) null else s.currentId,
            )
        }
        persist()
    }

    fun stop() { job?.cancel(); job = null; _ui.update { it.copy(generating = false) } }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _ui.value.generating) return
        val engine = _ui.value.engine

        // Create conversation if needed and append user + empty assistant message.
        var conv = _ui.value.current ?: Conversation(title = prompt.take(40))
        val userMsg = ChatMessage(role = Role.USER, text = prompt)
        val botMsg = ChatMessage(role = Role.ASSISTANT, text = "", engine = engine)
        conv = conv.copy(messages = conv.messages + userMsg + botMsg, updatedAt = System.currentTimeMillis())
        upsert(conv)
        _ui.update { it.copy(currentId = conv.id, generating = true) }

        val history = conv.messages.dropLast(1)
        val system = settings.systemPrompt
        job = viewModelScope.launch {
            try {
                val flow = when (engine) {
                    Engine.NANO -> nano.reply(system, history)
                    Engine.CLAUDE -> claude.reply(settings.apiKey, settings.claudeModel, system, history)
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

package de.foldki.app.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The optional on-device model Gemma 4 E2B (~2.59 GB) or E4B (~3.7 GB) (LiteRT-LM, Apache 2.0).
 * One single download from Hugging Face (fixed revision, size and SHA-256 checked),
 * through Android's DownloadManager: any network (also mobile data), notification,
 * automatic resume after connection loss, cancel. Nothing else of this feature uses the internet.
 */
class LocalModelManager(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("local_model", Context.MODE_PRIVATE)
    private val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    sealed class State {
        data object NotDownloaded : State()
        data class Downloading(val pct: Int, val bytes: Long) : State()
        data class Verifying(val pct: Int) : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.NotDownloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Called before the file is deleted, so the engine can be closed first. */
    var beforeDelete: (() -> Unit)? = null

    /** One downloadable model. [size]/[sha256] = 0/"" until read from the Hugging Face API (E4B). */
    data class Spec(val id: String, val label: String, val repo: String, val fileName: String, val revision: String, val size: Long, val sha256: String)

    /** Selected model: "e2b" (small, fast) or "e4b" (smarter, better for everyday questions). */
    var selectedId: String
        get() = prefs.getString("model", "e2b") ?: "e2b"
        private set(v) { prefs.edit().putString("model", v).apply() }

    val spec: Spec get() = if (selectedId == "e4b") E4B.copy(
        revision = prefs.getString("e4b_rev", "main") ?: "main",
        fileName = prefs.getString("e4b_file", E4B.fileName) ?: E4B.fileName,
        size = prefs.getLong("e4b_size", 0L),
        sha256 = prefs.getString("e4b_sha", "") ?: "",
    ) else E2B

    /** Switch between E2B and E4B (only when nothing is downloading). */
    fun select(id: String) {
        if (id == selectedId || _state.value is State.Downloading || _state.value is State.Verifying) return
        selectedId = id
        refresh()
    }

    private val SIZE: Long get() = spec.size
    private val SHA256: String get() = spec.sha256
    private val FILE_NAME: String get() = spec.fileName
    private val URL: String get() = "https://huggingface.co/${spec.repo}/resolve/${spec.revision}/${spec.fileName}"
    // E2B keeps the old keys (already verified files stay verified after the update)
    private val KEY_ID: String get() = if (selectedId == "e2b") "download_id" else "download_id_e4b"
    private val KEY_VERIFIED: String get() = if (selectedId == "e2b") "verified" else "verified_e4b"

    val dir: File get() = (app.getExternalFilesDir("models") ?: File(app.filesDir, "models")).apply { mkdirs() }
    val modelFile: File get() = File(dir, FILE_NAME)
    private val partFile: File get() = File(dir, "$FILE_NAME.part")

    val isReady: Boolean get() = _state.value is State.Ready

    init { refresh() }

    /** Restores the state after an app start (running download, finished file, …). */
    fun refresh() {
        val id = prefs.getLong(KEY_ID, -1L)
        val f = modelFile
        when {
            SIZE <= 0L -> _state.value = State.NotDownloaded // E4B: size/checksum are read when the download starts
            f.exists() && f.length() == SIZE && prefs.getString(KEY_VERIFIED, null) == stamp(f) -> _state.value = State.Ready
            id >= 0 -> poll(id)
            partFile.exists() && partFile.length() == SIZE -> verify(partFile)
            f.exists() && f.length() == SIZE -> verify(f)
            else -> _state.value = State.NotDownloaded
        }
    }

    /** Free space in the model folder (bytes). */
    fun freeBytes(): Long = runCatching { dir.usableSpace }.getOrDefault(0L)

    fun download() {
        if (_state.value is State.Downloading || _state.value is State.Verifying || _state.value is State.Ready) return
        if (SIZE <= 0L) {
            // E4B: read file name, size and SHA-256 of the current revision from the Hugging Face API, then download.
            _state.value = State.Downloading(0, 0)
            scope.launch {
                val err = resolveE4b()
                if (err != null) _state.value = State.Failed(err) else { _state.value = State.NotDownloaded; download() }
            }
            return
        }
        val need = SIZE + RESERVE
        if (freeBytes() < need) {
            _state.value = State.Failed("Zu wenig Speicher: ${gb(need)} nötig (Modell + 1 GB Reserve), frei sind ${gb(freeBytes())}.")
            return
        }
        runCatching { partFile.delete(); modelFile.delete() }
        val req = DownloadManager.Request(Uri.parse(URL))
            .setTitle("${spec.label} für Fold KI")
            .setDescription("Optionales On-Device-Modell (${gb(SIZE)})")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // Wish of the user: any connection (unlimited mobile data), no Wi-Fi-only restriction.
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationUri(Uri.fromFile(partFile))
        val id = runCatching { dm.enqueue(req) }.getOrElse {
            _state.value = State.Failed("Download konnte nicht gestartet werden: ${it.message ?: it.javaClass.simpleName}")
            return
        }
        prefs.edit().putLong(KEY_ID, id).apply()
        _state.value = State.Downloading(0, 0)
        poll(id)
    }

    fun cancel() {
        val id = prefs.getLong(KEY_ID, -1L)
        pollJob?.cancel()
        if (id >= 0) runCatching { dm.remove(id) }
        prefs.edit().remove(KEY_ID).apply()
        runCatching { partFile.delete() }
        _state.value = State.NotDownloaded
    }

    /** Closes the engine (via [beforeDelete]), deletes the file and frees the 2.59 GB. */
    fun delete() {
        cancel()
        runCatching { beforeDelete?.invoke() }
        runCatching { modelFile.delete(); partFile.delete() }
        prefs.edit().remove(KEY_VERIFIED).apply()
        _state.value = State.NotDownloaded
    }

    private fun poll(id: Long) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val q = runCatching { dm.query(DownloadManager.Query().setFilterById(id)) }.getOrNull()
                if (q == null || !q.moveToFirst()) {
                    q?.close()
                    prefs.edit().remove(KEY_ID).apply()
                    if (partFile.exists() && partFile.length() == SIZE) verify(partFile) else _state.value = State.NotDownloaded
                    return@launch
                }
                val status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val reason = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                q.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        prefs.edit().remove(KEY_ID).apply()
                        verify(partFile)
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        prefs.edit().remove(KEY_ID).apply()
                        runCatching { dm.remove(id) }
                        _state.value = State.Failed("Download fehlgeschlagen (Code $reason). Bitte erneut versuchen.")
                        return@launch
                    }
                    else -> _state.value = State.Downloading(((done * 100) / SIZE).toInt().coerceIn(0, 100), done)
                }
                delay(1000)
            }
        }
    }

    private fun verify(file: File) {
        pollJob?.cancel()
        _state.value = State.Verifying(0)
        pollJob = scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (file.length() != SIZE) return@runCatching false
                    val md = MessageDigest.getInstance("SHA-256")
                    val buf = ByteArray(1 shl 20)
                    var read = 0L
                    var lastPct = -1
                    file.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            read += n
                            val pct = ((read * 100) / SIZE).toInt()
                            if (pct != lastPct) { lastPct = pct; _state.value = State.Verifying(pct) }
                        }
                    }
                    md.digest().joinToString("") { "%02x".format(it) } == SHA256
                }.getOrDefault(false)
            }
            if (!ok) {
                runCatching { file.delete() }
                _state.value = State.Failed("Prüfung fehlgeschlagen (Datei beschädigt). Bitte erneut herunterladen.")
                return@launch
            }
            val target = modelFile
            if (file != target && !file.renameTo(target)) {
                runCatching { file.copyTo(target, overwrite = true); file.delete() }
            }
            prefs.edit().putString(KEY_VERIFIED, stamp(target)).apply()
            _state.value = State.Ready
        }
    }

    private fun stamp(f: File) = "${f.length()}:${f.lastModified()}:$SHA256"

    /** Hugging Face API: commit of the repo and LFS size/sha256 of the .litertlm file. Returns an error text or null. */
    private fun resolveE4b(): String? = runCatching {
        fun get(url: String): String {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "FoldKI (Android chat app)")
            try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
        }
        val repo = E4B.repo
        val rev = org.json.JSONObject(get("https://huggingface.co/api/models/$repo")).getString("sha")
        val tree = org.json.JSONArray(get("https://huggingface.co/api/models/$repo/tree/$rev"))
        val files = (0 until tree.length()).map { tree.getJSONObject(it) }.filter { it.optString("path").endsWith(".litertlm") }
        val f = files.firstOrNull { it.optString("path") == E4B.fileName }
            ?: files.filterNot { it.optString("path").contains("web", true) }.minByOrNull { it.optLong("size") }
            ?: return@runCatching "Keine Modelldatei gefunden."
        val lfs = f.optJSONObject("lfs") ?: return@runCatching "Modelldatei ohne Prüfsumme."
        prefs.edit()
            .putString("e4b_rev", rev).putString("e4b_file", f.getString("path"))
            .putLong("e4b_size", lfs.optLong("size", f.optLong("size")))
            .putString("e4b_sha", lfs.getString("oid"))
            .apply()
        null
    }.getOrElse { "Modellinfo konnte nicht geladen werden: ${it.message ?: it.javaClass.simpleName}" }

    companion object {
        /** Fixed Hugging Face revision of litert-community/gemma-4-E2B-it-litert-lm (ungated, Apache 2.0), LFS size and sha256. */
        val E2B = Spec(
            "e2b", "Gemma 4 E2B", "litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm",
            "2d74ac41a33d7da82f39f7d5720a04c68514a103", 2_588_147_712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        )
        /** Gemma 4 E4B (~3.7 GB): smarter answers for everyday questions. Size/sha256 are read from the API before the download. */
        val E4B = Spec("e4b", "Gemma 4 E4B", "litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm", "main", 0L, "")
        const val RESERVE = 1_000_000_000L

        fun gb(bytes: Long): String = "%.2f GB".format(java.util.Locale.GERMANY, bytes / 1e9)
    }
}

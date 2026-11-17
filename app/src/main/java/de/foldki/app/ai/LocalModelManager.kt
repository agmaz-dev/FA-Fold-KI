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
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * GGUF models on the phone (llama.cpp): download from Hugging Face, import own .gguf files, choose the
 * active one, delete. Downloads go through Android's DownloadManager: any network (also mobile data),
 * notification, automatic resume after connection loss, cancel. At the end the exact size (and the
 * SHA-256 from Hugging Face, if known) is checked. One operation at a time.
 */
class LocalModelManager(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("gguf_models", Context.MODE_PRIVATE)
    private val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    data class LocalModel(val file: String, val name: String, val size: Long, val source: String)

    sealed class State {
        data object Idle : State()
        data class Downloading(val name: String, val pct: Int, val bytes: Long, val total: Long) : State()
        data class Verifying(val name: String, val pct: Int) : State()
        data class Importing(val name: String, val pct: Int) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()
    private val _active = MutableStateFlow<String?>(null)
    /** File name of the chosen model (null = none). */
    val active: StateFlow<String?> = _active.asStateFlow()

    /** Called before a file is deleted, so the engine can be closed first. */
    var beforeDelete: ((File) -> Unit)? = null

    val dir: File get() = (app.getExternalFilesDir("models") ?: File(app.filesDir, "models")).apply { mkdirs() }
    fun fileOf(name: String) = File(dir, name)
    val activeFile: File? get() = _active.value?.let { fileOf(it) }?.takeIf { it.exists() }
    val activeModel: LocalModel? get() = _models.value.firstOrNull { it.file == _active.value }
    val isReady: Boolean get() = activeFile != null
    val busy: Boolean get() = _state.value is State.Downloading || _state.value is State.Verifying || _state.value is State.Importing

    init { refresh() }

    /** Rebuilds the list from the folder and restores a running download after an app start. */
    fun refresh() {
        scanModels()
        val pending = pending()
        if (pending != null && !busy) {
            val id = pending.optLong("id", -1L)
            if (id >= 0) poll(id, pending)
        }
    }

    private fun meta(): JSONObject = runCatching { JSONObject(prefs.getString("meta", "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun saveMeta(o: JSONObject) = prefs.edit().putString("meta", o.toString()).apply()
    private fun pending(): JSONObject? = prefs.getString("pending", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun scanModels() {
        val m = meta()
        val list = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.lowercase().endsWith(".gguf") }
            .sortedBy { it.name.lowercase() }
            .map { f ->
                val o = m.optJSONObject(f.name)
                LocalModel(
                    file = f.name,
                    name = o?.optString("name")?.ifBlank { null } ?: HfModels.displayName(f.name),
                    size = f.length(),
                    source = o?.optString("source")?.ifBlank { null } ?: "Import",
                )
            }
        _models.value = list
        var act = prefs.getString("active", null)
        if (act != null && list.none { it.file == act }) act = null
        if (act == null) act = list.firstOrNull()?.file
        prefs.edit().putString("active", act).apply()
        _active.value = act
    }

    fun setActive(file: String) {
        if (_models.value.none { it.file == file }) return
        prefs.edit().putString("active", file).apply()
        _active.value = file
    }

    /** Free space in the model folder (bytes). */
    fun freeBytes(): Long = runCatching { dir.usableSpace }.getOrDefault(0L)

    fun totalRam(): Long = runCatching {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }.getOrDefault(0L)

    /** Starts the download of one .gguf file. Returns null or an error text (shown by the caller). */
    fun download(f: HfModels.GgufFile): String? {
        if (busy) return "Es läuft schon ein Download oder Import."
        if (f.isSplit) return "Diese Datei ist in mehrere Teile aufgeteilt (…-00001-of-…). Geteilte Modelle unterstützt FA Fold KI nicht – bitte eine einzelne .gguf-Datei wählen."
        if (f.isProjector) return "Das ist nur ein Bild-Zusatz (mmproj), kein Sprachmodell."
        val name = f.fileName
        if (fileOf(name).exists() && (f.size <= 0 || fileOf(name).length() == f.size)) {
            scanModels(); setActive(name); return null
        }
        val need = f.size + RESERVE
        if (f.size > 0 && freeBytes() < need) return "Zu wenig Speicher: ${gb(need)} nötig (Modell + 1 GB Reserve), frei sind ${gb(freeBytes())}."
        val part = File(dir, "$name.part")
        runCatching { part.delete() }
        val req = DownloadManager.Request(Uri.parse(f.url))
            .setTitle("$name für FA Fold KI")
            .setDescription("Lokales KI-Modell (${gb(f.size)})")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // Wish of the user: any connection (also mobile data), no Wi-Fi-only restriction.
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationUri(Uri.fromFile(part))
        val id = runCatching { dm.enqueue(req) }.getOrElse { return "Download konnte nicht gestartet werden: ${it.message ?: it.javaClass.simpleName}" }
        val p = JSONObject().put("id", id).put("file", name).put("repo", f.repo).put("size", f.size)
            .put("sha", f.sha256 ?: "").put("name", HfModels.displayName(name))
        prefs.edit().putString("pending", p.toString()).apply()
        _state.value = State.Downloading(name, 0, 0, f.size)
        poll(id, p)
        return null
    }

    /** Cancels a running download and removes the partial file. */
    fun cancel() {
        val p = pending()
        pollJob?.cancel()
        p?.optLong("id", -1L)?.takeIf { it >= 0 }?.let { runCatching { dm.remove(it) } }
        p?.optString("file")?.takeIf { it.isNotBlank() }?.let { runCatching { File(dir, "$it.part").delete() } }
        prefs.edit().remove("pending").apply()
        _state.value = State.Idle
    }

    /** Closes the engine (via [beforeDelete]) and deletes the model file (and leftover partial files). */
    fun delete(file: String) {
        val f = fileOf(file)
        runCatching { beforeDelete?.invoke(f) }
        runCatching { f.delete(); File(dir, "$file.part").delete(); File(dir, "$file.import").delete() }
        val m = meta(); m.remove(file); saveMeta(m)
        if (prefs.getString("active", null) == file) prefs.edit().remove("active").apply()
        scanModels()
    }

    fun clearError() { if (_state.value is State.Failed) _state.value = State.Idle }

    private fun poll(id: Long, p: JSONObject) {
        val name = p.optString("file")
        val total = p.optLong("size", 0L)
        val part = File(dir, "$name.part")
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val q = runCatching { dm.query(DownloadManager.Query().setFilterById(id)) }.getOrNull()
                if (q == null || !q.moveToFirst()) {
                    q?.close()
                    prefs.edit().remove("pending").apply()
                    if (part.exists() && total > 0 && part.length() == total) verify(part, p) else _state.value = State.Idle
                    return@launch
                }
                val status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val dmTotal = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reason = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                q.close()
                val t = if (total > 0) total else dmTotal
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        prefs.edit().remove("pending").apply()
                        verify(part, p)
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        prefs.edit().remove("pending").apply()
                        runCatching { dm.remove(id) }
                        runCatching { part.delete() }
                        _state.value = State.Failed(
                            if (reason == 401 || reason == 403) "Download abgelehnt (HTTP $reason): Dieses Modell ist geschützt und braucht eine Anmeldung bei Hugging Face."
                            else "Download fehlgeschlagen (Code $reason). Bitte erneut versuchen.",
                        )
                        return@launch
                    }
                    else -> _state.value = State.Downloading(name, if (t > 0) ((done * 100) / t).toInt().coerceIn(0, 100) else 0, done, t)
                }
                delay(1000)
            }
        }
    }

    private fun verify(file: File, p: JSONObject) {
        val name = p.optString("file")
        val size = p.optLong("size", 0L)
        val sha = p.optString("sha").takeIf { it.length == 64 }
        pollJob?.cancel()
        _state.value = State.Verifying(name, 0)
        pollJob = scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    if (size > 0 && file.length() != size) return@runCatching "Größe stimmt nicht (${file.length()} statt $size Bytes)."
                    if (!isGguf(file)) return@runCatching "Die Datei ist kein GGUF-Modell."
                    if (sha != null) {
                        val md = MessageDigest.getInstance("SHA-256")
                        val buf = ByteArray(1 shl 20)
                        var read = 0L
                        var lastPct = -1
                        val len = file.length().coerceAtLeast(1)
                        file.inputStream().use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                md.update(buf, 0, n)
                                read += n
                                val pct = ((read * 100) / len).toInt()
                                if (pct != lastPct) { lastPct = pct; _state.value = State.Verifying(name, pct) }
                            }
                        }
                        if (md.digest().joinToString("") { "%02x".format(it) } != sha) return@runCatching "Prüfsumme (SHA-256) stimmt nicht."
                    }
                    null
                }.getOrElse { it.message ?: it.javaClass.simpleName }
            }
            if (error != null) {
                runCatching { file.delete() }
                _state.value = State.Failed("Prüfung fehlgeschlagen: $error Bitte erneut herunterladen.")
                return@launch
            }
            val target = fileOf(name)
            if (!file.renameTo(target)) runCatching { file.copyTo(target, overwrite = true); file.delete() }
            val m = meta()
            m.put(name, JSONObject().put("name", p.optString("name").ifBlank { HfModels.displayName(name) }).put("source", p.optString("repo").ifBlank { "Hugging Face" }))
            saveMeta(m)
            scanModels()
            setActive(name)
            _state.value = State.Idle
        }
    }

    /** Copies an own .gguf file from the phone (Storage Access Framework) into the model folder. */
    fun import(uri: Uri) {
        if (busy) { _state.value = State.Failed("Es läuft schon ein Download oder Import."); return }
        val cr = app.contentResolver
        var name = "modell.gguf"
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (!name.lowercase().endsWith(".gguf")) { _state.value = State.Failed("Bitte eine .gguf-Datei wählen („$name“ ist keine)."); return }
        if (HfModels.GgufFile("", name, size, null).isSplit) {
            _state.value = State.Failed("Geteilte Modelle (…-00001-of-…) unterstützt FA Fold KI nicht – bitte eine einzelne .gguf-Datei wählen.")
            return
        }
        if (size > 0 && freeBytes() < size + RESERVE) { _state.value = State.Failed("Zu wenig Speicher: ${gb(size + RESERVE)} nötig, frei sind ${gb(freeBytes())}."); return }
        val tmp = File(dir, "$name.import")
        _state.value = State.Importing(name, 0)
        pollJob?.cancel()
        pollJob = scope.launch {
            val error = runCatching {
                cr.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var lastPct = -1
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (size > 0) ((done * 100) / size).toInt() else 0
                            if (pct != lastPct) { lastPct = pct; _state.value = State.Importing(name, pct) }
                        }
                    }
                } ?: return@runCatching "Datei konnte nicht geöffnet werden."
                if (!isActive) return@runCatching "Abgebrochen."
                if (!isGguf(tmp)) return@runCatching "Die Datei ist kein GGUF-Modell."
                null
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            if (error != null) {
                runCatching { tmp.delete() }
                _state.value = State.Failed("Import fehlgeschlagen: $error")
                return@launch
            }
            val target = fileOf(name)
            runCatching { target.delete() }
            if (!tmp.renameTo(target)) runCatching { tmp.copyTo(target, overwrite = true); tmp.delete() }
            val m = meta()
            m.put(name, JSONObject().put("name", HfModels.displayName(name)).put("source", "Import"))
            saveMeta(m)
            scanModels()
            setActive(name)
            _state.value = State.Idle
        }
    }

    fun cancelImport() {
        pollJob?.cancel()
        (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".import") }.forEach { runCatching { it.delete() } }
        _state.value = State.Idle
    }

    private fun isGguf(f: File): Boolean = runCatching {
        f.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF" }
    }.getOrDefault(false)

    companion object {
        const val RESERVE = 1_000_000_000L
        fun gb(bytes: Long): String = "%.2f GB".format(java.util.Locale.GERMANY, bytes / 1e9)
    }
}

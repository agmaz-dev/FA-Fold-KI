package de.foldki.app.brief

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Open items for the daily briefing: filesDir/brief/open_items.json, max 20, removed after 14 days. Only on the device. */
class OpenItemsStore(context: Context) {
    data class Item(val id: String = UUID.randomUUID().toString(), val text: String, val created: Long, val done: Boolean = false)

    private val dir = File(context.filesDir, "brief")
    private val file = File(dir, "open_items.json")

    @Synchronized
    fun load(now: Long = System.currentTimeMillis()): List<Item> = runCatching {
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Item(it.getString("id"), it.getString("text"), it.getLong("created"), it.optBoolean("done"))
        }.filter { now - it.created < EXPIRY }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(items: List<Item>) {
        dir.mkdirs()
        val a = JSONArray()
        items.takeLast(MAX).forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("created", it.created).put("done", it.done)) }
        val tmp = File(dir, "open_items.json.tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    /** Adds newly found to-dos (also known/done ones are not added twice). Returns the open (not done) items. */
    fun merge(found: List<String>, now: Long = System.currentTimeMillis()): List<Item> {
        val items = load(now).toMutableList()
        val known = items.map { TaskExtractor.normalize(it.text) }.toMutableSet()
        found.reversed().forEach { t ->
            val k = TaskExtractor.normalize(t)
            if (k !in known) { items += Item(text = t, created = now); known += k }
        }
        val kept = items.sortedBy { it.created }.takeLast(MAX)
        save(kept)
        return kept.filter { !it.done }
    }

    fun setDone(id: String, done: Boolean) = save(load().map { if (it.id == id) it.copy(done = done) else it })

    /** Feature switched off: everything is deleted. */
    fun clear() { runCatching { file.delete(); File(dir, "open_items.json.tmp").delete() } }

    companion object {
        const val MAX = 20
        const val EXPIRY = 14L * 24 * 60 * 60 * 1000
    }
}

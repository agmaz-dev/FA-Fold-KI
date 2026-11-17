package de.foldki.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Categories of a memory entry (user-chosen, only for sorting and filtering). */
enum class MemoryCategory(val label: String) {
    STYLE("Schreibstil"), PUZZLE("Rätsel & Logik"), PERSONAL("Persönlich"), OTHER("Sonstiges");

    companion object {
        fun of(name: String?): MemoryCategory = values().firstOrNull { it.name == name } ?: OTHER
    }
}

/** One short entry of the user's memory (1–2 sentences). Only entries with [active] go to the model. */
data class MemoryEntry(
    val id: String,
    val text: String,
    val category: MemoryCategory,
    val active: Boolean,
    val created: Long,
)

/** Where the entries are kept (SharedPreferences on the phone; a plain map in unit tests). */
interface MemoryStorage {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

private class PrefsStorage(context: Context) : MemoryStorage {
    private val prefs = context.getSharedPreferences("memory", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

/**
 * The user's own, editable memory: entries with text, category, on/off switch and creation time, kept only on the
 * phone. Entries from older versions (plain list "facts") are migrated once as active entries of the category
 * "Persönlich". Nothing is built in: every entry can be edited, switched off or deleted.
 */
class MemoryStore(private val storage: MemoryStorage, private val now: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(PrefsStorage(context))

    private var cache: List<MemoryEntry>? = null

    /** All entries, oldest first. */
    fun all(): List<MemoryEntry> = cache ?: load().also { cache = it }

    private fun load(): List<MemoryEntry> {
        storage.get(KEY)?.let { json ->
            return runCatching {
                val a = JSONArray(json)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    MemoryEntry(
                        o.getString("id"), o.getString("text"), MemoryCategory.of(o.optString("category")),
                        o.optBoolean("active", true), o.optLong("created", 0L),
                    )
                }
            }.getOrDefault(emptyList())
        }
        // Migration of the old plain list: all active, "Persönlich", order kept.
        val legacy = runCatching {
            val a = JSONArray(storage.get(LEGACY_KEY) ?: "[]")
            (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        val t0 = now()
        val migrated = legacy.mapIndexed { i, f ->
            MemoryEntry(UUID.randomUUID().toString(), clean(f), MemoryCategory.PERSONAL, true, t0 - (legacy.size - i))
        }
        if (migrated.isNotEmpty()) save(migrated)
        return migrated
    }

    private fun save(list: List<MemoryEntry>) {
        cache = list
        val a = JSONArray()
        list.forEach { e ->
            a.put(JSONObject().put("id", e.id).put("text", e.text).put("category", e.category.name).put("active", e.active).put("created", e.created))
        }
        storage.put(KEY, a.toString())
    }

    /** Adds an entry (no duplicate text, at most [MAX] entries). Returns the new entry or null (empty, too short, duplicate). */
    fun add(text: String, category: MemoryCategory = MemoryCategory.PERSONAL, active: Boolean = true): MemoryEntry? {
        val t = clean(text)
        if (t.length < 3) return null
        val list = all()
        if (list.any { it.text.equals(t, true) }) return null
        val e = MemoryEntry(UUID.randomUUID().toString(), t, category, active, maxOf(now(), (list.maxOfOrNull { it.created } ?: 0L) + 1))
        save((list + e).takeLast(MAX))
        return e
    }

    /** Changes text and category of an entry. False if it does not exist or the text is too short. */
    fun update(id: String, text: String, category: MemoryCategory): Boolean {
        val t = clean(text)
        if (t.length < 3) return false
        val list = all()
        if (list.none { it.id == id }) return false
        save(list.map { if (it.id == id) it.copy(text = t, category = category) else it })
        return true
    }

    fun setActive(id: String, active: Boolean) = save(all().map { if (it.id == id) it.copy(active = active) else it })

    fun remove(id: String) = save(all().filterNot { it.id == id })

    companion object {
        const val MAX = 200
        const val MAX_TEXT = 300

        /** Characters of all active entries that go into the system message (keeps the model fast). */
        const val PROMPT_CHARS = 1500

        /** The only fixed text of the memory: one neutral heading line above the user's own entries. */
        const val HEADING = "Gespeicherte Einträge des Nutzers:"

        private const val KEY = "entries"
        private const val LEGACY_KEY = "facts"

        private fun clean(t: String): String = t.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT).trim()

        /**
         * "- text" lines of the active entries below [HEADING]; newest first until [maxChars] is reached, then
         * written oldest to newest. Empty string if no entry is active.
         */
        fun promptBlock(entries: List<MemoryEntry>, maxChars: Int = PROMPT_CHARS): String {
            val active = entries.filter { it.active }.sortedByDescending { it.created }
            var used = HEADING.length
            val chosen = ArrayList<MemoryEntry>()
            for (e in active) {
                val len = e.text.length + 3 // "- " + newline
                if (used + len > maxChars) break
                used += len
                chosen += e
            }
            if (chosen.isEmpty()) return ""
            return buildString {
                append(HEADING)
                chosen.reversed().forEach { append("\n- ").append(it.text) }
            }
        }

        /**
         * System message: the user's own instruction exactly as typed, then the active entries. Blank instruction and
         * no active entry = empty string = no system message at all.
         */
        fun systemMessage(userPrompt: String, entries: List<MemoryEntry>): String {
            val block = promptBlock(entries)
            return when {
                block.isEmpty() -> userPrompt
                userPrompt.isBlank() -> block
                else -> userPrompt.trimEnd() + "\n\n" + block
            }
        }

        /** Start value for "Merken" on an answer: first sentence, max ~200 characters, without Markdown. */
        fun excerpt(answer: String): String {
            val t = answer.replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) return ""
            val end = Regex("[.!?](\\s|$)").find(t)?.range?.first?.plus(1) ?: t.length
            val first = t.substring(0, end)
            if (first.length <= 200) return first
            val cut = first.take(200)
            return (cut.substringBeforeLast(' ', cut)).trimEnd(',', ';', ':') + "…"
        }

        private val EXPLICIT = Regex("^\\s*(bitte\\s+)?(merk|merke)\\s+(dir|es dir)\\s*(bitte\\s*)?[:,]?\\s*(dass\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        private val ABOUT_ME = Regex(
            "\\b(ich heiße|mein name ist|ich wohne|ich lebe in|ich komme aus|ich arbeite|ich bin von beruf|mein beruf|ich bin \\d+ jahre|" +
                "ich habe (eine frau|einen mann|kinder|eine tochter|einen sohn)|meine frau|mein mann|meine tochter|mein sohn|ich mag|ich liebe|ich hasse|" +
                "ich fahre|mein auto|mein geburtstag|ich spreche)\\b[^.!?,\\n]{2,120}?(?=\\s+und\\s+(ich|mein|meine)\\b|[.!?,\\n]|$)",
            RegexOption.IGNORE_CASE,
        )

        /** Wording of an entry saved from "Merk dir …" in the chat: no closing dot, first letter upper case. */
        fun fromChat(fact: String): String = fact.trim().trimEnd('.', '!').replaceFirstChar { it.uppercase() }

        /** What from a message should be remembered (explicit "Merk dir …" or a statement about the user). */
        fun factsIn(message: String): List<String> {
            EXPLICIT.find(message)?.let { return listOf(it.groupValues[6].trim()) }
            return ABOUT_ME.findAll(message).map { it.value.trim() }.filter { it.length >= 8 }.take(3).toList()
        }
    }
}

package de.foldki.app.data

import android.content.Context
import org.json.JSONArray

/**
 * Long-term memory across chats, the way ChatGPT/Claude do it: facts about the user are stored on the phone
 * and put into the instruction of every new chat. Only used for the on-device models (Nano/Gemma), never sent to Claude.
 */
class MemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("memory", Context.MODE_PRIVATE)

    fun all(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("facts", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun save(list: List<String>) = prefs.edit().putString("facts", JSONArray(list).toString()).apply()

    /** Adds a fact (no duplicates, newest last, at most [MAX]). Returns true if it was new. */
    fun add(fact: String): Boolean {
        val f = fact.trim().trimEnd('.', '!').replaceFirstChar { it.uppercase() }
        if (f.length < 3) return false
        val list = all()
        if (list.any { it.equals(f, true) }) return false
        save((list + f).takeLast(MAX))
        return true
    }

    fun remove(index: Int) = save(all().filterIndexed { i, _ -> i != index })

    companion object {
        const val MAX = 60

        private val EXPLICIT = Regex("^\\s*(bitte\\s+)?(merk|merke)\\s+(dir|es dir)\\s*(bitte\\s*)?[:,]?\\s*(dass\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        private val ABOUT_ME = Regex(
            "\\b(ich heiße|mein name ist|ich wohne|ich lebe in|ich komme aus|ich arbeite|ich bin von beruf|mein beruf|ich bin \\d+ jahre|" +
                "ich habe (eine frau|einen mann|kinder|eine tochter|einen sohn)|meine frau|mein mann|meine tochter|mein sohn|ich mag|ich liebe|ich hasse|" +
                "ich fahre|mein auto|mein geburtstag|ich spreche)\\b[^.!?\\n]{2,120}",
            RegexOption.IGNORE_CASE,
        )

        /** What from a message should be remembered (explicit "Merk dir …" or a statement about the user). */
        fun factsIn(message: String): List<String> {
            EXPLICIT.find(message)?.let { return listOf(it.groupValues[6].trim()) }
            return ABOUT_ME.findAll(message).map { it.value.trim() }.filter { it.length >= 8 }.take(3).toList()
        }

        /** Block for the instruction of the on-device model. */
        fun promptBlock(facts: List<String>, recentTopics: List<String>): String = buildString {
            if (facts.isNotEmpty()) {
                append("Was du über den Nutzer weißt (aus früheren Gesprächen, nutze es natürlich, ohne es aufzuzählen):\n")
                facts.forEach { append("- ").append(it).append("\n") }
            }
            if (recentTopics.isNotEmpty()) {
                append("Themen seiner letzten Gespräche mit dir: ").append(recentTopics.joinToString("; ")).append("\n")
            }
        }
    }
}

package de.foldki.app.brief

/**
 * Finds open to-dos in the user's own chat messages, with RULES only (no extra AI run, saves battery):
 * "muss", "Frist", "bis Montag", "nicht vergessen", "erinner…" and similar. Pure Kotlin, unit-tested.
 */
object TaskExtractor {
    data class Message(val text: String, val time: Long)

    private const val DAY = 24L * 60 * 60 * 1000

    private val WEEKDAY = "montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag"
    private val PATTERNS = listOf(
        Regex("\\b(ich )?(muss|müsste|muß|müssen)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bfrist(en)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bbis (spätestens )?(zum |nächsten |nächste |kommenden |ende )?($WEEKDAY|morgen|übermorgen|heute|ende|\\d{1,2}\\.)", RegexOption.IGNORE_CASE),
        Regex("\\bspätestens\\b", RegexOption.IGNORE_CASE),
        Regex("\\bnicht vergessen\\b", RegexOption.IGNORE_CASE),
        Regex("\\berinner", RegexOption.IGNORE_CASE),
        Regex("\\b(to-?do|deadline|erledigen)\\b", RegexOption.IGNORE_CASE),
    )

    /** Questions to the assistant ("Was muss ich …?") are not tasks. */
    private val QUESTION_START = Regex("^(was|wie|wann|wo|warum|wieso|weshalb|welche[rsmn]?|wer|kannst|könntest|muss ich|müssen wir)\\b", RegexOption.IGNORE_CASE)

    /** Sentences that look like to-dos, newest first, without duplicates, at most [max]. */
    fun extract(messages: List<Message>, now: Long, max: Int = 20): List<String> {
        val out = LinkedHashMap<String, String>()
        messages.filter { now - it.time in 0..(7 * DAY) }.sortedByDescending { it.time }.forEach { m ->
            sentences(m.text).forEach { s ->
                if (isTask(s)) {
                    val key = normalize(s)
                    if (key.isNotEmpty() && key !in out) out[key] = s
                }
            }
        }
        return out.values.take(max)
    }

    fun isTask(sentence: String): Boolean {
        val s = sentence.trim()
        if (s.length < 8 || s.length > 160) return false
        if (s.endsWith("?") || QUESTION_START.containsMatchIn(s)) return false
        return PATTERNS.any { it.containsMatchIn(s) }
    }

    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim().trim('-', '•', '*', ' ') }.filter { it.isNotBlank() }

    fun normalize(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()
}

package de.foldki.app.data

/**
 * Corrections: the user says the previous answer was wrong ("falsch", "nein, richtig ist …"). Pure Kotlin (unit-tested).
 * The app then (1) adds one hint line to that turn and lets the model think, and (2) in the background writes one
 * memory entry of the form: Bei der Frage „…“ ist … falsch; richtig ist … (…).
 */
object Corrections {
    /** The only text the app adds to a correction turn (in the data block before the user's message). */
    const val HINT = "Hinweis: Die vorherige Antwort wurde als falsch markiert und darf nicht wiederholt werden."

    private val SIGNAL = Regex(
        "\\b(falsch|falsche antwort|stimmt (so )?nicht|nicht richtig|nicht korrekt|inkorrekt|" +
            "richtig (ist|wäre|lautet)|korrekt (ist|wäre|lautet)|die (richtige|korrekte) antwort|das war (leider )?(falsch|nicht richtig)|" +
            "(denk|denke|überleg|überlege|rechne|rechn) (bitte )?nochmal|nochmal nachdenken)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val QUESTION_START = Regex("^\\s*(was|wie|warum|wieso|weshalb|wo|wer|welche[rsmn]?|wann|woran|wozu)\\b", RegexOption.IGNORE_CASE)
    private val RIGHT_IS = Regex("(richtig|korrekt) (ist|wäre|lautet)|die (richtige|korrekte) antwort|\\blautet\\b|\\bes (sind|ist)\\b|\\bmüsste\\b", RegexOption.IGNORE_CASE)
    private val FILLER = setOf(
        "nein", "nochmal", "bitte", "leider", "das", "ist", "war", "sie", "die", "es", "so", "versuch", "versuche", "versuchs",
        "denk", "denke", "überleg", "überlege", "probier", "probiere", "noch", "mal", "antwort", "nicht", "stimmt", "ne", "also", "doch", "aber",
    )

    /** The message says the previous answer was wrong. Questions like "Was ist falsch an …?" do not count. */
    fun isSignal(message: String): Boolean {
        val m = message.trim()
        if (m.isEmpty() || m.length > 400) return false
        if (!SIGNAL.containsMatchIn(m)) return false
        if (QUESTION_START.containsMatchIn(m) && !RIGHT_IS.containsMatchIn(m)) return false
        return true
    }

    /** The correction message already contains the right answer (not just "falsch" / "versuch nochmal"). */
    fun givesAnswer(message: String): Boolean {
        val m = message.trim()
        if (RIGHT_IS.find(m)?.let { m.substring(it.range.last + 1).trim(' ', ':', ',', '.').length >= 1 } == true) return true
        if (m.any { it.isDigit() }) return true
        val rest = SIGNAL.replace(m, " ").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it.lowercase() !in FILLER }
        return rest.size >= 2
    }

    /** Shortens to at most [max] characters at a word boundary (single line). */
    fun clip(text: String, max: Int): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.length <= max) return t
        return t.take(max).substringBeforeLast(' ', t.take(max)).trimEnd(',', ';', ':') + "…"
    }

    /**
     * Internal task prompt (no thinking) for the background extraction. [newAnswer] is only given if the user did not
     * name the right answer himself (the model's corrected answer then supplies it).
     */
    fun extractionPrompt(question: String, wrong: String, userMessage: String, newAnswer: String): String = buildString {
        append("Der Nutzer hat eine Antwort als falsch korrigiert. Schreibe genau einen Satz in dieser Form:\n")
        append("Bei der Frage „<kurze Frage>“ ist <falsche Antwort> falsch; richtig ist <richtige Antwort> (<kurzer Grund>).\n")
        append("Nutze nur die Angaben unten und erfinde nichts. Geht die richtige Antwort daraus nicht hervor, schreibe nur: KEINE\n\n")
        append("Frage: ").append(clip(question, 200)).append("\n")
        append("Falsche Antwort: ").append(clip(wrong, 300)).append("\n")
        append("Korrektur des Nutzers: ").append(clip(userMessage, 300))
        if (newAnswer.isNotBlank()) append("\nNeue Antwort: ").append(clip(newAnswer, 300))
    }

    /** First line of the model output if it has the required form, else null (then nothing is saved). */
    fun parseExtraction(output: String): String? {
        val line = output.lines().map { it.trim().trim('*', '"') }.firstOrNull { it.isNotBlank() } ?: return null
        if (line.length < 25 || line.length > MemoryStore.MAX_TEXT) return null
        if (!line.startsWith("Bei der Frage") || !line.contains(" falsch") || !line.contains("richtig ist")) return null
        return line
    }
}

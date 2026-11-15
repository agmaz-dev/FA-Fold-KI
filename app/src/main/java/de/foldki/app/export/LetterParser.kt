package de.foldki.app.export

/**
 * Splits an AI answer into: short intro, the letter itself, and the short remark after it.
 * The AI is asked to put the letter between "---BRIEF---" and "---ENDE---"; if a small model
 * forgets the markers, a letter is still recognised by its salutation and closing formula.
 */
object LetterParser {
    data class Parts(val before: String, val letter: String?, val after: String)

    private val START = Regex("^[ \\t]*-{2,}\\s*BRIEF\\s*-{2,}[ \\t]*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val END = Regex("^[ \\t]*-{2,}\\s*ENDE\\s*-{2,}[ \\t]*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val CLOSING = Regex("^(Mit freundlichen Grüßen|Freundliche Grüße|Mit freundlichem Gruß|Hochachtungsvoll)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    fun parse(text: String): Parts {
        val s = START.find(text)
        if (s != null) {
            val e = END.find(text, s.range.last + 1)
            val letter = text.substring(s.range.last + 1, e?.range?.first ?: text.length).trim()
            return Parts(text.substring(0, s.range.first).trim(), letter.ifBlank { null }, e?.let { text.substring(it.range.last + 1).trim() } ?: "")
        }
        // Fallback without markers: salutation ... closing formula + name line
        val salutation = Regex("^Sehr geehrte", RegexOption.MULTILINE).find(text) ?: return Parts(text.trim(), null, "")
        val closing = CLOSING.find(text, salutation.range.first) ?: return Parts(text.trim(), null, "")
        // letter starts at the first block before the salutation that looks like an address/subject
        val beforeSal = text.substring(0, salutation.range.first)
        val paras = beforeSal.split(Regex("\\n\\s*\\n"))
        val introParas = paras.takeWhile { p -> p.trim().endsWith(":") || p.trim().length > 200 || p.isBlank() }
        val letterStart = introParas.sumOf { it.length + 2 }.coerceAtMost(salutation.range.first)
        // letter ends after the closing formula and the following name line
        val afterClosing = text.substring(closing.range.last + 1)
        val nameLines = afterClosing.lines().let { l ->
            var n = 0; var taken = 0
            for (line in l) { taken += line.length + 1; if (line.isNotBlank()) { n++; if (n >= 1) break } }
            taken
        }
        val letterEnd = (closing.range.last + 1 + nameLines).coerceAtMost(text.length)
        return Parts(
            text.substring(0, letterStart).trim(),
            text.substring(letterStart, letterEnd).trim(),
            text.substring(letterEnd).trim(),
        )
    }

    /** Text for the chat bubble: markers removed. */
    fun display(text: String): String =
        text.replace(START, "").replace(END, "").replace(Regex("\\n{3,}"), "\n\n").trim()

    /** What is read aloud in voice mode: the short remarks, not the whole letter. */
    fun spoken(text: String): String {
        val p = parse(text)
        if (p.letter == null) return text
        return listOf(p.before, p.after).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Ich habe dir das Schreiben in den Chat gelegt, auch als PDF." }
    }
}

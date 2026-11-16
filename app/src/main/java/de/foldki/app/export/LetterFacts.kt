package de.foldki.app.export

/**
 * Key facts of a received letter (date, sender authority, case number …), so that a reply uses
 * them automatically. Same approach as FA-PDF (ReplyPanel): the AI reads the letter once in a fixed
 * line format, the app additionally finds the date with a regex, and leftover placeholders in the
 * reply are filled in or removed.
 */
data class LetterFacts(
    val summary: String = "",
    val letterDate: String = "",
    val deadline: String = "",
    val amount: String = "",
    val sender: String = "",      // sender of the received letter = recipient of the reply
    val reference: String = "",   // Aktenzeichen / Kunden-Nr. …
    val contact: String = "",
) {
    /** Facts as text for the AI that writes the reply. */
    fun forPrompt(): String = buildString {
        if (letterDate.isNotBlank()) append("Datum des erhaltenen Schreibens: $letterDate.\n")
        if (reference.isNotBlank()) append("Bezug/Aktenzeichen: $reference.\n")
        if (sender.isNotBlank()) append("Absender des Schreibens (= Empfänger der Antwort): ${sender.replace("\n", ", ")}.\n")
        if (contact.isNotBlank()) append("Ansprechpartner: $contact.\n")
        if (deadline.isNotBlank()) append("Frist: $deadline.\n")
        if (amount.isNotBlank()) append("Betrag: $amount.\n")
    }

    /** Fills leftover placeholders like „[Datum des Schreibens]“ or removes instruction brackets. */
    fun fixPlaceholders(t: String): String = t.replace(Regex("\\s?\\[([^\\]]{2,160})]")) { m ->
        val inner = m.groupValues[1]
        when {
            inner.contains("datum", true) && letterDate.isNotBlank() -> " $letterDate"
            (inner.contains("aktenzeichen", true) || inner.contains("nummer", true) || inner.contains("bezug", true)) && reference.isNotBlank() -> " $reference"
            inner.contains("betrag", true) && amount.isNotBlank() -> " $amount"
            inner.contains("frist", true) && deadline.isNotBlank() -> " $deadline"
            inner.contains("ansprechpartner", true) && contact.isNotBlank() -> " $contact"
            Regex("(?i)einfügen|falls|sonst|weglassen|bitte|angeben|unbekannt|xx|hier|ihr(en)? grund|sie können").containsMatchIn(inner) -> ""
            inner.length > 50 -> "" // long remarks are never letter text
            else -> m.value
        }
    }.replace(Regex("[ \\t]{2,}"), " ").replace(Regex(" ([,.])"), "$1")

    companion object {
        /** Prompt for reading a received letter (from FA-PDF, without suggestions). */
        const val ANALYZE = "Du liest ein Schreiben, das der Nutzer erhalten hat. Antworte auf Deutsch, OHNE Einleitung, " +
            "genau in diesen Zeilen (jede Angabe in einer Zeile, nichts erfinden, fehlt etwas: \"-\"):\n" +
            "KURZ: 2 Sätze – wer schreibt, worum geht es, was wird verlangt\n" +
            "BRIEFDATUM: Datum des Schreibens als TT.MM.JJJJ\n" +
            "FRIST: Frist oder Termin (in Worten)\n" +
            "BETRAG: wichtigster Betrag\n" +
            "ABSENDER: Postanschrift des Absenders, Teile mit | getrennt (Behörde/Firma | Straße Nr. | PLZ Ort)\n" +
            "BEZUG: Aktenzeichen/Kassenzeichen/Kunden-Nr. des Schreibens\n" +
            "ANSPRECHPARTNER: Name"

        private val DATE = "(\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4})"

        /** Date of the letter straight from its text: first next to „Datum“, else the first date. */
        fun findLetterDate(text: String): String {
            val near = Regex("(?i)datum[^\\d]{0,40}$DATE").find(text)?.groupValues?.get(1)
            val any = Regex(DATE).find(text)?.groupValues?.get(1)
            return (near ?: any).orEmpty().replace(" ", "")
        }

        /** Case number straight from the text (Aktenzeichen, Kassenzeichen, Az., Kunden-Nr. …). */
        fun findReference(text: String): String =
            Regex("(?i)(aktenzeichen|kassenzeichen|gesch\\.?\\s?-?zeichen|az\\.|unser zeichen|kunden-?nr\\.?|kundennummer|vorgangs-?nr\\.?|vorgangsnummer)\\s*[:.]?\\s*([A-Z0-9][A-Z0-9./ -]{3,30}[A-Z0-9])")
                .find(text)?.let { "${it.groupValues[1].trim().replaceFirstChar { c -> c.uppercase() }} ${it.groupValues[2].trim()}" }.orEmpty()

        /** Reads the AI analysis (line format) and completes it with the regex findings. */
        fun parse(analysis: String, docText: String): LetterFacts {
            val f = HashMap<String, String>()
            analysis.lines().forEach { line ->
                val m = Regex("^\\s*[-*•]?\\s*(KURZ|BRIEFDATUM|FRIST(?:DATUM)?|BETR(?:AG|ÄGE|AEGE)|ABSENDER|BEZUG|ANSPRECHPARTNER)\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE).find(line)
                if (m != null) {
                    val key = m.groupValues[1].uppercase().let { k -> when { k.startsWith("FRIST") -> "FRIST"; k.startsWith("BETR") -> "BETRAG"; else -> k } }
                    val v = m.groupValues[2].trim().trim('"').replace("*", "")
                    if (key !in f && v.isNotBlank()) f[key] = v
                }
            }
            fun clean(v: String?) = v?.trim()?.takeUnless { it.isBlank() || it == "-" || it.equals("leer", true) || it.startsWith("keine", true) || it.startsWith("nicht", true) }.orEmpty()
            val aiDate = Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}").find(f["BRIEFDATUM"].orEmpty())?.value.orEmpty()
            return LetterFacts(
                summary = clean(f["KURZ"]),
                letterDate = aiDate.ifBlank { findLetterDate(docText) },
                deadline = clean(f["FRIST"]),
                amount = clean(f["BETRAG"]),
                sender = clean(f["ABSENDER"]).replace(" | ", "\n").replace("|", "\n").replace(";", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"),
                reference = clean(f["BEZUG"]).ifBlank { findReference(docText) },
                contact = clean(f["ANSPRECHPARTNER"]),
            )
        }
    }
}

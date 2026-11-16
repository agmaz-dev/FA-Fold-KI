package de.foldki.app.export

/**
 * Reading a received letter like FA-PDF (ReplyPanel): the AI reads the whole letter once and answers in a
 * fixed line format; the app parses it, fills gaps of [LetterMeta] with it, repairs leftover placeholders in
 * the reply and offers short answer suggestions. Pure Kotlin, unit-tested on the JVM.
 */
object LetterAnalysis {
    data class Result(
        val summary: String = "",
        val date: String = "",
        val deadline: String = "",
        val deadlineDate: String = "",
        val amount: String = "",
        val sender: String = "",      // sender of the received letter = recipient of the reply (multi-line)
        val email: String = "",
        val reference: String = "",
        val contact: String = "",
        val note: String = "",
        val suggestions: List<String> = emptyList(),
    )

    /** Prompt from FA-PDF (ReplyViewModel.ANALYZE). */
    const val ANALYZE = "Du liest ein Schreiben, das der Nutzer erhalten hat. Antworte auf Deutsch, OHNE Einleitung, " +
        "genau in diesen Zeilen (jede Angabe in einer Zeile, nichts erfinden, fehlt etwas: \"-\"):\n" +
        "KURZ: 2 Sätze – wer schreibt, worum geht es, was wird verlangt\n" +
        "BRIEFDATUM: Datum des Schreibens als TT.MM.JJJJ\n" +
        "NOTIZ: handschriftliche Notiz auf dem Brief (z. B. „Bezahlt 21.10.26“), sonst -\n" +
        "FRIST: Frist oder Termin (in Worten)\n" +
        "FRISTDATUM: letzter Tag der Frist als TT.MM.JJJJ, sonst -\n" +
        "BETRAG: wichtigster Betrag\n" +
        "ABSENDER: Postanschrift des Absenders, Teile mit | getrennt (Firma | Straße Nr. | PLZ Ort)\n" +
        "EMAIL: E-Mail des Ansprechpartners\n" +
        "BEZUG: Aktenzeichen/Kunden-/Betriebsnummer und Datum des Schreibens\n" +
        "ANSPRECHPARTNER: Name\n" +
        "VORSCHLAG: kurze Antwortmöglichkeit aus Sicht des Empfängers (höchstens 10 Wörter)\n" +
        "VORSCHLAG: zweite Möglichkeit\n" +
        "VORSCHLAG: dritte Möglichkeit\n" +
        "Handschriftliche Notizen im Schreiben (z. B. „Bezahlt am …“) sind Wissen des Empfängers und gehören in die Vorschläge."

    val DEFAULT_SUGGESTIONS = listOf("Zustimmen und bestätigen", "Widersprechen", "Um Fristverlängerung bitten", "Rückfrage stellen")

    private val DATE = Regex("\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}")

    /** Reads the analysis: line format first, else single fields from (possibly broken) JSON. */
    fun parse(rawIn: String): Result {
        val raw = rawIn.replace("```json", "").replace("```", "").trim()
        val f = HashMap<String, String>()
        val vors = ArrayList<String>()
        raw.lines().forEach { line ->
            val m = Regex(
                "^\\s*[-*•]?\\s*\\**(KURZ|NOTIZ|BRIEFDATUM|FRISTDATUM|FRIST(?:EN)?|BETR(?:AG|ÄGE|AEGE)|ABSENDER|EMAIL|E-MAIL|BEZUG|ANSPRECHPARTNER|VORSCHLAG)\\**\\s*\\d*\\s*:\\s*(.*)$",
                RegexOption.IGNORE_CASE,
            ).find(line) ?: return@forEach
            val key = m.groupValues[1].uppercase().let { k ->
                when {
                    k == "FRISTDATUM" -> "FRISTDATUM"; k == "BRIEFDATUM" -> "BRIEFDATUM"; k.startsWith("FRIST") -> "FRIST"
                    k.startsWith("BETR") -> "BETRAG"; k.contains("MAIL") -> "EMAIL"; else -> k
                }
            }
            val v = m.groupValues[2].trim().trim('"', '*').trim()
            if (key == "VORSCHLAG") { if (v.isNotBlank() && v != "-") vors += v } else if (key !in f && v.isNotBlank()) f[key] = v
        }
        if (f.isEmpty()) {
            fun jsonField(name: String) = Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(raw)?.groupValues?.get(1)?.replace("\\n", "\n")?.trim()
            jsonField("kurzfassung")?.let { f["KURZ"] = it }
            jsonField("fristen")?.let { f["FRIST"] = it }
            jsonField("betraege")?.let { f["BETRAG"] = it }
            jsonField("absender")?.let { f["ABSENDER"] = it }
            jsonField("email")?.let { f["EMAIL"] = it }
            jsonField("bezug")?.let { f["BEZUG"] = it }
            jsonField("ansprechpartner")?.let { f["ANSPRECHPARTNER"] = it }
        }
        fun clean(v: String?) = v?.trim()?.takeUnless {
            it.isBlank() || it == "-" || it.equals("leer", true) || it.startsWith("keine", true) || it.startsWith("nicht ", true) || it.equals("unbekannt", true)
        }.orEmpty()
        val sender = clean(f["ABSENDER"]).replace("\\n", "\n").replace(" | ", "\n").replace("|", "\n").replace(";", "\n")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return Result(
            summary = clean(f["KURZ"]),
            date = DATE.find(f["BRIEFDATUM"].orEmpty())?.value?.replace(" ", "").orEmpty(),
            deadline = clean(f["FRIST"]),
            deadlineDate = DATE.find(f["FRISTDATUM"].orEmpty())?.value?.replace(" ", "").orEmpty(),
            amount = clean(f["BETRAG"]),
            sender = sender,
            email = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+").find(clean(f["EMAIL"]).replace(" de", ".de").replace(" com", ".com"))?.value.orEmpty(),
            reference = clean(f["BEZUG"]),
            contact = clean(f["ANSPRECHPARTNER"]),
            note = clean(f["NOTIZ"]),
            suggestions = vors.map { it.trim().trim('"', ',') }.filter { it.isNotEmpty() }.distinct().take(5),
        )
    }

    /** Leftover placeholders like „[Datum des Schreibens einfügen …]“: fill from the facts or remove (FA-PDF). */
    fun fixPlaceholders(t: String, letterDate: String?, reference: String?, amount: String?, deadline: String?, contact: String?): String =
        // "vom [Datum …]" without a known date: drop the whole "vom …" instead of leaving a gap
        t.replace(Regex("\\s+vom\\s+\\[[^\\]\\n]*datum[^\\]\\n]*]", RegexOption.IGNORE_CASE)) {
            if (!letterDate.isNullOrBlank()) " vom $letterDate" else ""
        }.replace(Regex("[ \\t]?\\[([^\\]\\n]{2,160})]")) { m ->
            val inner = m.groupValues[1]
            fun has(v: String?) = !v.isNullOrBlank()
            when {
                inner.contains("datum", true) && !inner.contains("frist", true) && has(letterDate) -> " $letterDate"
                (inner.contains("aktenzeichen", true) || inner.contains("zeichen", true) || inner.contains("nummer", true)) && has(reference) -> " $reference"
                inner.contains("betrag", true) && has(amount) -> " $amount"
                inner.contains("frist", true) && has(deadline) -> " $deadline"
                inner.contains("ansprechpartner", true) && has(contact) -> " $contact"
                Regex("(?i)einfügen|falls|sonst|weglassen|bitte|angeben|unbekannt|xx|hier können|ihr(en)? grund").containsMatchIn(inner) -> ""
                else -> "" // no placeholders in a finished letter: unknown = left out

            }
        }.replace(Regex("[ \\t]{2,}"), " ").replace(Regex(" ([,.])"), "$1")

    /** Text cut off mid-sentence (model limit): shorten to the last full sentence and add the closing (FA-PDF). */
    fun completeEnding(t: String): String {
        var b = t.trimEnd()
        if (b.isEmpty()) return b
        val closing = Regex("(?im)^(mit freundlichen grüßen|freundliche grüße|mit freundlichem gruß|hochachtungsvoll|viele grüße|beste grüße)")
        if (closing.containsMatchIn(b)) return b
        if (b.last() !in ".!?)") {
            val cut = b.lastIndexOfAny(charArrayOf('.', '!', '?'))
            if (cut > b.length / 2) b = b.substring(0, cut + 1)
        }
        return "$b\n\nMit freundlichen Grüßen"
    }

    private val SALUTATION = Regex("^\\s*(Sehr geehrte|Guten Tag|Hallo|Liebe|Lieber)", RegexOption.IGNORE_CASE)
    private val DATE_ONLY = Regex("^\\s*(den\\s+)?\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}\\s*$")
    private val PLACE_DATE = Regex("^\\s*[\\p{L} .\\-/()]{2,40},\\s*(den\\s+)?\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}\\s*$")

    /**
     * Repairs the head of a letter written by a small model: a salutation above the subject is removed,
     * duplicate date lines are reduced to one, and a missing sender block / recipient address is added.
     */
    fun tidy(doc: String, sender: String?, recipient: String?): String {
        val lines = doc.lines().toMutableList()
        val subj = lines.indexOfFirst { it.trim().startsWith("Betreff", true) }
        if (subj > 0 && lines.drop(subj + 1).any { SALUTATION.containsMatchIn(it) }) {
            // salutation(s) wrongly above the subject
            for (i in subj - 1 downTo 0) if (SALUTATION.containsMatchIn(lines[i])) lines.removeAt(i)
        }
        val head = lines.indexOfFirst { it.trim().startsWith("Betreff", true) || SALUTATION.containsMatchIn(it) }.let { if (it < 0) lines.size else it }
        val dateIdx = (0 until head).filter { DATE_ONLY.matches(lines[it]) || PLACE_DATE.matches(lines[it]) }
        if (dateIdx.size > 1) {
            val keep = dateIdx.firstOrNull { PLACE_DATE.matches(lines[it]) } ?: dateIdx.first()
            dateIdx.filter { it != keep }.sortedDescending().forEach { lines.removeAt(it) }
        }
        var text = lines.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        val headText = text.substringBefore("Betreff")
        fun firstLine(b: String?) = b?.lines()?.firstOrNull { it.isNotBlank() }?.trim()
        val blocks = mutableListOf<String>()
        firstLine(sender)?.let { if (!headText.contains(it, true)) blocks += sender!!.trim() }
        firstLine(recipient)?.let { if (!text.contains(it, true)) blocks += recipient!!.trim() }
        if (blocks.isNotEmpty()) text = blocks.joinToString("\n\n") + "\n\n" + text
        return text
    }

    /** Key to remember the sender profile used for a recipient (first line, letters/digits only). */
    fun recipientKey(r: String?): String? =
        r?.lines()?.firstOrNull { it.isNotBlank() }?.lowercase()?.replace(Regex("[^a-zäöüß0-9]"), "")?.take(40)?.takeIf { it.length >= 3 }
}

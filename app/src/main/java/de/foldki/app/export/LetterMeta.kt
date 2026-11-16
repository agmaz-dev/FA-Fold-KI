package de.foldki.app.export

/**
 * Reads the key data of an attached ORIGINAL letter from the text of its first page:
 * the letter's own date, its sender (= recipient of the reply), reference number and subject.
 * Only the text of the letter counts; PDF metadata and file names are never used.
 * Pure Kotlin (no Android classes), unit-tested on the plain JVM.
 */
object LetterMeta {
    data class Meta(
        /** Date of the original letter, DD.MM.YYYY, or null. */
        val date: String? = null,
        /** Sender of the original = recipient of the reply (name/authority + address, multi-line), or null. */
        val recipient: String? = null,
        /** e.g. "Aktenzeichen: 123/45" */
        val refNo: String? = null,
        val subject: String? = null,
    )

    private val MONTHS = mapOf(
        "januar" to 1, "jan" to 1, "jänner" to 1, "februar" to 2, "feb" to 2, "märz" to 3, "maerz" to 3, "mär" to 3, "mrz" to 3,
        "april" to 4, "apr" to 4, "mai" to 5, "juni" to 6, "jun" to 6, "juli" to 7, "jul" to 7, "august" to 8, "aug" to 8,
        "september" to 9, "sep" to 9, "sept" to 9, "oktober" to 10, "okt" to 10, "november" to 11, "nov" to 11, "dezember" to 12, "dez" to 12,
    )
    private val NUMERIC = Regex("(?<![\\d.])(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4}|\\d{2})(?![\\d])")
    private val WRITTEN = Regex(
        "(?<![\\d.])(\\d{1,2})\\.\\s*(" + MONTHS.keys.sortedByDescending { it.length }.joinToString("|") + ")\\.?\\s+(\\d{4})(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    private val ISO = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)")

    /** Dates that are NOT the letter's date: deadlines, birthdays, references to other letters, periods. */
    private val NEGATIVE = Regex(
        "(frist|bis\\s+(zum|spätestens)?|spätestens|innerhalb|fällig|zahlbar|zahlungsziel|geb\\.|geboren|geburt|gültig|ab dem|seit|zeitraum|" +
            "vom\\s*$|vom\\s+\\d|bescheid|schreiben vom|antrag vom|vertrag vom|rechnung vom|leistungszeitraum|termin|uhr|eingang|eingegangen)",
        RegexOption.IGNORE_CASE,
    )
    private val LABEL = Regex("(datum|date)\\s*:?\\s*$", RegexOption.IGNORE_CASE)
    private val PLACE_DEN = Regex("^[A-ZÄÖÜ][\\p{L} .\\-/()]{1,40},\\s*(den\\s+)?$")

    private data class Cand(val date: String, val score: Int, val pos: Int)

    fun extract(text: String, ownNames: List<String> = emptyList()): Meta {
        val lines = text.lines().map { it.trimEnd() }
        return Meta(
            date = findDate(lines),
            recipient = findRecipient(lines, ownNames),
            refNo = findRef(lines),
            subject = findSubject(lines),
        )
    }

    fun findDate(text: String): String? = findDate(text.lines())

    private fun findDate(lines: List<String>): String? {
        val cands = mutableListOf<Cand>()
        val top = lines.size.coerceAtLeast(1)
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            fun add(start: Int, end: Int, d: String?) {
                if (d == null) return
                val before = line.substring(0, start)
                val after = line.substring(end)
                val near = before.takeLast(45)
                if (NEGATIVE.containsMatchIn(near)) return // deadline, birthday, "Bescheid vom …"
                var score = 10
                if (LABEL.containsMatchIn(before) || (i > 0 && LABEL.containsMatchIn(lines[i - 1].trim()))) score += 60
                if (PLACE_DEN.matches(before.trimStart())) score += 50
                if (before.isBlank() && after.isBlank()) score += 25 // date alone on its line
                if (raw.contains(Regex("\\S\\s{3,}\\S")) && after.isBlank()) score += 15 // right column of the letterhead
                if (i < top * 0.35 || i < 12) score += 15
                if (Regex("(unser|ihr)e?\\s+(zeichen|nachricht|schreiben)", RegexOption.IGNORE_CASE).containsMatchIn(before) && !LABEL.containsMatchIn(before)) score -= 5
                cands += Cand(d, score, i * 1000 + start)
            }
            NUMERIC.findAll(line).forEach { m -> add(m.range.first, m.range.last + 1, norm(m.groupValues[1], m.groupValues[2], m.groupValues[3])) }
            WRITTEN.findAll(line).forEach { m ->
                add(m.range.first, m.range.last + 1, MONTHS[m.groupValues[2].lowercase()]?.let { norm(m.groupValues[1], it.toString(), m.groupValues[3]) })
            }
            ISO.findAll(line).forEach { m -> add(m.range.first, m.range.last + 1, norm(m.groupValues[3], m.groupValues[2], m.groupValues[1])) }
        }
        return cands.sortedWith(compareByDescending<Cand> { it.score }.thenBy { it.pos }).firstOrNull()?.date
    }

    private fun norm(d: String, m: String, y: String): String? {
        val day = d.toIntOrNull() ?: return null
        val month = m.toIntOrNull() ?: return null
        var year = y.toIntOrNull() ?: return null
        if (y.length == 2) year += 2000
        if (day !in 1..31 || month !in 1..12 || year !in 1990..2100) return null
        return "%02d.%02d.%04d".format(day, month, year)
    }

    // ---- recipient of the reply = sender of the original ----

    private val ZIP_CITY = Regex("(?<!\\d)(D-\\s?)?\\d{5}\\s+[A-ZÄÖÜ][\\p{L}.\\- ]+")
    private val STREET = Regex(
        "([\\p{L}.\\- ]+(straße|strasse|str\\.|weg|platz|allee|gasse|ring|damm|ufer|markt|chaussee|steig|pfad|hof)\\s*\\d+\\s*[a-zA-Z]?)|" +
            "(postfach\\s+\\d[\\d ]*)|([A-ZÄÖÜ][\\p{L}.\\-]+(\\s[A-ZÄÖÜ]?[\\p{L}.\\-]+)?\\s\\d{1,4}\\s?[a-zA-Z]?)$",
        RegexOption.IGNORE_CASE,
    )
    private val ORG = Regex(
        "\\b(gmbh|ag|kg|ohg|e\\.\\s?v\\.|ug|se|mbh|amt|ämter|behörde|stadt|gemeinde|landkreis|landratsamt|kreisverwaltung|bezirksamt|finanzamt|jobcenter|" +
            "agentur für arbeit|familienkasse|krankenkasse|aok|barmer|dak|ikk|bkk|techniker|versicherung|bank|sparkasse|volksbank|gericht|" +
            "rentenversicherung|inkasso|verwaltung|hausverwaltung|stadtwerke|ministerium|polizei|staatsanwaltschaft|kanzlei|rechtsanwält|notar|" +
            "universität|schule|bürgeramt|ordnungsamt|sozialamt|wohngeldstelle|bundesagentur|beitragsservice|zentrale|verband|genossenschaft)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ADDRESSEE = Regex("^(herrn?|frau|an\\s|an$|familie|eheleute)\\b", RegexOption.IGNORE_CASE)
    private val FOOTER = Regex("(sitz|geschäftsführ|amtsgericht|registergericht|hrb|ust-?id|iban|bic|telefon|tel\\.|fax|e-mail|www\\.|http|öffnungszeiten|sprechzeiten|seite \\d)", RegexOption.IGNORE_CASE)
    private val SEPARATORS = Regex("\\s*[·•|]\\s*|\\s+[-–]\\s+")

    private fun findRecipient(lines: List<String>, ownNames: List<String>): String? {
        val head = lines.take(30)
        val own = ownNames.map { it.trim().lowercase() }.filter { it.length >= 3 }
        fun isOwn(s: String) = own.any { s.lowercase().contains(it) }

        // 1) The small sender line above the address window: "Stadt Muster · Bürgeramt · Marktplatz 1 · 12345 Muster"
        head.forEach { raw ->
            val parts = raw.trim().split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2 && parts.any { ZIP_CITY.containsMatchIn(it) } && !isOwn(raw) && !FOOTER.containsMatchIn(raw)) {
                val zipIdx = parts.indexOfFirst { ZIP_CITY.containsMatchIn(it) }
                val parts2 = if (parts.size == 2 && parts[0].contains(",")) parts[0].split(",").map { it.trim() } + parts[1] else parts
                val z = parts2.indexOfFirst { ZIP_CITY.containsMatchIn(it) }.takeIf { it >= 0 } ?: zipIdx
                if (z >= 1) return parts2.take(z + 1).joinToString("\n")
            }
        }
        // Comma-separated sender line: "Jobcenter Musterstadt, Hauptstraße 5, 12345 Musterstadt"
        head.forEach { raw ->
            val parts = raw.trim().split(Regex("\\s*,\\s*")).filter { it.isNotBlank() }
            if (parts.size >= 3 && ZIP_CITY.matches(parts.last().trim()) && STREET.containsMatchIn(parts[parts.size - 2]) && !isOwn(raw)) {
                return parts.joinToString("\n")
            }
        }

        // 2) Address blocks in the letterhead: name lines, street, PLZ city – but not the addressee (the user).
        val blocks = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        head.forEach { l -> if (l.isBlank()) { if (cur.isNotEmpty()) blocks += cur; cur = mutableListOf() } else cur += l.trim() }
        if (cur.isNotEmpty()) blocks += cur
        var best: List<String>? = null
        var bestScore = Int.MIN_VALUE
        blocks.forEachIndexed { bi, b ->
            val zip = b.indexOfFirst { ZIP_CITY.matches(it) || (ZIP_CITY.containsMatchIn(it) && it.length < 45) }
            if (zip < 0) return@forEachIndexed
            if (b.any { ADDRESSEE.containsMatchIn(it) } || b.any { isOwn(it) }) return@forEachIndexed
            if (b.any { FOOTER.containsMatchIn(it) && !ZIP_CITY.containsMatchIn(it) && it.length > 40 }) return@forEachIndexed
            val start = (zip - 4).coerceAtLeast(0)
            val chosen = b.subList(start, zip + 1).filterNot { FOOTER.containsMatchIn(it) || NUMERIC.containsMatchIn(it) }
            if (chosen.size < 2) return@forEachIndexed
            var score = 20 - bi * 3
            if (chosen.any { ORG.containsMatchIn(it) }) score += 30
            if (chosen.any { STREET.containsMatchIn(it) }) score += 10
            if (score > bestScore) { bestScore = score; best = chosen }
        }
        best?.let { return it.joinToString("\n") }

        // 3) Only a name: an authority/company in the first lines.
        return head.take(8).map { it.trim() }.firstOrNull { it.isNotEmpty() && ORG.containsMatchIn(it) && !isOwn(it) && !FOOTER.containsMatchIn(it) && it.length <= 60 }
    }

    /**
     * The read address may start with the street (name line lost). Then the name is put in front:
     * from the analysis ([nameHint], first line) or from an authority/company line at the top of the letter.
     */
    fun completeRecipient(recipient: String?, letterText: String, nameHint: String?): String? {
        val r = recipient?.trim()?.takeIf { it.isNotEmpty() } ?: return nameHint?.trim()?.ifBlank { null }
        val first = r.lines().first().trim()
        val isAddressLine = STREET.containsMatchIn(first) || ZIP_CITY.containsMatchIn(first)
        if (!isAddressLine) return r
        fun ok(s: String) = s.isNotBlank() && !STREET.containsMatchIn(s) && !ZIP_CITY.containsMatchIn(s) && !FOOTER.containsMatchIn(s) && s.length <= 60
        val name = nameHint?.lines()?.firstOrNull()?.trim()?.takeIf { ok(it) }
            ?: letterText.lines().take(30).map { it.trim() }.firstOrNull { ok(it) && ORG.containsMatchIn(it) }
        return if (name != null) "$name\n$r" else r
    }

    private val REF = Regex(
        "^(.*?\\b)?(aktenzeichen|az\\.|geschäftszeichen|unser zeichen|ihr zeichen|kassenzeichen|vorgangsnummer|kundennummer|vertragsnummer|" +
            "steuernummer|versicherungsnummer|bg-nummer|nummer der bedarfsgemeinschaft|rechnungsnummer|mitgliedsnummer|personalnummer)\\s*[:.]?\\s*([A-Za-z0-9][A-Za-z0-9 ./\\-]{1,40})",
        RegexOption.IGNORE_CASE,
    )

    private fun findRef(lines: List<String>): String? {
        lines.take(40).forEach { l ->
            val m = REF.find(l.trim()) ?: return@forEach
            val value = m.groupValues[3].trim().split(Regex("\\s{2,}")).first().trim().trimEnd('.', ',')
            if (value.any { it.isDigit() }) return m.groupValues[2].trim().replaceFirstChar { it.uppercase() } + ": " + value
        }
        return null
    }

    private fun findSubject(lines: List<String>): String? {
        lines.take(45).forEach { l ->
            val t = l.trim()
            Regex("^(betreff|betr\\.)\\s*:?\\s*(.+)$", RegexOption.IGNORE_CASE).find(t)?.let { return it.groupValues[2].trim().take(120) }
        }
        lines.take(45).forEach { l ->
            val t = l.trim()
            if (t.length in 8..120 && Regex("^(bescheid|mahnung|rechnung|kündigung|anhörung|zahlungserinnerung|änderungsbescheid|aufhebungsbescheid|" +
                    "erstattungsbescheid|ablehnung|ihr antrag|ihre anfrage|widerspruchsbescheid|beitragsbescheid|steuerbescheid)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
        }
        return null
    }
}

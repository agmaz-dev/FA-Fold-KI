package de.foldki.app.export

import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a clean DIN 5008 letter from what the AI wrote.
 * The AI only delivers recipient, subject and the body; sender, place/date and signature
 * come from the app, so they are always correct and never duplicated.
 */
object LetterBuilder {
    data class Letter(
        val sender: List<String>,      // name, street, city, phone, mail
        val recipient: List<String>,
        val placeDate: String,
        val subject: String,
        val body: String,              // from salutation to closing formula
        val signature: String,
    ) {
        /** Plain text version for the chat bubble and the clipboard. */
        fun asText(): String = buildString {
            if (sender.isNotEmpty()) append(sender.joinToString("\n")).append("\n\n")
            append(recipient.joinToString("\n").ifBlank { "[Empfänger]" }).append("\n\n")
            append(placeDate).append("\n\n")
            append(subject).append("\n\n")
            append(body).append("\n\n")
            append(signature)
        }
    }

    private val CLOSING = Regex("^(Mit freundlichen Grüßen|Freundliche Grüße|Mit freundlichem Gruß|Hochachtungsvoll|Viele Grüße)", RegexOption.IGNORE_CASE)
    private val SALUTATION = Regex("^(Sehr geehrte|Guten Tag|Hallo|Liebe|Lieber)", RegexOption.IGNORE_CASE)
    private val DATE_LINE = Regex("^([\\p{L} .-]+,\\s*)?(den\\s*)?\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}$")
    // Long remarks in brackets are instructions to the user, not letter text ("[Hier können Sie …]").
    private val META = Regex("\\[[^\\]]{50,}\\]")

    fun build(aiLetter: String, senderSetting: String, facts: LetterFacts = LetterFacts()): Letter {
        val sender = senderSetting.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val senderSet = sender.map { it.lowercase() }.toSet()
        val lines = facts.fixPlaceholders(PdfExporter.clean(aiLetter)).replace(META, "").lines().map { it.trimEnd() }

        // Recipient: block after "Empfänger:" (or "An:") up to the next empty line / Betreff
        val recipient = mutableListOf<String>()
        var subject = ""
        var subjectIdx = -1
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            when {
                t.startsWith("Empfänger", true) || t.equals("An:", true) || t.startsWith("An:", true) -> {
                    t.substringAfter(":", "").trim().takeIf { it.isNotEmpty() }?.let { recipient += it }
                    i++
                    while (i < lines.size && lines[i].isNotBlank() && !lines[i].trim().startsWith("Betreff", true)) {
                        recipient += lines[i].trim(); i++
                    }
                    continue
                }
                t.startsWith("Betreff", true) -> {
                    subjectIdx = i
                    subject = t.substringAfter(":").trim()
                    // subject may continue on the next line
                    if (i + 1 < lines.size && lines[i + 1].isNotBlank() && !SALUTATION.containsMatchIn(lines[i + 1].trim())) {
                        subject += " " + lines[i + 1].trim(); i++
                    }
                }
            }
            if (SALUTATION.containsMatchIn(t) && subject.isNotEmpty()) break
            i++
        }

        // Body: from the salutation after the subject (a stray one at the top is ignored) to the closing formula
        val salIdx = lines.indices.firstOrNull { it > subjectIdx && SALUTATION.containsMatchIn(lines[it].trim()) }
            ?: lines.indices.firstOrNull { SALUTATION.containsMatchIn(lines[it].trim()) } ?: -1
        val closeIdx = lines.indexOfLast { CLOSING.containsMatchIn(it.trim()) }
        val bodyLines = when {
            salIdx >= 0 && closeIdx > salIdx -> lines.subList(salIdx, closeIdx + 1)
            salIdx >= 0 -> lines.subList(salIdx, lines.size)
            else -> lines.filterNot { it.trim().startsWith("Betreff", true) || it.trim().startsWith("Empfänger", true) }
        }
        val body = bodyLines
            .filterNot { l -> val t = l.trim(); t.lowercase() in senderSet || DATE_LINE.matches(t) }
            .joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .let { if (CLOSING.containsMatchIn(it.lines().last().trim())) it else "$it\n\nMit freundlichen Grüßen" }

        val city = sender.firstOrNull { Regex("^\\d{5}\\s+\\S").containsMatchIn(it) }?.substringAfter(" ")?.trim()
        val date = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())
        // Recipient: what the AI wrote, unless it is empty or a placeholder – then the sender of the received letter
        val aiRecipient = recipient.filterNot { it.lowercase() in senderSet || it.isBlank() }
        val finalRecipient = if (aiRecipient.isEmpty() || aiRecipient.any { it.contains("[") }) {
            facts.sender.lines().filter { it.isNotBlank() }.ifEmpty { aiRecipient }
        } else aiRecipient
        if (subject.isBlank() || subject.contains("[")) {
            subject = listOfNotNull(
                "Ihr Schreiben" + (if (facts.letterDate.isNotBlank()) " vom ${facts.letterDate}" else ""),
                facts.reference.takeIf { it.isNotBlank() },
            ).joinToString(" – ").takeIf { facts.letterDate.isNotBlank() || facts.reference.isNotBlank() } ?: subject
        }
        return Letter(
            sender = sender,
            recipient = finalRecipient,
            placeDate = if (city != null) "$city, $date" else date,
            subject = subject.ifBlank { "[Betreff]" },
            body = body,
            signature = sender.firstOrNull() ?: "[Dein Name – bitte in den Einstellungen unter Briefe eintragen]",
        )
    }

    // ------------------------------------------------------------------ PDF (DIN 5008, Form B)

    private const val MM = 72f / 25.4f
    private val PAGE_W = 595; private val PAGE_H = 842
    private val LEFT = 25 * MM; private val RIGHT = 20 * MM

    fun writePdf(l: Letter, out: OutputStream) {
        val normal = TextPaint().apply { isAntiAlias = true; textSize = 11f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL); color = android.graphics.Color.BLACK }
        val bold = TextPaint(normal).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val small = TextPaint(normal).apply { textSize = 7.5f; color = android.graphics.Color.DKGRAY }
        val width = (PAGE_W - LEFT - RIGHT).toInt()
        fun layout(text: String, p: TextPaint, w: Int = width, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
            StaticLayout.Builder.obtain(text, 0, text.length, p, w).setAlignment(align).setLineSpacing(1.5f, 1.1f).build()

        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        var c = page.canvas

        // Sender block top right
        if (l.sender.isNotEmpty()) {
            val sl = layout(l.sender.joinToString("\n"), normal, (75 * MM).toInt())
            c.save(); c.translate(PAGE_W - RIGHT - 75 * MM, 20 * MM); sl.draw(c); c.restore()
            // return address line above the window
            val ret = l.sender.take(3).joinToString(" · ")
            c.drawText(ret, LEFT, 48 * MM, small)
        }
        // Recipient (address window, 50 mm from top)
        val rl = layout(l.recipient.joinToString("\n").ifBlank { "[Empfänger]" }, normal, (85 * MM).toInt())
        c.save(); c.translate(LEFT, 52 * MM); rl.draw(c); c.restore()
        // Place, date right aligned
        val dl = layout(l.placeDate, normal, width, Layout.Alignment.ALIGN_OPPOSITE)
        c.save(); c.translate(LEFT, 98 * MM); dl.draw(c); c.restore()
        // Subject (bold)
        val subj = layout(l.subject, bold)
        c.save(); c.translate(LEFT, 108 * MM); subj.draw(c); c.restore()

        // Body + signature, flowing onto further pages
        val bodyText = l.body + "\n\n\n\n" + l.signature
        val bl = layout(bodyText, normal)
        var y = 108 * MM + subj.height + 8 * MM
        val bottom = PAGE_H - 25 * MM
        var line = 0
        while (line < bl.lineCount) {
            var end = line
            val startTop = bl.getLineTop(line)
            while (end < bl.lineCount && y + (bl.getLineBottom(end) - startTop) <= bottom) end++
            if (end == line) end = line + 1
            c.save()
            c.translate(LEFT, y - startTop)
            c.clipRect(0f, startTop.toFloat(), width.toFloat(), bl.getLineBottom(end - 1).toFloat())
            bl.draw(c)
            c.restore()
            line = end
            if (line < bl.lineCount) {
                c.drawText("Seite $pageNo", PAGE_W - RIGHT - 40, PAGE_H - 12 * MM, small)
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                c = page.canvas
                y = 25 * MM
            }
        }
        if (pageNo > 1) c.drawText("Seite $pageNo", PAGE_W - RIGHT - 40, PAGE_H - 12 * MM, small)
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}

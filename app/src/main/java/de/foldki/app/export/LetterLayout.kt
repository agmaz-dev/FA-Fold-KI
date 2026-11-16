package de.foldki.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import java.io.OutputStream

/**
 * Business letter as A4 PDF, same layout as FA-PDF (LetterPdfWriter, DIN 5008 Form B):
 * left the address field with return line, right on the same height the info block
 * (name, address, phone, e-mail, "Ihr Schreiben", "Ihr Zeichen", "Datum"); below subject, text, signature, name.
 */
object LetterLayout {
    data class Letter(
        val sender: String, val recipient: String, val placeDate: String, val subject: String,
        val body: String, val signerName: String, val theirDate: String = "", val reference: String = "",
    )

    private data class Info(val label: String, val value: String)

    private val DATE = Regex("\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}")
    private val THEIR_DATE = Regex("^\\s*Ihr Schreiben( vom)?\\s*:?\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val THEIR_REF = Regex("^\\s*Ihr Zeichen\\s*:?\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val CLOSING = Regex("^(Mit freundlichen Grüßen|Freundliche Grüße|Mit freundlichem Gruß|Hochachtungsvoll|Viele Grüße|Beste Grüße)", RegexOption.IGNORE_CASE)

    /**
     * Reads a letter text in the app's format (LetterAnalysis.assemble): sender ¶ recipient ¶ [refs +] place/date ¶
     * Betreff ¶ body … closing ¶ name. Returns null for other documents (they keep the plain layout).
     */
    fun parse(text: String): Letter? {
        val paras = text.trim().split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val s = paras.indexOfFirst { it.startsWith("Betreff", true) }
        if (s < 1) return null
        val dateIdx = s - 1
        val dateLines = paras[dateIdx].lines()
        if (dateLines.none { DATE.containsMatchIn(it) }) return null
        var theirDate = ""; var reference = ""; var placeDate = ""
        dateLines.forEach { l ->
            THEIR_DATE.find(l)?.let { theirDate = it.groupValues[2].trim(); return@forEach }
            THEIR_REF.find(l)?.let { reference = it.groupValues[1].trim(); return@forEach }
            if (DATE.containsMatchIn(l)) placeDate = l.trim()
        }
        val head = paras.subList(0, dateIdx)
        var bodyParas = paras.subList(s + 1, paras.size)
        var signer = ""
        if (bodyParas.size >= 2 && bodyParas.last().lines().size == 1 && CLOSING.containsMatchIn(bodyParas[bodyParas.size - 2].lines().last())) {
            signer = bodyParas.last(); bodyParas = bodyParas.dropLast(1)
        }
        // Only one block above the date: it is the own address if it starts with the signer's name, else the recipient.
        val onlyOwn = head.size == 1 && signer.isNotBlank() && head[0].lines().first().trim().equals(signer.trim(), true)
        val sender = if (head.size >= 2) head[0] else if (onlyOwn) head[0] else ""
        val recipient = if (head.size >= 2) head[1] else if (onlyOwn) "" else head.firstOrNull().orEmpty()
        return Letter(
            sender = sender, recipient = recipient, placeDate = placeDate,
            subject = paras[s].replace(Regex("^Betreff\\s*:?\\s*", RegexOption.IGNORE_CASE), "").trim(),
            body = bodyParas.joinToString("\n\n"), signerName = signer, theirDate = theirDate, reference = reference,
        )
    }

    private fun splitSender(sender: String): Triple<String, List<String>, List<Info>> {
        val lines = sender.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val name = lines.firstOrNull().orEmpty()
        val contact = ArrayList<Info>(); val postal = ArrayList<String>()
        lines.drop(1).forEach { l ->
            when {
                l.contains("@") -> contact += Info("E-Mail", l.substringAfter(":").trim())
                Regex("(?i)^(tel|telefon|mobil|handy|fon)\\b").containsMatchIn(l) || Regex("^\\+?[\\d /()-]{7,}$").matches(l) ->
                    contact += Info("Telefon", l.replace(Regex("(?i)^(tel(efon)?|mobil|handy|fon)\\.?:?\\s*"), ""))
                else -> postal += l
            }
        }
        return Triple(name, postal, contact)
    }

    private fun splitAddress(text: String): List<String> = text.lines().flatMap { l ->
        val m = Regex("^(.*\\S)\\s*[-,·|]?\\s+(\\d{5}\\s+\\S.*)$").find(l.trim())
        if (m != null && m.groupValues[1].any { it.isLetter() }) listOf(m.groupValues[1].trimEnd('-', ',', ' '), m.groupValues[2]) else listOf(l)
    }.map { it.trim() }.filter { it.isNotEmpty() }

    private fun dateOnly(placeDate: String) = DATE.find(placeDate)?.value ?: placeDate.substringAfterLast(",").trim()

    private const val MM = 2.8346457f
    private const val W = 595f
    private const val H = 842f

    fun write(letter: Letter, out: OutputStream, signature: Bitmap?) {
        val left = 25 * MM
        val right = W - 20 * MM
        val size = 11f
        val leading = size * 1.4f
        fun paint(sz: Float, bold: Boolean = false, color: Int = 0xFF000000.toInt()) = TextPaint().apply {
            isAntiAlias = true; textSize = sz; this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val regular = paint(size)
        val bold = paint(size, true)

        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNo).create())
        var c: Canvas = page.canvas
        // y = distance from the top of the page to the top of the text line (like FA-PDF's yTop)
        fun put(p: TextPaint, x: Float, yTop: Float, s: String) { if (s.isNotEmpty()) c.drawText(s, x, yTop + p.textSize * 0.8f, p) }
        fun newPage(): Float {
            doc.finishPage(page); pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNo).create())
            c = page.canvas
            return 20 * MM
        }
        fun wrap(text: String, p: TextPaint, maxW: Float): List<String> {
            val outL = ArrayList<String>(); var cur = ""
            text.split(' ').forEach { w ->
                val t = if (cur.isEmpty()) w else "$cur $w"
                if (p.measureText(t) <= maxW || cur.isEmpty()) cur = t else { outL += cur; cur = w }
            }
            if (cur.isNotEmpty()) outL += cur
            return outL
        }

        // DIN 5008 Form B: left the address field, right on the same height the info block
        val (name, postal, contact) = splitSender(letter.sender)
        val returnLine = (listOf(name) + postal).filter { it.isNotBlank() }.joinToString(" · ")
        val top = 45 * MM + 3 * MM
        if (returnLine.isNotBlank()) {
            val small = paint(7f, color = 0xFF555555.toInt())
            put(small, left, top, returnLine)
            val w = small.measureText(returnLine).coerceAtMost(85 * MM)
            c.drawLine(left, top + 8.5f, left + w, top + 8.5f, Paint().apply { color = 0xFF888888.toInt(); strokeWidth = 0.4f })
        }
        var ay = 45 * MM + 12 * MM
        splitAddress(letter.recipient).take(7).forEach { l -> put(regular, left, ay, l); ay += size * 1.3f }

        // Info block right (from 125 mm): name, address, contact, references, date
        val ix = 125 * MM
        var iy = top - 4f
        if (name.isNotBlank()) { put(paint(11f, true), ix, iy, name); iy += 11f * 1.35f }
        val p9 = paint(9f)
        postal.forEach { put(p9, ix, iy, it); iy += 9f * 1.35f }
        val labelW = 22 * MM
        val label = paint(8f, color = 0xFF666666.toInt())
        fun info(lbl: String, value: String) {
            if (value.isBlank()) return
            put(label, ix, iy, lbl)
            val avail = right - ix - labelW
            val fs = generateSequence(9f) { it - 0.5f }.takeWhile { it >= 6.5f }.firstOrNull { paint(it).measureText(value) <= avail } ?: 6.5f
            put(paint(fs), ix + labelW, iy, value)
            iy += 9f * 1.45f
        }
        if (contact.isNotEmpty()) iy += 6f
        contact.forEach { info(it.label, it.value) }
        iy += 8f
        info("Ihr Schreiben", letter.theirDate)
        info("Ihr Zeichen", LetterAnalysis.cleanRef(letter.reference).ifBlank { letter.reference }.take(40))
        info("Datum", dateOnly(letter.placeDate))

        // Subject below both blocks
        var y = maxOf(ay, iy, 98 * MM) + leading * 1.2f
        val maxW = right - left
        wrap(letter.subject.trim(), bold, maxW).forEach { put(bold, left, y, it); y += leading }
        y += leading

        // Body
        for (raw in letter.body.trim().split('\n')) {
            if (raw.isBlank()) { y += leading * 0.7f; continue }
            for (l in wrap(raw.trim(), regular, maxW)) {
                if (y + leading > H - 25 * MM) y = newPage()
                put(regular, left, y, l); y += leading
            }
        }

        // Signature and name
        if (signature != null) {
            val h = 16 * MM
            val w = (h * signature.width / signature.height.coerceAtLeast(1)).coerceAtMost(70 * MM)
            val hh = w * signature.height / signature.width.coerceAtLeast(1)
            if (y + hh + leading > H - 20 * MM) y = newPage()
            c.drawBitmap(signature, null, RectF(left, y + 2f, left + w, y + 2f + hh), Paint(Paint.FILTER_BITMAP_FLAG))
            y += hh + 4f
        } else y += leading * 2
        if (letter.signerName.isNotBlank()) {
            if (y + leading > H - 20 * MM) y = newPage()
            put(regular, left, y, letter.signerName.trim())
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}

package de.foldki.app.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AlignmentSpan
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/** Renders plain text (e.g. a letter) onto A4 pages using Android's built-in PdfDocument. */
object PdfExporter {
    // A4 in PostScript points (1/72 inch). Margins roughly follow DIN 5008.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val LEFT = 71   // 25 mm
    private const val RIGHT = 57  // 20 mm
    private const val TOP = 57
    private const val BOTTOM = 57

    /** Signature box: about 45 × 15 mm. */
    private const val SIG_W = 127.5f
    private const val SIG_H = 42.5f
    private const val SIG_LINES = 3

    /**
     * Writes [text] as A4 PDF. Letters: the date line is right-aligned (DIN 5008); with [signature] the image is
     * drawn between the closing formula and the name, and closing + signature + name are kept on one page.
     */
    fun write(text: String, out: OutputStream, signature: Bitmap? = null) {
        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = 11f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            color = android.graphics.Color.BLACK
        }
        val width = PAGE_W - LEFT - RIGHT

        // Room for the signature: exactly SIG_LINES empty lines between closing formula and name.
        var body = text
        var slot = DocumentParser.signatureSlot(text)
        if (slot != null && signature != null) {
            val lines = text.lines()
            val rebuilt = lines.subList(0, slot.closingLine + 1) + List(SIG_LINES) { "" } + lines.subList(slot.nameLine, lines.size)
            body = rebuilt.joinToString("\n")
            slot = DocumentParser.SignatureSlot(slot.closingLine, slot.closingLine + 1 + SIG_LINES)
        }
        fun offsetOfLine(t: String, idx: Int) = t.lines().take(idx).sumOf { it.length + 1 }

        // DIN 5008: the date line of a letter stands on the right.
        val styled = SpannableString(body)
        val dateIdx = DocumentParser.dateLineIndex(body)
        if (dateIdx >= 0) {
            val start = offsetOfLine(body, dateIdx)
            val end = (start + body.lines()[dateIdx].length).coerceAtMost(body.length)
            styled.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.1f)
            .build()

        // Layout lines of closing formula, first signature line and name (to keep them together).
        val closingL = slot?.let { layout.getLineForOffset(offsetOfLine(body, it.closingLine)) }
        val nameL = slot?.let { layout.getLineForOffset(offsetOfLine(body, it.nameLine).coerceAtMost(body.length)) }
        val sigL = if (signature != null && slot != null) layout.getLineForOffset(offsetOfLine(body, slot.closingLine + 1)) else null

        val doc = PdfDocument()
        val usable = PAGE_H - TOP - BOTTOM
        var line = 0
        var pageNo = 1
        while (line < layout.lineCount) {
            val startY = layout.getLineTop(line)
            var end = line
            while (end < layout.lineCount && layout.getLineBottom(end) - startY <= usable) end++
            if (end == line) end = line + 1
            // Do not split closing formula / signature / name across two pages.
            if (closingL != null && nameL != null && closingL in (line + 1) until end && nameL >= end &&
                layout.getLineBottom(nameL) - layout.getLineTop(closingL) <= usable
            ) end = closingL

            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            val c = page.canvas
            c.save()
            c.translate(LEFT.toFloat(), (TOP - startY).toFloat())
            c.clipRect(0, startY, width, layout.getLineBottom(end - 1))
            layout.draw(c)
            c.restore()
            if (signature != null && sigL != null && sigL in line until end) {
                val scale = minOf(SIG_W / signature.width, SIG_H / signature.height)
                val w = signature.width * scale
                val h = signature.height * scale
                val top = TOP + (layout.getLineTop(sigL) - startY) + 2f
                val area = (layout.getLineTop(nameL ?: sigL) - layout.getLineTop(sigL)).toFloat()
                val y = top + ((area - h) / 2f).coerceAtLeast(0f)
                c.drawBitmap(signature, null, RectF(LEFT.toFloat(), y, LEFT + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            if (layout.lineCount > 0 && (end < layout.lineCount || pageNo > 1)) {
                val small = TextPaint(paint).apply { textSize = 8f; color = android.graphics.Color.GRAY }
                c.drawText("Seite $pageNo", (PAGE_W - RIGHT - 40).toFloat(), (PAGE_H - 30).toFloat(), small)
            }
            doc.finishPage(page)
            line = end
            pageNo++
        }
        if (pageNo == 1) doc.finishPage(doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create()))
        doc.writeTo(out)
        doc.close()
    }

    /** Creates the PDF in the cache and opens Android's share sheet (WhatsApp, E-Mail, Drive …). */
    fun share(context: Context, text: String, fileName: String, signature: Bitmap? = null) {
        val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { write(text, it, signature) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "PDF teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Removes simple Markdown so documents look clean in the chat and in the PDF. */
    fun clean(text: String): String = DocumentParser.clean(text)
}

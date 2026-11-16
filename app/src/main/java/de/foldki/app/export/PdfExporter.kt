package de.foldki.app.export

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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

    fun write(text: String, out: OutputStream) {
        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = 11f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            color = android.graphics.Color.BLACK
        }
        val width = PAGE_W - LEFT - RIGHT
        // DIN 5008: the date line of a letter stands on the right.
        val styled = android.text.SpannableString(text)
        val dateIdx = DocumentParser.dateLineIndex(text)
        if (dateIdx >= 0) {
            val start = text.lines().take(dateIdx).sumOf { it.length + 1 }
            val end = (start + text.lines()[dateIdx].length).coerceAtMost(text.length)
            styled.setSpan(android.text.style.AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), start, end, android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.1f)
            .build()

        val doc = PdfDocument()
        val usable = PAGE_H - TOP - BOTTOM
        var line = 0
        var pageNo = 1
        while (line < layout.lineCount) {
            val startY = layout.getLineTop(line)
            var end = line
            while (end < layout.lineCount && layout.getLineBottom(end) - startY <= usable) end++
            if (end == line) end = line + 1

            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            val c = page.canvas
            c.save()
            c.translate(LEFT.toFloat(), (TOP - startY).toFloat())
            c.clipRect(0, startY, width, layout.getLineBottom(end - 1))
            layout.draw(c)
            c.restore()
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
    fun share(context: Context, text: String, fileName: String) {
        val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { write(text, it) }
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

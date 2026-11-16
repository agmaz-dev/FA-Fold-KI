package de.foldki.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Imports photos and PDFs into private storage and extracts their text, so that
 * Gemini Nano (text only) can read them too. Claude additionally receives the original file.
 */
class Attachments(private val context: Context) {
    private val dir = File(context.filesDir, "attachments").apply { mkdirs() }
    private var pdfBoxReady = false

    suspend fun import(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: guessMime(uri)
        val name = displayName(uri) ?: if (mime == "application/pdf") "Dokument.pdf" else "Foto.jpg"
        val id = UUID.randomUUID().toString()
        when {
            mime == "application/pdf" -> {
                val file = File(dir, "$id.pdf")
                resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                Attachment(id = id, name = name, mime = mime, path = file.absolutePath, text = pdfText(file))
            }
            mime.startsWith("image/") -> {
                val file = File(dir, "$id.jpg")
                val bmp = loadScaled(uri, 1568)
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                Attachment(id = id, name = name, mime = "image/jpeg", path = file.absolutePath, text = ocr(bmp))
            }
            else -> throw IllegalArgumentException("Nur Fotos und PDF-Dateien werden unterstützt.")
        }
    }

    /** Saves [text] as an A4 PDF in the chat attachments (used for letters the AI wrote). */
    fun createPdf(text: String): Attachment {
        val subject = text.lines().firstOrNull { it.trim().startsWith("Betreff", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.take(40)?.replace(Regex("[^A-Za-z0-9ÄÖÜäöüß _-]"), "")?.trim()
        val name = (subject?.ifBlank { null } ?: "Schreiben") + ".pdf"
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.pdf")
        file.outputStream().use { de.foldki.app.export.PdfExporter.write(text, it) }
        return Attachment(id = id, name = name, mime = "application/pdf", path = file.absolutePath, text = "")
    }

    /** Saves a finished letter as DIN 5008 PDF in the chat attachments. */
    fun createLetterPdf(letter: de.foldki.app.export.LetterBuilder.Letter): Attachment {
        val subject = letter.subject.take(40).replace(Regex("[^A-Za-z0-9ÄÖÜäöüß _-]"), "").trim()
        val name = (subject.ifBlank { "Schreiben" }) + ".pdf"
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.pdf")
        file.outputStream().use { de.foldki.app.export.LetterBuilder.writePdf(letter, it) }
        return Attachment(id = id, name = name, mime = "application/pdf", path = file.absolutePath, text = "")
    }

    /** A fresh file for the camera to write a photo into. */
    fun newCameraFile(): File = File(dir, "camera_${System.currentTimeMillis()}.jpg")

    private fun guessMime(uri: Uri): String {
        val s = uri.toString().lowercase()
        return if (s.endsWith(".pdf")) "application/pdf" else "image/jpeg"
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun loadScaled(uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        var bmp = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Bild konnte nicht gelesen werden.")

        val rotation = runCatching {
            resolver.openInputStream(uri)!!.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrDefault(0f)

        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        if (scale < 1f || rotation != 0f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    private suspend fun pdfText(file: File): String {
        val text = runCatching {
            if (!pdfBoxReady) { PDFBoxResourceLoader.init(context); pdfBoxReady = true }
            PDDocument.load(file).use { PDFTextStripper().getText(it) }
        }.getOrDefault("").trim()
        if (text.length > 40) return text
        // Scanned PDF without a text layer: render the first pages and read them with OCR.
        return runCatching {
            val sb = StringBuilder()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    for (i in 0 until minOf(r.pageCount, 5)) {
                        r.openPage(i).use { page ->
                            val w = 1400
                            val h = (w.toFloat() * page.height / page.width).toInt()
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            sb.append(ocr(bmp)).append("\n\n")
                        }
                    }
                }
            }
            sb.toString().trim()
        }.getOrDefault(text)
    }

    private suspend fun ocr(bmp: Bitmap): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resume("") }
    }
}

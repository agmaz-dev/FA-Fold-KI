package de.foldki.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Signature images per sender profile: transparent PNG, at most 600×200 px, in noBackupFilesDir/signatures
 * (private, not in any backup, other apps cannot read it). Never sent to Nano or Claude; only drawn into PDFs.
 */
class SignatureStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "signatures")

    private fun file(profileId: String) = File(dir, profileId.replace(Regex("[^A-Za-z0-9-]"), "") + ".png")

    fun exists(profileId: String) = file(profileId).exists()

    fun load(profileId: String): Bitmap? = runCatching {
        file(profileId).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }.getOrNull()

    /** Crops to the drawn strokes, scales down to max 600×200 and stores as PNG. */
    fun save(profileId: String, drawn: Bitmap): Boolean = runCatching {
        val cropped = crop(drawn) ?: return false
        val scale = minOf(1f, MAX_W.toFloat() / cropped.width, MAX_H.toFloat() / cropped.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(cropped, (cropped.width * scale).toInt().coerceAtLeast(1), (cropped.height * scale).toInt().coerceAtLeast(1), true) else cropped
        dir.mkdirs()
        val tmp = File(dir, "tmp.png")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val target = file(profileId)
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        true
    }.getOrDefault(false)

    fun delete(profileId: String) { runCatching { file(profileId).delete() } }

    /** Bounding box of all non-transparent pixels plus a small margin; null if empty. */
    private fun crop(b: Bitmap): Bitmap? {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 16) {
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < 0) return null
        val m = 8
        val l = (minX - m).coerceAtLeast(0); val t = (minY - m).coerceAtLeast(0)
        val r = (maxX + m).coerceAtMost(w - 1); val bt = (maxY + m).coerceAtMost(h - 1)
        return Bitmap.createBitmap(b, l, t, r - l + 1, bt - t + 1)
    }

    companion object { const val MAX_W = 600; const val MAX_H = 200 }
}

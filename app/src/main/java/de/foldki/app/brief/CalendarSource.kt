package de.foldki.app.brief

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.Calendar

/** Today's and tomorrow's appointments from the phone's calendar (READ_CALENDAR), at most 8. Read only, nothing stored. */
object CalendarSource {
    data class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String, val tomorrow: Boolean)

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun read(context: Context, now: Long = System.currentTimeMillis()): List<Event> {
        if (!hasPermission(context)) return emptyList()
        val start = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val tomorrow = start.timeInMillis + 24L * 60 * 60 * 1000
        val end = tomorrow + 24L * 60 * 60 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start.timeInMillis); ContentUris.appendId(it, end)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        return runCatching {
            context.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                val out = mutableListOf<Event>()
                while (c.moveToNext() && out.size < 8) {
                    val allDay = c.getInt(3) == 1
                    val begin = c.getLong(1)
                    val endT = c.getLong(2)
                    // All-day events are stored in UTC; skip ones already over today.
                    if (!allDay && endT < now) continue
                    out += Event(c.getString(0)?.trim().orEmpty().ifBlank { "(ohne Titel)" }, begin, endT, allDay,
                        c.getString(4)?.trim().orEmpty(), if (allDay) dayKey(begin, utc = true) > dayKey(now, utc = false) else begin >= tomorrow)
                }
                out
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** yyyymmdd of [t]; all-day events are stored as UTC midnight. */
    private fun dayKey(t: Long, utc: Boolean): Int {
        val c = if (utc) Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")) else Calendar.getInstance()
        c.timeInMillis = t
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }
}

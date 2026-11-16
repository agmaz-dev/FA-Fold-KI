package de.foldki.app.brief

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The daily briefing ("Tagesstart"): facts collected on the device, optionally summarised by Nano. */
data class Briefing(
    val date: String,
    val events: List<CalendarSource.Event> = emptyList(),
    val notifications: List<NotificationCollector.Item> = emptyList(),
    val openItems: List<OpenItemsStore.Item> = emptyList(),
    /** Nano's short summary; null = Nano not available, the plain lists are shown. */
    val summary: String? = null,
    val loading: Boolean = false,
    val notificationsAllowed: Boolean = true,
) {
    val isEmpty get() = events.isEmpty() && notifications.isEmpty() && openItems.isEmpty()
}

object DailyBriefing {
    /** Fixed time window: 5:00 to 10:59. */
    fun inWindow(now: Long = System.currentTimeMillis()): Boolean {
        val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return h in 5..10
    }

    fun today(now: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date(now))

    fun dateLabel(now: Long = System.currentTimeMillis()): String = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(Date(now))

    private val time = SimpleDateFormat("HH:mm", Locale.GERMANY)

    fun eventLine(e: CalendarSource.Event): String = buildString {
        append(if (e.tomorrow) "Morgen " else "Heute ")
        append(if (e.allDay) "ganztägig" else time.format(Date(e.begin)))
        append(": ").append(e.title)
        if (e.location.isNotBlank()) append(" (").append(e.location.take(40)).append(")")
    }

    /** Plain facts for Nano, at most 3500 characters. */
    fun facts(b: Briefing): String = buildString {
        append("Datum: ").append(b.date).append("\n")
        if (b.events.isNotEmpty()) {
            append("Termine:\n"); b.events.forEach { append("- ").append(eventLine(it)).append("\n") }
        } else append("Termine: keine\n")
        if (b.notifications.isNotEmpty()) {
            append("Ungelesene Mitteilungen:\n")
            b.notifications.forEach { append("- ").append(it.app).append(", ").append(it.sender).append(": ").append(it.preview).append("\n") }
        }
        if (b.openItems.isNotEmpty()) {
            append("Offene Punkte aus den Chats:\n"); b.openItems.forEach { append("- ").append(it.text.take(140)).append("\n") }
        }
    }.take(3500)

    /** Text for "Als Chat öffnen". */
    fun asText(b: Briefing): String = buildString {
        append("Tagesstart – ").append(b.date).append("\n\n")
        b.summary?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
        if (b.events.isNotEmpty()) { append("Termine:\n"); b.events.forEach { append("• ").append(eventLine(it)).append("\n") }; append("\n") }
        if (b.notifications.isNotEmpty()) {
            append("Mitteilungen:\n"); b.notifications.forEach { append("• ").append(it.app).append(" – ").append(it.sender).append(": ").append(it.preview).append("\n") }; append("\n")
        }
        if (b.openItems.isNotEmpty()) { append("Offene Punkte:\n"); b.openItems.forEach { append(if (it.done) "☑ " else "☐ ").append(it.text).append("\n") } }
    }.trim()
}

package de.foldki.app.brief

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Reads ONLY the notifications that are currently shown, at the moment the app opens the daily briefing
 * (getActiveNotifications). Nothing is stored or forwarded; the callbacks for new notifications are not used.
 * Only messenger/mail apps, without ongoing ones, group summaries, the own app or hidden (sensitive) content.
 */
class NotificationCollector : NotificationListenerService() {
    data class Item(val app: String, val sender: String, val preview: String)

    override fun onListenerConnected() {
        instance = this
        val s = de.foldki.app.data.Settings(this)
        if (!(s.dailyBriefEnabled && s.briefNotifications)) runCatching { requestUnbind() } // feature off: let go of the connection
    }

    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    private fun snapshot(): List<Item> {
        val list = runCatching { activeNotifications }.getOrNull() ?: return emptyList()
        val ranking = runCatching { currentRanking }.getOrNull()
        return list.asSequence()
            .filter { it.packageName in APPS && it.packageName != packageName }
            .filter { !it.isOngoing && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
            .filter { sbn ->
                // Hidden/sensitive content stays hidden: secret visibility (app or user setting) is skipped.
                val secret = sbn.notification.visibility == Notification.VISIBILITY_SECRET ||
                    (ranking != null && runCatching {
                        Ranking().let { r -> ranking.getRanking(sbn.key, r) && r.lockscreenVisibilityOverride == Notification.VISIBILITY_SECRET }
                    }.getOrDefault(false))
                !secret
            }
            .sortedByDescending { it.postTime }
            .mapNotNull { sbn ->
                val ex = sbn.notification.extras
                val sender = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
                    ?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                // One-time codes (also redacted ones from Android 15) are never shown.
                if (sender.isEmpty() && text.isEmpty() || SENSITIVE.containsMatchIn(text)) null
                else Item(APPS[sbn.packageName] ?: sbn.packageName, sender.take(60), if (text.length > 80) text.take(79) + "…" else text)
            }
            .distinct()
            .take(15)
            .toList()
    }

    companion object {
        @Volatile private var instance: NotificationCollector? = null

        private val SENSITIVE = Regex(
            "(\\b(code|pin|tan|otp|passwort|kennwort|bestätigungscode|verification|sicherheitscode)\\b.{0,30}\\b\\d{4,8}\\b)|" +
                "(\\b\\d{4,8}\\b.{0,30}\\b(code|pin|tan|otp)\\b)|sensitive notification content hidden|vertrauliche[rn]? (inhalt|mitteilung)",
            RegexOption.IGNORE_CASE,
        )

        val APPS = mapOf(
            "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business",
            "org.thoughtcrime.securesms" to "Signal",
            "com.google.android.gm" to "Gmail",
            "com.samsung.android.messaging" to "Nachrichten",
            "org.telegram.messenger" to "Telegram", "org.telegram.messenger.web" to "Telegram",
        )

        fun isEnabled(context: Context) = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /** Current notifications, read right now. Empty if access is not granted or the service is not connected. */
        suspend fun current(context: Context): List<Item> {
            if (!isEnabled(context)) return emptyList()
            if (instance == null) {
                rebind(context)
                // Give Android a moment to connect the listener (e.g. right after switching the feature on).
                repeat(15) { if (instance == null) kotlinx.coroutines.delay(100) }
            }
            return instance?.snapshot() ?: emptyList()
        }

        /** Asks Android to connect the listener again (after switching the feature on). */
        fun rebind(context: Context) { runCatching { NotificationListenerService.requestRebind(ComponentName(context, NotificationCollector::class.java)) } }

        /** Feature off: disconnect the listener (the permission itself can only be revoked in Android's settings). */
        fun release() { runCatching { instance?.requestUnbind() } }
    }
}

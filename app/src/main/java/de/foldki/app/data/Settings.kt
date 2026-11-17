package de.foldki.app.data

import android.content.Context

/** Simple settings in the app's private SharedPreferences (not readable by other apps). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Sender profile last used for a recipient (key = LetterAnalysis.recipientKey), like FA-PDF. */
    fun rememberedProfile(recipientKey: String): String? =
        runCatching { org.json.JSONObject(prefs.getString("recipient_profiles", "{}") ?: "{}").optString(recipientKey).ifEmpty { null } }.getOrNull()

    fun rememberProfile(recipientKey: String, profileId: String) {
        val o = runCatching { org.json.JSONObject(prefs.getString("recipient_profiles", "{}") ?: "{}") }.getOrDefault(org.json.JSONObject())
        o.put(recipientKey, profileId)
        prefs.edit().putString("recipient_profiles", o.toString()).apply()
    }

    /** GitHub token (fine-grained or classic) – only on this device; the local model uses it for GitHub tasks after approval. */
    var githubToken: String
        get() = prefs.getString("github_token", "") ?: ""
        set(v) = prefs.edit().putString("github_token", v.trim()).apply()

    /** Account name confirmed by "Verbinden & prüfen". */
    var githubLogin: String
        get() = prefs.getString("github_login", "") ?: ""
        set(v) = prefs.edit().putString("github_login", v).apply()

    /** Own system prompt of the user. Empty by default and stays empty: the app adds no built-in instruction text. */
    var systemPrompt: String
        get() = prefs.getString("system_prompt", "") ?: ""
        set(v) = prefs.edit().putString("system_prompt", v).apply()

    /** "Automatisch lernen": the app saves memory entries by itself (explicit "Merk dir", statements about the user, corrections). On by default. */
    var autoLearn: Boolean
        get() = prefs.getBoolean("auto_learn", true)
        set(v) = prefs.edit().putBoolean("auto_learn", v).apply()

    var stVoice: String
        get() = prefs.getString("st_voice", "tessa") ?: "tessa"
        set(v) = prefs.edit().putString("st_voice", v).apply()

    var ttsRate: Float
        get() = prefs.getFloat("tts_rate", 1f)
        set(v) = prefs.edit().putFloat("tts_rate", v).apply()

    var webSearch: Boolean
        get() = prefs.getBoolean("web_search", false)
        set(v) = prefs.edit().putBoolean("web_search", v).apply()

    /** Divider position in the unfolded layout (0..1; 0 = sidebar closed, 1 = chat closed). Only stored on the device. */
    var splitFraction: Float
        get() = prefs.getFloat("split_fraction", 1f / 3f).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat("split_fraction", v).apply()

    /** "NONE", "FIRST" (sidebar closed) or "SECOND" (chat closed). */
    var splitCollapse: String
        get() = prefs.getString("split_collapse", "NONE") ?: "NONE"
        set(v) = prefs.edit().putString("split_collapse", v).apply()

    /** Own name and address, used as sender in letters. */
    var sender: String
        get() = prefs.getString("sender", "") ?: ""
        set(v) = prefs.edit().putString("sender", v.trim()).apply()

    /**
     * Crash guard: true while the local model loads or answers. Written with commit() (synchronously), so it is on
     * disk before native code runs. If the app finds it still true at the next start, the model killed the app.
     */
    var llmLoading: Boolean
        get() = prefs.getBoolean("llm_loading", false)
        set(v) { prefs.edit().putBoolean("llm_loading", v).commit() }

    /** File of the model that was loading/answering when [llmLoading] was set. */
    var llmAttempt: String
        get() = prefs.getString("llm_attempt", "") ?: ""
        set(v) { prefs.edit().putString("llm_attempt", v).commit() }

    /** Model file that crashed the app (not loaded automatically again until the user picks it). */
    var llmCrashed: String
        get() = prefs.getString("llm_crashed", "") ?: ""
        set(v) { prefs.edit().putString("llm_crashed", v).commit() }

    /** Daily briefing ("Tagesstart"), fixed window 5–11 o'clock. Off by default, the user switches it on. */
    var dailyBriefEnabled: Boolean
        get() = prefs.getBoolean("daily_brief", false)
        set(v) = prefs.edit().putBoolean("daily_brief", v).apply()

    var briefCalendar: Boolean
        get() = prefs.getBoolean("brief_calendar", true)
        set(v) = prefs.edit().putBoolean("brief_calendar", v).apply()

    /** Only the notifications shown at the moment the app opens; nothing is stored. */
    var briefNotifications: Boolean
        get() = prefs.getBoolean("brief_notifications", true)
        set(v) = prefs.edit().putBoolean("brief_notifications", v).apply()

    /** Open items found by rules in the own chat messages of the last 7 days. */
    var briefChats: Boolean
        get() = prefs.getBoolean("brief_chats", true)
        set(v) = prefs.edit().putBoolean("brief_chats", v).apply()

    /** yyyy-MM-dd of the last shown briefing (once per day). */
    var lastBriefDate: String
        get() = prefs.getString("last_brief_date", "") ?: ""
        set(v) = prefs.edit().putString("last_brief_date", v).apply()

    /**
     * Sender profiles for letters (JSON in "sender_profiles"). The first access migrates the old
     * free-text [sender] into a first profile "Privat" (nothing is lost).
     */
    var senderProfiles: List<SenderProfile>
        get() {
            val raw = prefs.getString("sender_profiles", null)
            if (raw != null) return SenderProfile.listFromJson(raw)
            val old = sender
            val list = if (old.isBlank()) emptyList() else listOf(SenderProfile.fromLegacy(old))
            prefs.edit().putString("sender_profiles", SenderProfile.listToJson(list))
                .putString("active_sender_id", list.firstOrNull()?.id ?: "").apply()
            return list
        }
        set(v) { prefs.edit().putString("sender_profiles", SenderProfile.listToJson(v)).apply(); syncLegacySender() }

    var activeSenderId: String
        get() = prefs.getString("active_sender_id", "") ?: ""
        set(v) { prefs.edit().putString("active_sender_id", v).apply(); syncLegacySender() }

    val activeSender: SenderProfile?
        get() = senderProfiles.let { list -> list.firstOrNull { it.id == activeSenderId } ?: list.firstOrNull() }

    /** Keeps the old key "sender" equal to the active profile (older versions read it). */
    private fun syncLegacySender() {
        prefs.edit().putString("sender", activeSender?.toLegacyText().orEmpty()).apply()
    }
}

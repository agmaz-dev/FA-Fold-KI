package de.foldki.app.data

import android.content.Context

/** Simple settings in the app's private SharedPreferences (not readable by other apps). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var claudeModel: String
        get() = prefs.getString("claude_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("claude_model", v).apply()

    var engine: Engine
        get() = runCatching { Engine.valueOf(prefs.getString("engine", Engine.NANO.name)!!) }
            .getOrDefault(Engine.NANO)
        set(v) = prefs.edit().putString("engine", v.name).apply()

    var systemPrompt: String
        get() = prefs.getString("system_prompt", DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM
        set(v) = prefs.edit().putString("system_prompt", v).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5 (stärkstes)",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5 (schnell)",
            "claude-haiku-5-5" to "Claude Haiku 5.5 (günstig)",
        )
        const val DEFAULT_SYSTEM =
            "Du bist ein hilfreicher Assistent. Antworte in der Sprache des Nutzers, klar und freundlich."
    }
}

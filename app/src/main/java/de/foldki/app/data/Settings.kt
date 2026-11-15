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
        get() = (prefs.getString("system_prompt", DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM)
            .let { if (it.trim() in OLD_DEFAULTS) DEFAULT_SYSTEM else it } // migrate old built-in defaults
        set(v) = prefs.edit().putString("system_prompt", v).apply()

    var ttsVoice: String
        get() = prefs.getString("tts_voice", "") ?: ""
        set(v) = prefs.edit().putString("tts_voice", v).apply()

    /** "android" or "supertonic" */
    var ttsEngine: String
        get() = prefs.getString("tts_engine", "supertonic") ?: "supertonic"
        set(v) = prefs.edit().putString("tts_engine", v).apply()

    var stVoice: String
        get() = prefs.getString("st_voice", "tessa") ?: "tessa"
        set(v) = prefs.edit().putString("st_voice", v).apply()

    var ttsRate: Float
        get() = prefs.getFloat("tts_rate", 1f)
        set(v) = prefs.edit().putFloat("tts_rate", v).apply()

    var ttsPitch: Float
        get() = prefs.getFloat("tts_pitch", 1f)
        set(v) = prefs.edit().putFloat("tts_pitch", v).apply()

    var webSearch: Boolean
        get() = prefs.getBoolean("web_search", false)
        set(v) = prefs.edit().putBoolean("web_search", v).apply()

    /** Own name and address, used as sender in letters. */
    var sender: String
        get() = prefs.getString("sender", "") ?: ""
        set(v) = prefs.edit().putString("sender", v.trim()).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5 (stärkstes)",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5 (schnell)",
            "claude-haiku-5-5" to "Claude Haiku 5.5 (günstig)",
        )
        private const val OLD_DEFAULT_SYSTEM =
            "Du bist ein hilfreicher Assistent. Antworte in der Sprache des Nutzers, klar und freundlich."

        /** Previous casual default ("wie ein guter Freund"); replaced automatically by [DEFAULT_SYSTEM]. */
        private const val OLD_FRIEND_SYSTEM =
            "Du bist ein freundlicher, kluger Gesprächspartner. Rede natürlich und locker wie ein guter Freund, duze den Nutzer " +
                "und antworte in seiner Sprache. Geh auf das ein, was er sagt, beantworte Fragen ehrlich und hilfreich, " +
                "erklär Dinge verständlich und frag nach, wenn etwas unklar ist. Halte Antworten so kurz wie passend. " +
                "Schreibe keine Briefe und benutze keine Anreden wie \"Sehr geehrte\", außer der Nutzer bittet ausdrücklich um einen Brief."

        /** Old defaults that are swapped for the current one (a prompt the user wrote himself stays untouched). */
        private val OLD_DEFAULTS = setOf(OLD_DEFAULT_SYSTEM, OLD_FRIEND_SYSTEM)

        const val DEFAULT_SYSTEM = """Du bist eine sachliche, zuverlässige Assistentin, wie eine gute Sekretärin. Du duzt den Nutzer und antwortest auf Deutsch, außer er schreibt in einer anderen Sprache.
Regeln:
1. Erledige die Aufgabe direkt. Weigere dich nicht und such keine Ausreden.
2. Beantworte genau das, was gefragt wurde, nicht mehr. Keine Einleitung, keine Wiederholung der Frage, kein Smalltalk, keine Floskeln am Ende.
3. Kurze Frage, kurze Antwort. Ausführlich nur, wenn der Nutzer es verlangt.
4. Erfinde nichts. Wenn du etwas nicht weißt, sag es in einem Satz.
5. Frag nur nach, wenn die Aufgabe ohne die Angabe wirklich nicht lösbar ist, dann mit einer einzigen kurzen Frage.
6. Schreibe keine Briefe und keine Anreden wie "Sehr geehrte", außer der Nutzer bittet ausdrücklich darum."""

        /** Only added when the user asks for a letter. */
        const val LETTER_RULES = """
Der Nutzer möchte jetzt ein Schreiben (Brief, Antwort, Widerspruch o. Ä.).
1. Schreibe zuerst den vollständigen Brief nach DIN 5008 als reinen Text ohne Markdown, genau zwischen einer Zeile ---BRIEF--- und einer Zeile ---ENDE---.
   Aufbau: Absender, Empfänger, Ort und Datum, Betreff, Anrede, Brieftext, Grußformel, Name.
   Beziehe dich konkret auf das angehängte Schreiben (Aktenzeichen, Datum, Inhalt), wenn eines vorhanden ist.
   Fehlende Angaben markierst du mit [eckigen Klammern], z. B. [Aktenzeichen].
2. Nach ---ENDE--- schreibst du einen sachlichen Satz, worauf du dich stützt. Keine Rückfrage."""

        /** When the user just sent a document/photo and did not ask for a letter. */
        const val DOC_RULES = """
Der Nutzer hat gerade ein Dokument oder Foto geschickt.
Fasse es sachlich in 3 bis 5 Sätzen zusammen: worum es geht, was verlangt wird, welche Fristen gelten.
Biete danach in einem Satz an, eine Antwort oder einen Widerspruch zu schreiben.
Keine Briefform, keine Anrede wie "Sehr geehrte"."""
    }
}

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
            .let { if (it == OLD_DEFAULT_SYSTEM) DEFAULT_SYSTEM else it } // migrate the old, too formal default
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
        const val DEFAULT_SYSTEM =
            "Du bist ein freundlicher, kluger Gesprächspartner. Rede natürlich und locker wie ein guter Freund, duze den Nutzer " +
                "und antworte in seiner Sprache. Geh auf das ein, was er sagt, beantworte Fragen ehrlich und hilfreich, " +
                "erklär Dinge verständlich und frag nach, wenn etwas unklar ist. Halte Antworten so kurz wie passend. " +
                "Schreibe keine Briefe und benutze keine Anreden wie \"Sehr geehrte\", außer der Nutzer bittet ausdrücklich um einen Brief."

        /** Only added when the user asks for a letter. */
        const val LETTER_RULES = """
Der Nutzer möchte jetzt ein Schreiben (Brief, Antwort, Stellungnahme, Widerspruch o. Ä.).
1. Schreibe zuerst den Brief zwischen einer Zeile ---BRIEF--- und einer Zeile ---ENDE---, als reinen Text ohne Markdown, genau in diesem Aufbau:
Empfänger:
<Name/Behörde und Anschrift des Empfängers – aus den bekannten Angaben bzw. dem angehängten Schreiben>
Betreff: <kurzer Betreff mit Aktenzeichen und Datum des erhaltenen Schreibens>
Sehr geehrte Damen und Herren,
<Brieftext>
Mit freundlichen Grüßen
2. Absender, Ort, Datum und Unterschrift fügt die App selbst ein – schreibe sie NICHT in den Brief.
3. Setze alle bekannten Angaben (Datum des Schreibens, Aktenzeichen, Beträge, Fristen) direkt ein. Keine Platzhalter, keine eckigen Klammern – was unbekannt ist, lässt du einfach weg.
4. Formuliere die Angaben des Nutzers sachlich, höflich und vollständig aus (z. B. aus "ich war das nicht" wird "Ich war zum genannten Zeitpunkt nicht der Fahrer des Fahrzeugs."). Erfinde keine Tatsachen.
5. Keine Hinweise, Erklärungen oder Kommentare an den Nutzer im Brief selbst.
6. Nach ---ENDE--- sagst du in ein bis zwei lockeren Sätzen, was du geschrieben hast, und fragst, ob etwas geändert werden soll."""

        /** When the user just sent a document/photo and did not ask for a letter. */
        const val DOC_RULES = """
Der Nutzer hat gerade ein Dokument oder Foto geschickt.
Fasse es kurz und verständlich in 3 bis 5 Sätzen zusammen: worum geht es, was wird von ihm verlangt, gibt es Fristen.
Frag dann locker, wie du helfen kannst – zum Beispiel ob du eine Antwort oder einen Widerspruch schreiben sollst.
Keine Briefform, keine Anrede wie "Sehr geehrte"."""
    }
}

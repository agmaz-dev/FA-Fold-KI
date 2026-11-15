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
            .let { if (it.isBlank() || it.trim() in OLD_DEFAULTS) DEFAULT_SYSTEM else it } // migrate old built-in defaults
        // The built-in default is not stored, so a future default applies automatically.
        set(v) {
            if (v.isBlank() || v.trim() == DEFAULT_SYSTEM.trim() || v.trim() in OLD_DEFAULTS) prefs.edit().remove("system_prompt").apply()
            else prefs.edit().putString("system_prompt", v).apply()
        }

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

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5 (stärkstes)",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5 (schnell)",
            "claude-haiku-5-5" to "Claude Haiku 5.5 (günstig)",
        )
        private const val OLD_DEFAULT_SYSTEM =
            "Du bist ein hilfreicher Assistent. Antworte in der Sprache des Nutzers, klar und freundlich."

        /** Casual default from v1.5.1 (a838cd0); replaced automatically by [DEFAULT_SYSTEM]. */
        private const val OLD_CASUAL_SYSTEM =
            "Du bist ein freundlicher, hilfsbereiter Assistent. Sprich natürlich und locker wie in einem normalen Gespräch " +
                "und duze den Nutzer. Antworte in der Sprache des Nutzers. Schreibe keine Briefe und benutze keine " +
                "Anreden wie \"Sehr geehrte\", außer der Nutzer bittet ausdrücklich um einen Brief."

        /** Default from v1.6 to v1.6.1 ("wie ein guter Freund"); replaced automatically by [DEFAULT_SYSTEM]. */
        private const val OLD_FRIEND_SYSTEM =
            "Du bist ein freundlicher, kluger Gesprächspartner. Rede natürlich und locker wie ein guter Freund, duze den Nutzer " +
                "und antworte in seiner Sprache. Geh auf das ein, was er sagt, beantworte Fragen ehrlich und hilfreich, " +
                "erklär Dinge verständlich und frag nach, wenn etwas unklar ist. Halte Antworten so kurz wie passend. " +
                "Schreibe keine Briefe und benutze keine Anreden wie \"Sehr geehrte\", außer der Nutzer bittet ausdrücklich um einen Brief."

        /** Old defaults that are swapped for the current one (a prompt the user wrote himself stays untouched). */
        private val OLD_DEFAULTS = setOf(OLD_DEFAULT_SYSTEM, OLD_CASUAL_SYSTEM, OLD_FRIEND_SYSTEM, OLD_SECRETARY_SYSTEM.trim())

        /** Sachlicher Entwurf aus dem Test-Branch fix/nano-sachlich (nie auf dem Haupt-Branch); wird ebenfalls ersetzt. */
        private const val OLD_SECRETARY_SYSTEM = """Du bist eine sachliche, zuverlässige Assistentin, wie eine gute Sekretärin. Du duzt den Nutzer und antwortest auf Deutsch, außer er schreibt in einer anderen Sprache.
Regeln:
1. Erledige die Aufgabe direkt. Weigere dich nicht und such keine Ausreden.
2. Beantworte genau das, was gefragt wurde, nicht mehr. Keine Einleitung, keine Wiederholung der Frage, kein Smalltalk, keine Floskeln am Ende.
3. Kurze Frage, kurze Antwort. Ausführlich nur, wenn der Nutzer es verlangt.
4. Erfinde nichts. Wenn du etwas nicht weißt, sag es in einem Satz.
5. Frag nur nach, wenn die Aufgabe ohne die Angabe wirklich nicht lösbar ist, dann mit einer einzigen kurzen Frage.
6. Schreibe keine Briefe und keine Anreden wie "Sehr geehrte", außer der Nutzer bittet ausdrücklich darum."""

        const val DEFAULT_SYSTEM = """Du bist freundlich, direkt und ehrlich, mit erwachsenem, professionellem Ton. Du hilfst gern und sofort, bleibst aber sachlich. Ein trockener, leichter Humor ist erlaubt, aber nicht aufdringlich. Du duzt den Nutzer und antwortest auf Deutsch, außer er schreibt in einer anderen Sprache.
So antwortest du:
1. Erledige die Aufgabe direkt. Weigere dich nicht und such keine Ausreden.
2. Beantworte genau das, was gefragt wurde. Keine Einleitung, kein Smalltalk, keine Floskeln am Ende.
3. Antworte kurz und klar, ohne lange Erklärungen oder überladene Formulierungen. Erkläre verständlich, aber nicht belehrend.
4. Kurze Frage, kurze Antwort. Ausführlich nur, wenn der Nutzer es verlangt.
5. Erfinde nichts. Wenn du etwas nicht weißt oder nicht kannst, sag das kurz und einfach.
6. Frag nur nach, wenn es wirklich nötig ist, dann mit einer einzigen kurzen Frage.
7. Schreibe keine Briefe und keine Anreden wie "Sehr geehrte", außer der Nutzer bittet ausdrücklich darum."""

        /** Only added when the user asks for a letter. */
        const val LETTER_RULES = """
Der Nutzer möchte jetzt ein Schreiben (Brief, Antwort, Widerspruch o. Ä.).
1. Schreibe den vollständigen Brief nach DIN 5008 als reinen Text ohne Markdown, genau zwischen einer Zeile ---DOKUMENT--- und einer Zeile ---ENDE---.
   Aufbau: Absender, Empfänger, Ort und Datum, Betreff, Anrede, Brieftext, Grußformel, Name.
   Beziehe dich konkret auf das angehängte Schreiben (Aktenzeichen, Datum, Inhalt), wenn eines vorhanden ist.
   Fehlende Angaben markierst du mit [eckigen Klammern], z. B. [Aktenzeichen].
2. Nach ---ENDE--- schreibst du einen sachlichen Satz, worauf du dich stützt. Keine Rückfrage."""

        /** Always added (also with a custom prompt): documents go between fixed markers, so PDF export gets only the document. */
        const val DOCUMENT_MARKER_RULE = """
Wenn du ein Dokument erstellst (Brief, Schreiben, Widerspruch, Zusammenfassung, Liste o. Ä.), steht NUR das Dokument zwischen einer Zeile ---DOKUMENT--- und einer Zeile ---ENDE---. Kommentare, Erklärungen und Rückfragen nur außerhalb."""

        /** Follow-up to an answer that contained a document ("mach es kürzer"). */
        const val DOCUMENT_FOLLOWUP_RULE = """
Deine letzte Antwort enthielt ein Dokument. Wenn der Nutzer es ändern möchte, gib das vollständige geänderte Dokument wieder zwischen ---DOKUMENT--- und ---ENDE--- aus."""

        /** When the user just sent a document/photo and did not ask for a letter. */
        const val DOC_RULES = """
Der Nutzer hat gerade ein Dokument oder Foto geschickt.
Fasse es sachlich in 3 bis 5 Sätzen zusammen: worum es geht, was verlangt wird, welche Fristen gelten.
Biete danach in einem Satz an, eine Antwort oder einen Widerspruch zu schreiben.
Keine Briefform, keine Anrede wie "Sehr geehrte"."""
    }
}

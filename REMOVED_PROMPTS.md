# Entfernte Prompts und Regeltexte (FA Fold KI 1.0.1)

Paula will **null vorgefertigte Prompts**: Im normalen Chat geht nur an das Modell, was er selbst tippt (plus seine eigene Systemanweisung, falls er eine einträgt). Hier steht jeder entfernte Text im Wortlaut, mit Datei und Zeilen im **Basis-Commit `263c08e`** (Stand von FA Fold KI 1.0, Release apk-2).

Wiederherstellen mit Git:

```
git show 263c08e:app/src/main/java/de/foldki/app/data/Settings.kt          # Standardtexte
git show 263c08e:app/src/main/java/de/foldki/app/ChatViewModel.kt          # buildSystem, VOICE_*, send()
git show 263c08e:app/src/main/java/de/foldki/app/ai/LlamaEngine.kt         # reply(): Daten-Beschriftungen
git show 263c08e:app/src/main/java/de/foldki/app/ai/LocalGitHubAgent.kt    # Persona-Zeile
git show 263c08e:app/src/main/java/de/foldki/app/data/MemoryStore.kt       # promptBlock
git checkout 263c08e -- <datei>                                            # eine Datei zurückholen
```

Eingabestelle: Die Systemanweisung wird in den **Einstellungen** eingegeben, an der ursprünglichen Stelle (Gruppe „Allgemein“, erste Zeile, wie bei `263c08e` Zeile 222), jetzt beschriftet „Anweisungen an die KI“. Untertitel: „Leer“ oder eine kurze Vorschau des Textes. Antippen öffnet den Dialog „Anweisungen an die KI“ (`InstructionsDialog` in `SettingsScreen.kt`). Gespeichert wird genau das Eingegebene in `Settings.systemPrompt`; leer = keine Systemnachricht. Im Chat gibt es dafür kein Symbol. Entfernt wurde nur der alte Standardtext, der Zurücksetzen-Button „Auf Standard zurücksetzen“ und die eigene Seite „Systemanweisung“ (`SettingsScreen.kt` bei `263c08e`: Zeilen 82, 222–223, 350–358); der Dialog ersetzt die Seite.

Die Zeilennummern unten gelten nur für `263c08e`, nicht für den aktuellen Stand.

Hinweis: In dieser Datei steht der Quelltext (Kotlin-Strings) unverändert, auch mit `\"` und `"""`.

## 1. DEFAULT_SYSTEM (Persona „persönliche KI-Assistentin“)

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 177–183 (bei `263c08e`)
- Grund: Die App schrieb dem Modell eine Persona, Tonregeln und Verhaltensregeln vor. Das Feld „Systemanweisung“ startet jetzt leer und bleibt leer.

```kotlin
        /** Natural, warm conversation partner (like in the first versions) – letters stay factual via LETTER_RULES. */
        const val DEFAULT_SYSTEM = """Du bist eine persönliche KI-Assistentin auf dem Handy des Nutzers – so wie ChatGPT, Claude oder Grok: herzlich, aufmerksam, klug und fachlich sicher.
Du duzt den Nutzer und antwortest in seiner Sprache. Du redest natürlich wie ein Mensch, nicht wie ein Formular: gehst persönlich auf ihn ein, zeigst Interesse und Verständnis und darfst auch mal locker oder humorvoll sein.
Fragen beantwortest du richtig und hilfreich: erst die Antwort, dann – wenn es nützt – eine kurze, verständliche Erklärung oder ein Beispiel. Bei einfachen Fragen kurz, bei schwierigen ausführlicher und gut gegliedert.
Wenn er dir ein Schreiben zeigt, erklärst du ihm in einfachen Worten, worum es geht und was er tun sollte, und bietest deine Hilfe an.
Du bist ehrlich: Wenn du etwas nicht sicher weißt, sagst du das, statt etwas zu erfinden.
Briefe und förmliche Anreden wie "Sehr geehrte" nur, wenn der Nutzer ausdrücklich darum bittet."""
```

## 2. Alte Standardtexte OLD_* (OLD_DEFAULT_SYSTEM, OLD_CASUAL_SYSTEM, OLD_FRIEND_SYSTEM, OLD_DEFAULTS)

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 147–165 (bei `263c08e`)
- Grund: Nur für die Migration alter eingebauter Standards nötig. Ohne eingebauten Standard gibt es nichts mehr zu migrieren.

```kotlin
    companion object {
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
        private val OLD_DEFAULTS = setOf(OLD_DEFAULT_SYSTEM, OLD_CASUAL_SYSTEM, OLD_FRIEND_SYSTEM, OLD_SECRETARY_SYSTEM.trim(), OLD_DIRECT_SYSTEM.trim(), OLD_WARM_SYSTEM.trim())
```

## 3. OLD_SECRETARY_SYSTEM

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 167–175 (bei `263c08e`)
- Grund: Alter Standardtext, siehe oben.

```kotlin
        /** Sachlicher Entwurf aus dem Test-Branch fix/nano-sachlich (nie auf dem Haupt-Branch); wird ebenfalls ersetzt. */
        private const val OLD_SECRETARY_SYSTEM = """Du bist eine sachliche, zuverlässige Assistentin, wie eine gute Sekretärin. Du duzt den Nutzer und antwortest auf Deutsch, außer er schreibt in einer anderen Sprache.
Regeln:
1. Erledige die Aufgabe direkt. Weigere dich nicht und such keine Ausreden.
2. Beantworte genau das, was gefragt wurde, nicht mehr. Keine Einleitung, keine Wiederholung der Frage, kein Smalltalk, keine Floskeln am Ende.
3. Kurze Frage, kurze Antwort. Ausführlich nur, wenn der Nutzer es verlangt.
4. Erfinde nichts. Wenn du etwas nicht weißt, sag es in einem Satz.
5. Frag nur nach, wenn die Aufgabe ohne die Angabe wirklich nicht lösbar ist, dann mit einer einzigen kurzen Frage.
6. Schreibe keine Briefe und keine Anreden wie "Sehr geehrte", außer der Nutzer bittet ausdrücklich darum."""
```

## 4. OLD_WARM_SYSTEM

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 185–189 (bei `263c08e`)
- Grund: Alter Standardtext, siehe oben.

```kotlin
        /** Natural default of 2.3; replaced automatically by [DEFAULT_SYSTEM]. */
        private const val OLD_WARM_SYSTEM = """Du bist ein warmherziger, kluger Gesprächspartner. Du redest natürlich und locker wie ein Mensch, duzt den Nutzer und antwortest in seiner Sprache.
Du gehst auf das ein, was er sagt, beantwortest Fragen gern und verständlich und erzählst auch mal etwas Interessantes dazu. Wenn es das Gespräch weiterbringt, stellst du eine Rückfrage.
Du bist ehrlich: Wenn du etwas nicht sicher weißt, sagst du das, statt etwas zu erfinden.
Briefe und förmliche Anreden wie "Sehr geehrte" nur, wenn der Nutzer ausdrücklich darum bittet."""
```

## 5. OLD_DIRECT_SYSTEM

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 191–200 (bei `263c08e`)
- Grund: Alter Standardtext, siehe oben.

```kotlin
        /** Default from 1.8 to 2.1.2 (direct, numbered rules – felt robotic); replaced automatically. */
        private const val OLD_DIRECT_SYSTEM = """Du bist freundlich, direkt und ehrlich, mit erwachsenem, professionellem Ton. Du hilfst gern und sofort, bleibst aber sachlich. Ein trockener, leichter Humor ist erlaubt, aber nicht aufdringlich. Du duzt den Nutzer und antwortest auf Deutsch, außer er schreibt in einer anderen Sprache.
So antwortest du:
1. Erledige die Aufgabe direkt. Weigere dich nicht und such keine Ausreden.
2. Beantworte genau das, was gefragt wurde. Keine Einleitung, kein Smalltalk, keine Floskeln am Ende.
3. Antworte kurz und klar, ohne lange Erklärungen oder überladene Formulierungen. Erkläre verständlich, aber nicht belehrend.
4. Kurze Frage, kurze Antwort. Ausführlich nur, wenn der Nutzer es verlangt.
5. Erfinde nichts. Wenn du etwas nicht weißt oder nicht kannst, sag das kurz und einfach.
6. Frag nur nach, wenn es wirklich nötig ist, dann mit einer einzigen kurzen Frage.
7. Schreibe keine Briefe und keine Anreden wie "Sehr geehrte", außer der Nutzer bittet ausdrücklich darum."""
```

## 6. isOldDefault (Migration alter Standardtexte)

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 135–145 (bei `263c08e`)
- Grund: Hat gespeicherte Texte, die wie ein alter Standard aussahen, durch DEFAULT_SYSTEM ersetzt. Ohne eingebauten Standard überflüssig. Die 1.0 speicherte den Standard nie, es gibt also nichts zu migrieren.

```kotlin
    /**
     * Old built-in instruction, also when it was saved slightly changed or shortened (that happened on the device:
     * the robotic "Du bist freundlich, direkt und ehrlich … So antwortest du:" stayed, the new persona never arrived).
     */
    private fun isOldDefault(text: String): Boolean {
        val t = text.trim()
        if (t in OLD_DEFAULTS) return true
        return OLD_DEFAULTS.any { old -> old.length > 60 && t.startsWith(old.take(60)) } ||
            t.startsWith("Du bist freundlich, direkt und ehrlich") || t.contains("So antwortest du:") ||
            t.startsWith("Du bist eine sachliche, zuverlässige Assistentin") || t.startsWith("Du bist ein hilfreicher Assistent.")
    }
```

## 7. APP_ABILITIES (App-Fähigkeiten als Regeltext)

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 202–204 (bei `263c08e`)
- Grund: Wurde bei jeder Anfrage in den System-Prompt geschrieben. Die App-Funktionen (Dateien lesen, PDF erstellen) laufen technisch weiter, das Modell bekommt sie nur nicht mehr als Text erklärt.

```kotlin
        /** What the app can do – so the model never claims it cannot create PDFs or see files. */
        const val APP_ABILITIES = "Die App liest Dateien und Fotos, die der Nutzer anhängt, und macht aus jedem Brief automatisch ein PDF. " +
            "Sag nie, dass du keine PDFs erstellen oder keine Dateien sehen kannst."
```

## 8. LETTER_RULES (Briefregeln, Marker ---DOKUMENT--- / ---ENDE---)

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 206–217 (bei `263c08e`)
- Grund: Vorgefertigte Brief-Anweisung, die automatisch zugefügt wurde, sobald ein Brief erkannt wurde.

```kotlin
        /** Only added when the user asks for a letter. */
        const val LETTER_RULES = """
Der Nutzer möchte jetzt ein Schreiben (Brief, Antwort, Widerspruch o. Ä.). Schreibe es SOFORT mit den vorhandenen Angaben – keine Rückfragen.
Gib zwischen einer Zeile ---DOKUMENT--- und einer Zeile ---ENDE--- GENAU dieses Format aus, als reinen Text ohne Markdown:
Betreff: <kurzer Betreff mit Bezug, z. B. Zeichen/Nummer des Schreibens>
<Anrede>,
<Brieftext in 2 bis 4 Absätzen: zuerst Bezug auf das erhaltene Schreiben (Datum, Zeichen), dann das Anliegen des Nutzers sachlich und vollständig ausformuliert, am Ende klar, was erwartet wird>
Mit freundlichen Grüßen
Keine Adressen, kein Datum, keinen Namen unter dem Gruß, keine Platzhalter in eckigen Klammern – Absender, Empfänger, Datum und Unterschrift setzt die App selbst ein.
Übernimm Beträge, Daten und Nummern genau so, wie sie unten stehen. Erfinde keine Namen von Ansprechpartnern.
Formuliere die Worte des Nutzers sachlich aus (z. B. aus "ich zahle das nicht" wird "Ich widerspreche der Forderung und werde den Betrag nicht zahlen.").
Nach ---ENDE--- schreibst du einen kurzen Satz dazu."""
```

## 9. SENDER_RULES (Absenderregeln)

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 219–223 (bei `263c08e`)
- Grund: Vorgefertigter Regeltext zum Absenderblock. Im Stand `263c08e` war die Konstante schon nicht mehr in buildSystem eingebunden (toter Code), jetzt ganz gelöscht.

```kotlin
        /** Added to letters when a sender profile is active. */
        const val SENDER_RULES = """
Absender: Übernimm den Block "Absender (aktiv)" genau so, nichts weglassen, nichts erfinden, nichts ändern.
In förmlichen Briefen (Anrede "Sehr geehrte …") steht nach der Datumszeile und vor dem Betreff die Zeile "Unser Zeichen: " mit den Initialen. In lockeren Briefen (Anrede "Hallo", "Liebe …") gibt es diese Zeile nicht.
Unter die Grußformel schreibst du nur den Namen; die Unterschrift setzt die App selbst ein."""
```

## 10. DOCUMENT_MARKER_RULE (Marker-Regel für Dokumente)

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 225–227 (bei `263c08e`)
- Grund: Vorgefertigte Anweisung, Dokumente zwischen ---DOKUMENT--- und ---ENDE--- zu schreiben. Das PDF-Erkennen in DocumentParser funktioniert ohne Marker weiter (Brief erkannt an Anrede und Grußformel).

```kotlin
        /** Always added (also with a custom prompt): documents go between fixed markers, so PDF export gets only the document. */
        const val DOCUMENT_MARKER_RULE = """
Wenn du ein Dokument erstellst (Brief, Schreiben, Widerspruch, Zusammenfassung, Liste o. Ä.), steht NUR das Dokument zwischen einer Zeile ---DOKUMENT--- und einer Zeile ---ENDE---. Kommentare, Erklärungen und Rückfragen nur außerhalb."""
```

## 11. DOCUMENT_FOLLOWUP_RULE (Regel für Änderungswünsche am Dokument)

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 229–231 (bei `263c08e`)
- Grund: Vorgefertigte Anweisung für Folgefragen („mach es kürzer“).

```kotlin
        /** Follow-up to an answer that contained a document ("mach es kürzer"). */
        const val DOCUMENT_FOLLOWUP_RULE = """
Deine letzte Antwort enthielt ein Dokument. Wenn der Nutzer es ändern möchte, gib das vollständige geänderte Dokument wieder zwischen ---DOKUMENT--- und ---ENDE--- aus."""
```

## 12. DOC_RULES (Regel nach gesendetem Dokument/Foto)

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 233–238 (bei `263c08e`)
- Grund: Wurde nach dem Senden einer Datei an den System-Prompt gehängt und sagte dem Modell, wie es antworten soll.

```kotlin
        /** When the user just sent a document/photo and did not ask for a letter. */
        const val DOC_RULES = """
Der Nutzer hat gerade ein Dokument oder Foto geschickt.
Erkläre ihm in deinen eigenen, freundlichen Worten (3 bis 6 Sätze), worum es geht, was von ihm verlangt wird und welche Fristen gelten – so, als würdest du es einem Freund erklären.
Sag ihm, was du an seiner Stelle tun würdest, und biete an, eine Antwort oder einen Widerspruch zu schreiben.
Keine Briefform, keine Anrede wie "Sehr geehrte"."""
```

## 13. Fallback: leeres System-Prompt-Feld wird zu Standardtext (Getter/Setter)

- Datei: `app/src/main/java/de/foldki/app/data/Settings.kt`, Zeilen 29–35 (bei `263c08e`)
- Grund: Leeres Feld heißt jetzt: leer. Der Getter liefert den gespeicherten Wert oder "", der Setter speichert genau das Eingegebene.

```kotlin
    var systemPrompt: String
        get() = (prefs.getString("system_prompt", DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM)
            .let { if (it.isBlank() || isOldDefault(it)) DEFAULT_SYSTEM else it } // migrate old built-in defaults
        // The built-in default is not stored, so a future default applies automatically.
        set(v) {
            if (v.isBlank() || v.trim() == DEFAULT_SYSTEM.trim() || isOldDefault(v)) prefs.edit().remove("system_prompt").apply()
            else prefs.edit().putString("system_prompt", v).apply()
```

## 14. Fallback in saveSettings (leer → DEFAULT_SYSTEM)

- Datei: `app/src/main/java/de/foldki/app/ChatViewModel.kt`, Zeilen 519–521 (bei `263c08e`)
- Grund: Ein leeres Feld wird nicht mehr durch Standardtext ersetzt.

```kotlin
    fun saveSettings(system: String) {
        settings.systemPrompt = system.ifBlank { Settings.DEFAULT_SYSTEM }
    }
```

## 15. VOICE_LETTER_RULES und VOICE_RULES (Sprachregeln)

- Datei: `app/src/main/java/de/foldki/app/ChatViewModel.kt`, Zeilen 87–92 (bei `263c08e`)
- Grund: Vorgefertigte Sprachmodus-Anweisung („kurz, ohne Listen …“), wurde im Sprachmodus an den System-Prompt gehängt.

```kotlin
private const val VOICE_LETTER_RULES = """
Das Gespräch läuft per Sprache. Nur der Text außerhalb von ---DOKUMENT--- … ---ENDE--- wird vorgelesen: Halte ihn kurz und gesprochen."""

private const val VOICE_RULES = """
Das Gespräch läuft gerade per Sprache: Deine Antwort wird vorgelesen.
Antworte kurz und natürlich gesprochen (meist 1 bis 4 Sätze), ohne Listen, Tabellen, Emojis oder Sonderzeichen."""
```

## 16. Zusammenbau des System-Prompts in send(): DOC_RULES + VOICE_* + Gedächtnis/Themen

- Datei: `app/src/main/java/de/foldki/app/ChatViewModel.kt`, Zeilen 582–588 (bei `263c08e`)
- Grund: Hier wurden DOC_RULES, VOICE_RULES und der Gedächtnisblock (MemoryStore.promptBlock samt Themen der letzten Chats) an den System-Prompt gehängt.

```kotlin
                val system = buildSystem(wantsLetter, followUpDoc = lastDoc != null, history = history) +
                    (if (sentDoc) Settings.DOC_RULES + "\n" else "") +
                    (if (voice) (if (wantsLetter) VOICE_LETTER_RULES else VOICE_RULES) else "")
                // The local model also gets the memory and the topics of the last chats (all on the phone).
                val topics = _ui.value.conversations.filter { it.id != conv.id }.take(5).map { it.title }.filter { it.isNotBlank() }
                val localSystem = system + "\n" + de.foldki.app.data.MemoryStore.promptBlock(memory.all(), topics)
                logSystem = localSystem
```

## 17. MemoryStore.promptBlock (Gedächtnis und Chat-Themen als Prompt-Text)

- Datei: `app/src/main/java/de/foldki/app/data/MemoryStore.kt`, Zeilen 49–59 (bei `263c08e`)
- Grund: Hat gemerkte Fakten und Titel der letzten Chats als Text ins Modell geschrieben. Gedächtnis-Einträge werden in den Einstellungen weiter angezeigt, gehen aber nicht mehr ans Modell.

```kotlin
        /** Block for the instruction of the on-device model. */
        fun promptBlock(facts: List<String>, recentTopics: List<String>): String = buildString {
            if (facts.isNotEmpty()) {
                append("Was du über den Nutzer weißt (aus früheren Gesprächen, nutze es natürlich, ohne es aufzuzählen):\n")
                facts.forEach { append("- ").append(it).append("\n") }
            }
            if (recentTopics.isNotEmpty()) {
                append("Themen seiner letzten Gespräche mit dir: ").append(recentTopics.joinToString("; ")).append("\n")
            }
        }
    }
```

## 18. buildSystem (Datum, Marker-Regeln, APP_ABILITIES, Brief- und Datenzeilen)

- Datei: `app/src/main/java/de/foldki/app/ChatViewModel.kt`, Zeilen 750–778 (bei `263c08e`)
- Grund: Enthielt „Heutiges Datum“, DOCUMENT_MARKER_RULE, DOCUMENT_FOLLOWUP_RULE, APP_ABILITIES, LETTER_RULES und alle Briefdaten-Zeilen („Datum für das neue Schreiben“, „Datum des angehängten Schreibens“, „Empfänger“, „Zeichen“, „Betreff“, „Frist“, „Betrag“, „Ansprechpartner“, „Handschriftliche Notiz“). Jetzt gibt es keinen buildSystem mehr: System = nur die eigene Anweisung des Nutzers.

```kotlin
    /** Letter rules only when the user actually asks for a letter – otherwise the AI talks normally. */
    private fun buildSystem(letter: Boolean, followUpDoc: Boolean, history: List<ChatMessage> = emptyList()): String = buildString {
        append(settings.systemPrompt).append("\n")
        // Marker rule only when a document is likely asked for: small models otherwise echo "---ENDE---" into normal answers.
        val lastAsk = history.lastOrNull { it.role == Role.USER }?.text.orEmpty()
        if (letter || followUpDoc || DOC_WORDS.containsMatchIn(lastAsk)) append(Settings.DOCUMENT_MARKER_RULE).append("\n")
        if (followUpDoc) append(Settings.DOCUMENT_FOLLOWUP_RULE).append("\n")
        append("Heutiges Datum: ").append(today()).append("\n")
        append(Settings.APP_ABILITIES).append("\n")
        if (letter) {
            append(Settings.LETTER_RULES).append("\n")
            append("Datum für das neue Schreiben (heute): ").append(today()).append("\n")
            // Data of the attached original letter, read by the app from its first page.
            val original = history.lastOrNull { it.role == Role.USER && it.attachments.isNotEmpty() }?.attachments?.lastOrNull()
            if (original != null) {
                append("Datum des angehängten Schreibens: ").append(original.docDate ?: "unbekannt").append("\n")
                append("Empfänger (aus dem Originalschreiben): ").append(original.recipient?.let { "\n$it" } ?: "unbekannt").append("\n")
                original.refNo?.let { append("Zeichen des Originalschreibens: ").append(it).append("\n") }
                original.subject?.let { append("Betreff des Originalschreibens: ").append(it).append("\n") }
                original.analysis?.let { de.foldki.app.export.LetterAnalysis.parse(it) }?.let { r ->
                    if (r.deadline.isNotBlank()) append("Frist im Originalschreiben: ").append(r.deadline).append("\n")
                    if (r.amount.isNotBlank()) append("Betrag im Originalschreiben: ").append(r.amount).append("\n")
                    if (r.contact.isNotBlank()) append("Ansprechpartner: ").append(r.contact).append("\n")
                    if (r.note.isNotBlank()) append("Handschriftliche Notiz des Nutzers auf dem Schreiben: ").append(r.note).append("\n")
                }
            }
            // Sender, recipient, date and signature are set by the app (LetterAnalysis.assemble), not by the model.
        }
    }
```

## 19. GitHub-Agent: vorangestellte Persona (Basis-Systemanweisung)

- Datei: `app/src/main/java/de/foldki/app/ai/LocalGitHubAgent.kt`, Zeilen 22–22 (bei `263c08e`)
- Grund: Die Systemanweisung des Nutzers (früher: der Standardtext) wurde immer vorangestellt. Jetzt nur noch, wenn der Nutzer selbst eine eingetragen hat. Das Werkzeug-Protokoll bleibt, siehe Liste der verbleibenden internen Prompts.

```kotlin
            append(base.trim().take(600)).append("\n\n")
```

## 20. LlamaEngine.reply: Beschriftungen mit Anweisungen im System-Prompt

- Datei: `app/src/main/java/de/foldki/app/ai/LlamaEngine.kt`, Zeilen 107–114 (bei `263c08e`)
- Grund: Dateien und Websuche kamen mit Sätzen wie „nutze sie, nenne die Quelle“ in den System-Prompt. Jetzt nur noch als Datenblock vor der Frage mit minimaler Beschriftung („Angehängte Datei <Name>:“, „Internetsuche:“).

```kotlin
        val sys = buildString {
            append(system.trim())
            val docs = recentDocs(history)
            if (docs.isNotBlank()) append("\n\nInhalt der angehängten Dateien:\n").append(docs)
            val noText = history.flatMap { it.attachments }.filter { it.text.isBlank() }
            if (noText.isNotEmpty()) append("\n\n(Aus ${noText.joinToString { it.name }} konnte kein Text gelesen werden.)")
            if (web.isNotBlank()) append("\n\nErgebnisse einer Internetsuche (nutze sie, nenne die Quelle):\n").append(web.take(WEB_CHARS))
        }
```

## 21. LlamaEngine.reply: precise-Schalter nach Marker-Text

- Datei: `app/src/main/java/de/foldki/app/ai/LlamaEngine.kt`, Zeilen 127–128 (bei `263c08e`)
- Grund: Hat nach den entfernten Regeltexten im System-Prompt gesucht („---DOKUMENT---“, „BRIEFDATUM:“). Ohne diese Texte gibt es nichts mehr zu erkennen. Dazu wurde `think = true` immer gesetzt, jetzt automatisch (nur bei Dateien, langen Fragen, Code/Mathe).

```kotlin
        val precise = system.contains("---DOKUMENT---") || system.contains("BRIEFDATUM:")
        generate(all, MAX_ANSWER_TOKENS, think = true, precise = precise, onThinkOpen = onThinkOpen).collect { emit(it) }
```

## 22. Einstellungen: alte Zeile „Systemanweisung“ und Seite mit Zurücksetzen-Button

- Datei: `app/src/main/java/de/foldki/app/ui/SettingsScreen.kt`, Zeilen 222–223 und 350–358 (bei `263c08e`)
- Grund: Der Standardtext ist weg, deshalb entfällt „Standard“/„Auf Standard zurücksetzen“ (setzte `DEFAULT_SYSTEM`). Die Zeile selbst steht wieder an derselben Stelle, jetzt als „Anweisungen an die KI“ mit Dialog statt eigener Seite.

```kotlin
        SettingRow("Systemanweisung", if (system.trim() == Settings.DEFAULT_SYSTEM.trim()) "Standard" else "Eigene Anweisung",
            leading = { Tile(Icons.Default.Edit, FK.T6) }, onClick = { open("system") })
// … (Zeilen 350–358:)
@Composable
private fun SystemPage(system: String, setSystem: (String) -> Unit) {
    Group {
        FkField("Anweisung an die KI", system, setSystem, minLines = 8)
        FkButton("Auf Standard zurücksetzen", Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), ghost = true) {
            setSystem(Settings.DEFAULT_SYSTEM)
        }
    }
}
```

## Hinweise zum neuen Verhalten

- System-Nachricht an das Modell = genau der Text aus „Anweisungen an die KI“ (`Settings.systemPrompt`). Leer = keine Systemnachricht.
- Angehängte Dateien und Internetsuche stehen als Datenblock vor der Frage in der Nutzer-Nachricht („Angehängte Datei <Name>:“, „Internetsuche:“). Das ist Inhalt, keine Regel.
- **Gedächtnis (ab Version nach 1.0.1):** Bearbeitbare Einträge unter Einstellungen → „Gedächtnis“ (Text, Kategorie Schreibstil / Rätsel & Logik / Persönlich / Sonstiges, Schalter an/aus, Herkunft „automatisch“, Bearbeiten, Löschen mit Rückfrage, „+ Eintrag“, Export/Import als JSON) und „Merken“ unter jeder Antwort. Nur **aktive** Einträge gehen ans Modell, höchstens ca. 1500 Zeichen; Einträge, die Wörter mit der aktuellen Frage teilen, zuerst (Korrektur-Einträge leicht vorn), dann die neuesten. Der einzige feste Text dabei ist die eine Überschriftszeile `MemoryStore.HEADING` = „Gespeicherte Einträge des Nutzers:“, danach „- Eintrag“-Zeilen. Der Block steht hinter der eigenen Anweisung des Nutzers; ist beides leer, gibt es keine Systemnachricht. Nie der Verlauf anderer Chats.
- **Automatisch lernen (Standard: an, abschaltbar auf der Gedächtnis-Seite):** „Merk dir …“ und Aussagen über den Nutzer („ich heiße …“) werden ohne Rückfrage als aktive Einträge „Persönlich“ gespeichert, höchstens 2 pro Nachricht. Der Hinweis „Gemerkt: …“ ist nur eine Information. Alles bleibt bearbeitbar, ausschaltbar, löschbar.
- **Fehlerkorrektur:** Sagt der Nutzer, die letzte Antwort sei falsch („falsch“, „stimmt nicht“, „nein, richtig ist …“, `Corrections.isSignal`), bekommt nur dieser Zug eine einzige technische Zeile vor seiner Nachricht (`Corrections.HINT`: „Hinweis: Die vorherige Antwort wurde als falsch markiert und darf nicht wiederholt werden.“) und das Nachdenken wird eingeschaltet. Nach der neuen Antwort läuft im Hintergrund (ohne Nachdenken, wird bei einer neuen Nachricht abgebrochen) eine kurze Extraktion (`Corrections.extractionPrompt`), die einen Eintrag der Kategorie „Rätsel & Logik“ erzeugt: „Bei der Frage „…“ ist … falsch; richtig ist … (…).“ Widerspricht der Nutzer danach erneut, wird dieser Eintrag wieder gelöscht. Doppelte Einträge werden nicht angelegt.
- **Denk-Budget:** Im Denkmodus beendet die native Bibliothek das Nachdenken nach 1200 Tokens selbst (`</think>` wird eingefügt), damit die Antwort nicht Minuten braucht.
- Alle internen Prompt-Texte stehen wörtlich in `INTERNAL_PROMPTS.md`.
- Denken (`think`) wird automatisch gesetzt: nur bei Dateien, Fragen über 200 Zeichen oder Code/Mathe (`Prompts.wantsThinking`).
- Bleibende interne Aufgaben-Prompts (nicht der normale Chat): Briefanalyse (`LetterAnalysis.ANALYZE`), Suchbegriffe (`Prompts.searchTermsPrompt`), Tagesstart (`Prompts.BRIEF_SYSTEM`), GitHub-Werkzeugprotokoll (`LocalGitHubAgent.system`), sowie die Texte der Schnellknöpfe im Chat (`P_REPLY`, `P_OBJECT`, `P_SUMMARY`, „Schreib ein Antwortschreiben …“, die Nutzer-Nachricht „Was steht in diesem Dokument? …“).

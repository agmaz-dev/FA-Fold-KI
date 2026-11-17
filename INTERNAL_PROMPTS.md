# Interne Prompts (Stand FA Fold KI nach Gedächtnis-Update)

Alle Texte, die die App von sich aus an das Modell schickt, ohne dass der Nutzer sie tippt. Im normalen Chat geht nur an das Modell: die Eingabe des Nutzers, seine „Anweisungen an die KI“ und seine **aktiven** Gedächtnis-Einträge (eine feste Überschriftszeile, siehe 1). Alles andere sind technische Aufgaben, die nur laufen, wenn die jeweilige Funktion benutzt wird. Wortlaut jeweils aus dem Quelltext; die Zeilen gelten für diesen Commit.

## 1. Gedächtnis-Überschrift (einziger fester Text im normalen Chat)

- Wann: jede Anfrage, nur wenn mindestens ein aktiver Eintrag existiert; darunter „- Eintrag“-Zeilen des Nutzers
- Quelle: `app/src/main/java/de/foldki/app/data/MemoryStore.kt`, Zeilen 193–193

```kotlin
        const val HEADING = "Gespeicherte Einträge des Nutzers:"
```

## 2. Korrektur-Hinweis (eine Zeile)

- Wann: nur in dem Zug, in dem die Nachricht des Nutzers als „Antwort war falsch“ erkannt wird; steht vor seiner Nachricht, dazu wird das Nachdenken eingeschaltet
- Quelle: `app/src/main/java/de/foldki/app/data/Corrections.kt`, Zeilen 10–10

```kotlin
    const val HINT = "Hinweis: Die vorherige Antwort wurde als falsch markiert und darf nicht wiederholt werden."
```

## 3. Korrektur-Extraktion (Hintergrund, ohne Nachdenken, max. 200 Tokens)

- Wann: nach der neuen Antwort in einem Korrektur-Zug, nur bei eingeschaltetem „Automatisch lernen“; wird abgebrochen, sobald eine neue Nachricht gesendet wird
- Quelle: `app/src/main/java/de/foldki/app/data/Corrections.kt`, Zeilen 54–61

```kotlin
    fun extractionPrompt(question: String, wrong: String, userMessage: String, newAnswer: String): String = buildString {
        append("Der Nutzer hat eine Antwort als falsch korrigiert. Schreibe genau einen Satz in dieser Form:\n")
        append("Bei der Frage „<kurze Frage>“ ist <falsche Antwort> falsch; richtig ist <richtige Antwort> (<kurzer Grund>).\n")
        append("Nutze nur die Angaben unten und erfinde nichts. Geht die richtige Antwort daraus nicht hervor, schreibe nur: KEINE\n\n")
        append("Frage: ").append(clip(question, 200)).append("\n")
        append("Falsche Antwort: ").append(clip(wrong, 300)).append("\n")
        append("Korrektur des Nutzers: ").append(clip(userMessage, 300))
        if (newAnswer.isNotBlank()) append("\nNeue Antwort: ").append(clip(newAnswer, 300))
```

## 4. Tagesstart

- Wann: wenn der Tagesstart eingeschaltet ist (morgens, einmal am Tag)
- Quelle: `app/src/main/java/de/foldki/app/ai/Prompts.kt`, Zeilen 5–7

```kotlin
    const val BRIEF_SYSTEM = """Du schreibst einen kurzen Tagesstart auf Deutsch für den Nutzer (duzen).
Fasse nur die folgenden Fakten in 2 bis 4 kurzen Sätzen zusammen: was heute wichtig ist, welche Termine anstehen, wer geschrieben hat, was offen ist.
Erfinde nichts, keine Listen, keine Begrüßungsfloskeln, kein Markdown."""
```

## 5. Suchbegriffe

- Wann: nur bei eingeschaltetem „Web“-Knopf, vor der Internetsuche
- Quelle: `app/src/main/java/de/foldki/app/ai/Prompts.kt`, Zeilen 9–10

```kotlin
    fun searchTermsPrompt(question: String) =
        "Gib nur 2 bis 5 Suchbegriffe (ohne Erklärung, eine Zeile) für eine Internetsuche zu dieser Frage aus:\n$question"
```

## 6. Datenblock vor der Nutzer-Nachricht (Beschriftungen)

- Wann: Dateien angehängt / Web an
- Quelle: `app/src/main/java/de/foldki/app/ai/Prompts.kt`, Zeilen 31–41

```kotlin
    /**
     * Data in front of the question in the user turn: file text ("Angehängte Datei <Name>:"), "Internetsuche:" and, if the
     * user marked the previous answer as wrong, [de.foldki.app.data.Corrections.HINT]. Contains data only, no rules.
     */
    fun userData(docs: String, noTextNames: List<String>, web: String, correction: Boolean): String = buildString {
        if (correction) append(de.foldki.app.data.Corrections.HINT).append("\n\n")
        if (docs.isNotBlank()) append(docs.trim()).append("\n\n")
        for (n in noTextNames) append("Angehängte Datei ").append(n).append(": (kein Text lesbar)\n\n")
        if (web.isNotBlank()) append("Internetsuche:\n").append(web.trim()).append("\n\n")
    }
}
```

## 7. Briefanalyse (ANALYZE)

- Wann: einmal pro neu angehängtem Schreiben (Foto/PDF mit Text)
- Quelle: `app/src/main/java/de/foldki/app/export/LetterAnalysis.kt`, Zeilen 24–41

```kotlin
    const val ANALYZE = "Du liest ein Schreiben, das der Nutzer erhalten hat. Antworte auf Deutsch, OHNE Einleitung, " +
        "genau in diesen Zeilen (jede Angabe in einer Zeile, nichts erfinden, fehlt etwas: \"-\"):\n" +
        "KURZ: 2 Sätze – wer schreibt, worum geht es, was wird verlangt\n" +
        "BRIEFDATUM: Datum des Schreibens als TT.MM.JJJJ\n" +
        "NOTIZ: handschriftliche Notiz auf dem Brief (z. B. „Bezahlt 21.10.26“), sonst -\n" +
        "FRIST: Frist oder Termin (in Worten)\n" +
        "FRISTDATUM: letzter Tag der Frist als TT.MM.JJJJ, sonst -\n" +
        "BETRAG: wichtigster Betrag\n" +
        "ABSENDER: Postanschrift des Absenders, Teile mit | getrennt (Firma | Straße Nr. | PLZ Ort)\n" +
        "EMAIL: E-Mail des Ansprechpartners\n" +
        "BEZUG: Aktenzeichen/Kunden-/Betriebsnummer und Datum des Schreibens\n" +
        "ANSPRECHPARTNER: Name\n" +
        "VORSCHLAG: kurze Antwortmöglichkeit aus Sicht des Empfängers (höchstens 10 Wörter)\n" +
        "VORSCHLAG: zweite Möglichkeit\n" +
        "VORSCHLAG: dritte Möglichkeit\n" +
        "Handschriftliche Notizen im Schreiben (z. B. „Bezahlt am …“) sind Wissen des Empfängers und gehören in die Vorschläge."

    val DEFAULT_SUGGESTIONS = listOf("Zustimmen und bestätigen", "Widersprechen", "Um Fristverlängerung bitten", "Rückfrage stellen")
```

## 8. Briefanalyse: Auftragszeile

- Wann: zusammen mit ANALYZE
- Quelle: `app/src/main/java/de/foldki/app/ChatViewModel.kt`, Zeilen 824–824

```kotlin
            llm.analyze(de.foldki.app.export.LetterAnalysis.ANALYZE, "Lies das angehängte Schreiben und antworte im verlangten Zeilenformat.", listOf(a))
```

## 9. Standardfrage bei Datei ohne Text

- Wann: wenn der Nutzer nur eine Datei sendet und nichts tippt
- Quelle: `app/src/main/java/de/foldki/app/ChatViewModel.kt`, Zeilen 593–593

```kotlin
        val prompt = text.trim().ifEmpty { if (pendingFiles.isNotEmpty()) "Was steht in diesem Dokument? Fasse es kurz zusammen." else "" }
```

## 10. Schnellknöpfe (Nutzer-Nachricht auf Tipp)

- Wann: nur wenn der Nutzer den Knopf antippt; „Zusammenfassen“, „Antwortschreiben“, „Widerspruch“
- Quelle: `app/src/main/java/de/foldki/app/ui/ChatApp.kt`, Zeilen 441–444

```kotlin
private const val P_REPLY = "Schreibe ein formelles Antwortschreiben (Gegenschreiben) auf das angehängte Schreiben. Gehe auf alle Punkte ein."
private const val P_OBJECT = "Schreibe einen formellen, fristgerechten Widerspruch gegen das angehängte Schreiben mit sachlicher Begründung."
private const val P_SUMMARY = "Fasse das angehängte Dokument verständlich zusammen: Worum geht es, was wird von mir verlangt, welche Fristen gibt es?"
private const val P_GITHUB = "Auf GitHub: "
```

## 11. Brief-Vorschlag-Chips

- Wann: nur wenn der Nutzer einen Vorschlag antippt
- Quelle: `app/src/main/java/de/foldki/app/ui/ChatApp.kt`, Zeilen 936–936

```kotlin
            else -> suggestions.map { sug -> sug to { vm.send("Schreib ein Antwortschreiben auf das Schreiben: $sug" + extra(text)); setText("") } }
```

## 12. GitHub-Agent: Werkzeugprotokoll (System-Nachricht)

- Wann: nur bei GitHub-Aufgaben mit eingetragenem Token; danach folgt die Werkzeugliste (Name, Parameter, Beschreibung aus `GitHubTools.SPECS`). Die eigene Anweisung des Nutzers steht davor, wenn er eine hat
- Quelle: `app/src/main/java/de/foldki/app/ai/LocalGitHubAgent.kt`, Zeilen 21–35

```kotlin
        fun system(base: String): String = buildString {
            if (base.isNotBlank()) append(base.trim().take(600)).append("\n\n")
            append(
                """
Du hast Zugang zum GitHub-Konto des Nutzers über Werkzeuge. So benutzt du ein Werkzeug:
Schreibe eine eigene Zeile, die mit WERKZEUG: beginnt, danach ein JSON-Objekt mit "name" und den Angaben, z. B.
WERKZEUG: {"name":"list_my_repositories"}
WERKZEUG: {"name":"read_file","owner":"agmaz-dev","repo":"Test","path":"README.md"}
Danach hörst du auf zu schreiben. Du bekommst das ERGEBNIS und machst dann weiter. Immer nur ein Werkzeug auf einmal.
Wenn du fertig bist, antworte ohne WERKZEUG-Zeile mit einer kurzen Zusammenfassung auf Deutsch.
Änderungen (Anlegen, Schreiben, Mergen, Workflow starten) zeigt die App dem Nutzer vorher zur Freigabe.
Behaupte nie, etwas sei erledigt, ohne dass ein ERGEBNIS es bestätigt. Erfinde keine Dateiinhalte.
Werkzeuge:
""".trimIndent(),
            )
```

## Technisch, kein Text an das Modell

- **Denk-Budget:** Im Denkmodus fügt `foldki_llm.cpp` nach `LlamaEngine.THINK_BUDGET` = 1200 Tokens selbst `\n</think>\n\n` ein (Kontext und Ausgabe), damit danach die Antwort kommt. Das sind Steuerzeichen der Chat-Vorlage, kein Prompt.
- **Denken automatisch:** `Prompts.wantsThinking` (Dateien, Frage über 200 Zeichen, Code/Mathe) oder Korrektur-Zug.

# Hinweise für Claude

## Stimmen (gilt für alle Apps und Programme dieses Nutzers)
- Sprachausgabe immer mit **Supertonic 3** und genau **zwei Stimmen: Tessa (weiblich, Stil `F5`) und Caleb (männlich, Stil `M2`)**. Keine weiteren Stimmen (z. B. nicht Elena).
- Modell: `https://huggingface.co/supertone-oss-archive/supertonic-3`, Revision `aafc6e32416a594460b32413efc49d7fe4ce6d46`, einmaliger Download (~250 MB), läuft offline per ONNX Runtime.
- Referenz-Umsetzungen: `agmaz-dev/FA-KI-Desktop` → `lib/supertonic.js` (Node/Electron), `agmaz-dev/FA-KI-FOLD7` → `app/src/main/java/de/foldki/app/voice/Supertonic.kt` (Android/Kotlin).
- Standardstimme: Tessa. **Keine Ausweichstimmen** (keine Android-/Windows-/System-Stimmen, auch nicht als Fallback). Ist Supertonic noch nicht eingerichtet, wird der Nutzer zur Einrichtung geschickt.

## Spracheingabe
- Wie bei ChatGPT/Grok: **eigene Mikrofonaufnahme + Whisper auf dem Gerät**, keine System-Spracherkennung (kein Android-SpeechRecognizer, kein Google-Sprachfenster).
- Android: `Xenova/whisper-small` (int8, ONNX Runtime) → `app/src/main/java/de/foldki/app/voice/WhisperStt.kt` in FA-KI-FOLD7. Desktop: whisper.cpp (`lib/speech.js` in FA-KI-Desktop).

## Zielgerät
- Zielgerät: Samsung Galaxy Z Fold 7. Jede Layout-Änderung und jeder Test gilt für beide Zustände: zugeklappt (schmales Außendisplay) und aufgeklappt (großes Display). Eingabefelder, Knöpfe und Abstände müssen auf dem Außendisplay funktionieren.

## Allgemein
- Antworten und App-Texte auf Deutsch.

## On-Device-Funktionen (ab 2.0)
- **Gemini Nano ist immer Standard und Ersatz.** Gemma 4 E2B oder E4B (`ai/GemmaEngine.kt`, `ai/LocalModelManager.kt`, LiteRT-LM) ist optional und wählbar: einmaliger Download (E2B ~2,6 GB fest gepinnt; E4B ~3,7 GB, Größe/SHA-256 vor dem Download aus der Hugging-Face-API, dann geprüft), danach offline. Bei Fehler/Timeout/Hitze/Absturz → automatisch Nano. Keine weiteren Netzwerkzugriffe einbauen.
- Tagesstart (`brief/`): Kalender, aktive Benachrichtigungen, offene Punkte – nur lokal mit Nano, nie an Claude (`ChatMessage.localOnly`).
- Briefe lesen wie FA-PDF: `export/LetterAnalysis.kt` (KI liest das Schreiben einmal: KURZ/BRIEFDATUM/FRIST/BETRAG/ABSENDER/BEZUG/ANSPRECHPARTNER/VORSCHLAG, ergänzt LetterMeta; Platzhalter füllen, Ende ergänzen, Antwort-Vorschläge, Absenderprofil pro Empfänger). Gemma bekommt ganze Dokumente (`NanoEngine.buildPrompt(large = true)`).
- Briefe: `export/LetterMeta.kt` (Datum/Empfänger/Zeichen/Betreff aus Seite 1), Absenderprofile `data/SenderProfile.kt` + Unterschrift `data/SignatureStore.kt`, PDF-Vorschau `ui/PdfPreview.kt` (öffnet sich nach jedem Brief).
- Einstellungen = aufklappbare Karten (`ui/SettingsScreen.kt`). Aufgeklappt: Trennwand-Knopf oben links in der Chat-Leiste (`SplitPaneState.toggleFirst`).
- Tests: `./gradlew testReleaseUnitTest` (LetterMeta, DocumentParser, SenderProfile, TaskExtractor).

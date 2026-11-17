# Hinweise für Claude

> **Dieses Repo ist FA Fold KI (`agmaz-dev/FA-Fold-KI`, App-ID `de.foldki.fa`, Version 1.0)** – eine Kopie von Fold KI 3.1
> (`agmaz-dev/FA-KI-FOLD7`, bleibt unverändert). Einziger Unterschied: Die KI ist **nur ein lokales GGUF-Modell über llama.cpp**.
> Optik und alle anderen Bereiche sind 1:1 wie im Original.

## FA Fold KI: lokale KI (llama.cpp, GGUF)
- **Kein Gemini Nano, kein Gemma/LiteRT-LM, kein Claude** – entfernt (Code, Abhängigkeiten `genai-prompt`, `litertlm-android`, `anthropic-java`, Modellwahl, Einstellungen). Nicht wieder einbauen.
- **Keine vorgefertigten Prompts (ab 1.0.1):** Im normalen Chat geht nur an das Modell, was der Nutzer tippt, plus seine eigene „Anweisungen an die KI“ (Einstellungen → „Anweisungen an die KI“, `Settings.systemPrompt`, standardmäßig leer; leer = keine Systemnachricht). Keine Persona, keine Regeltexte, kein Datum, kein Gedächtnis-Block. Entfernte Texte: `REMOVED_PROMPTS.md`. Nicht wieder einbauen. Interne Aufgaben-Prompts (Briefanalyse, Suchbegriffe, Tagesstart, GitHub-Werkzeugprotokoll) bleiben, weil die Funktionen sie brauchen.
- Native: `app/src/main/cpp/foldki_llm.cpp` + `CMakeLists.txt` (aus Jarvis-Android, llama.cpp fest auf Tag **b12287** per FetchContent, arm64 dotprod/i8mm/KleidiAI, `c++_static`, 16-KB-Seiten). JNI: `ai/LlamaNative.kt`.
- `ai/LlamaEngine.kt`: Kontext **8192** (Ersatz 4096/2048 bei wenig RAM), Antwort bis **4000** Tokens, Chat-Vorlage (Jinja) aus der GGUF-Datei. Interne Aufgaben (Suchbegriffe, Brief lesen, Tagesstart) ohne Nachdenken (leerer `<think></think>`-Block), Chat und GitHub mit Nachdenken.
- `ai/ThinkParser.kt`: Text bis `</think>` = „Gedanken“ (ausklappbar über der Antwort, `ChatMessage.thoughts`), nie in PDF/Export/Vorlesen/Sprachmodus. Kein `</think>` = alles ist Antwort.
- Modelle: `ai/LocalModelManager.kt` (DownloadManager: jede Verbindung inkl. Mobilfunk, Fortsetzen, Größe + SHA-256 prüfen; Import eigener .gguf; aktives Modell; Löschen inkl. Teil-Dateien), `ai/HfModels.kt` (Vorschlag Qwen3 4B Thinking Q4_K_M von TeichAI im Jarvis-`MODELS`-Format, HF-API, Q4_K_M-Empfehlung, Split-Dateien werden abgelehnt, RAM-Warnung), `ui/ModelSettings.kt` + `ui/HfBrowser.kt` (Mini-Browser huggingface.co/models?library=gguf).
- Ohne Modell: Hinweis im Chat/Startbildschirm, Modell herunterzuladen oder zu importieren.
- Netzwerk: nur Hugging Face (Browser + Download), GitHub-Werkzeuge und die vorhandene Websuche.

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

## Zoom (gilt für jede Zoom-Funktion)
- Zoomen immer dort, wo die Finger sind (Mittelpunkt zwischen den zwei Fingern), nicht nur in die Bildmitte. Gezoomt muss man überall hin schieben können (links, rechts, oben, unten). Doppeltipp zoomt an der getippten Stelle hinein bzw. wieder heraus. Referenz: `ui/PdfPreview.kt`.

## Design (ab 3.0)
- Claude wird nicht angeboten. In FA Fold KI zeigt die Modellwahl nur die heruntergeladenen GGUF-Modelle.
- Immer das dunkle Fold-KI-Design: Farben/Bausteine in `ui/Theme.kt` (`FK`) und `ui/Design.kt` (Tile, Group, SettingRow, Tag, FkToggle, FkChip, FkButton), Schrift Inter (`res/font`). Neue Bildschirme nur mit diesen Bausteinen bauen, damit alles gleich aussieht.

## Allgemein
- Antworten und App-Texte auf Deutsch.

## On-Device-Funktionen
- Einzige KI: das gewählte GGUF-Modell (`ai/LlamaEngine.kt`). Bei Absturz beim Laden wird das Modell gesperrt, bis der Nutzer es wieder auswählt (`Settings.llmCrashed`).
- Tagesstart (`brief/`): Kalender, aktive Benachrichtigungen, offene Punkte – nur lokal mit dem lokalen Modell (`ChatMessage.localOnly`).
- Briefe lesen wie FA-PDF: `export/LetterAnalysis.kt` (KI liest das Schreiben einmal: KURZ/BRIEFDATUM/FRIST/BETRAG/ABSENDER/BEZUG/ANSPRECHPARTNER/VORSCHLAG, ergänzt LetterMeta; Platzhalter füllen, Ende ergänzen, Antwort-Vorschläge, Absenderprofil pro Empfänger). Das lokale Modell bekommt bis zu 8000 Zeichen Dokumenttext (`LlamaEngine.DOC_CHARS`).
- Briefe: `export/LetterMeta.kt` (Datum/Empfänger/Zeichen/Betreff aus Seite 1), Absenderprofile `data/SenderProfile.kt` + Unterschrift `data/SignatureStore.kt`, PDF-Vorschau `ui/PdfPreview.kt` (öffnet sich nach jedem Brief).
- Einstellungen = aufklappbare Karten (`ui/SettingsScreen.kt`). Aufgeklappt: Trennwand-Knopf oben links in der Chat-Leiste (`SplitPaneState.toggleFirst`).
- GitHub: **nur das lokale Modell auf dem Handy** mit Werkzeugen (`ai/LocalGitHubAgent.kt`: Zeile `WERKZEUG: {json}`, `github/GitHubTools.kt`, `github/GitHubApi.kt`), nur GitHub-Token nötig. **Kein Claude, kein API-Schlüssel** – der Nutzer will Claude nicht in seiner App. Lesen sofort, **jede Änderung nur nach Freigabe** (Dialog `UiState.approval`).
- Tests: `./gradlew testReleaseUnitTest` (LetterMeta, DocumentParser, SenderProfile, TaskExtractor, ThinkParser, HfModels, LocalGitHubAgent).

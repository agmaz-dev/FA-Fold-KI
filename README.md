# FA Fold KI – Fold KI mit lokalem llama.cpp (GGUF)

Kopie von **Fold KI 3.1** (`agmaz-dev/FA-KI-FOLD7`, unverändert) mit genau einem Unterschied: Die KI ist ein
**lokales GGUF-Modell über llama.cpp** auf dem Handy (optimiert für das Galaxy Z Fold 7). Kein Gemini Nano, kein Gemma, kein Claude.
Installiert sich neben dem Original (App-ID `de.foldki.fa`, Name „FA Fold KI“, Version 1.0).

- **Modelle:** Einstellungen → KI-Modelle. Vorschlag mit einem Tipp: *Qwen3 4B Thinking (Opus-Destillat)*, Q4_K_M, 2,50 GB von
  [TeichAI auf Hugging Face](https://huggingface.co/TeichAI/Qwen3-4B-Thinking-2507-Claude-4.5-Opus-High-Reasoning-Distill-GGUF).
  Außerdem: eingebauter Hugging-Face-Browser für GGUF-Modelle (Q4_K_M wird vorgeschlagen) und Import eigener `.gguf`-Dateien.
  Download über WLAN oder Mobilfunk, mit Fortsetzen, Größe und SHA-256 werden geprüft. Löschen mit Bestätigung.
- **Gedanken:** Thinking-Modelle schreiben erst ihre Überlegungen (bis `</think>`). Die App zeigt sie eingeklappt als „Gedanken“ über der Antwort,
  nie in PDFs, beim Teilen oder Vorlesen.
- **Kontext** 8192 Tokens, Antwort bis 4000 Tokens (bei wenig Arbeitsspeicher automatisch kleiner).
- Alles andere wie in Fold KI: Briefe & PDF, Anhänge (Texterkennung offline), Websuche-Knopf, Sprache (Whisper + Supertonic), Tagesstart, GitHub mit Freigabe,
  Split-Ansicht aufgeklappt.

## APK bekommen
Jeder Push baut automatisch die APK (GitHub Actions) und veröffentlicht sie unter **Releases → „FA Fold KI 1.0 (Build N)“ → `FA-Fold-KI-1.0.apk`**.

## Selbst bauen
```
./gradlew assembleRelease
```
Braucht Android SDK mit NDK und CMake 3.22.1; llama.cpp (Tag b12287, MIT) wird beim Bauen geladen.

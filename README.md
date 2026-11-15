# Fold KI – Android-Chat mit Gemini Nano + Claude

Chat-App für Android (optimiert für das Galaxy Z Fold7):

- **Gemini Nano (offline)** – läuft direkt auf dem Gerät über Android AICore / ML Kit GenAI.
- **Claude (online)** – Opus 5.5, Sonnet 5.5 oder Haiku 5.5 über deinen eigenen API-Schlüssel.
- **+ Anhänge:** Fotos (Galerie/Kamera) und PDFs. Text wird offline gelesen (PDF-Text + Texterkennung), damit auch Gemini Nano sie versteht; Claude bekommt die Originaldateien.
- **Briefe & PDF:** Schnellaktionen „Antwortschreiben“, „Widerspruch“, „Zusammenfassen“, „Brief schreiben“; jede Antwort als PDF speichern oder teilen.
- **🌐 Websuche (Globus-Knopf):** Claude sucht selbst im Internet; Gemini Nano bekommt Wikipedia-Artikel und aktuelle Schlagzeilen (Google News) dazu.
- **🎙️ Sprache:** Sprachmodus (Knopf rechts bei leerem Feld) wie in der Claude-App, Mikrofon zum Diktieren, Vorlesen jeder Antwort.
- Chat-Verlauf, Seitenleiste (aufgeklappt dauerhaft sichtbar), hell/dunkel.

## APK bekommen
Jeder Push baut automatisch die APK (GitHub Actions). Herunterladen unter
**Releases → neueste „Fold KI APK“ → `FoldKI.apk`**, auf dem Handy öffnen und
„Installation aus unbekannten Quellen“ erlauben.

## Gemini Nano einrichten
Gemini Nano muss auf dem Gerät verfügbar sein (AICore). Die App zeigt den Status an
und bietet bei Bedarf den Download des Modells an.

## Selbst bauen
```
./gradlew assembleRelease
```

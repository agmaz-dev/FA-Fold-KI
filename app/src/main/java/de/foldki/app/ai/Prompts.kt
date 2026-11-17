package de.foldki.app.ai

/** Fixed prompts and small helpers for the local model (formerly in NanoEngine). */
object Prompts {
    const val BRIEF_SYSTEM = """Du schreibst einen kurzen Tagesstart auf Deutsch für den Nutzer (duzen).
Fasse nur die folgenden Fakten in 2 bis 4 kurzen Sätzen zusammen: was heute wichtig ist, welche Termine anstehen, wer geschrieben hat, was offen ist.
Erfinde nichts, keine Listen, keine Begrüßungsfloskeln, kein Markdown."""

    fun searchTermsPrompt(question: String) =
        "Gib nur 2 bis 5 Suchbegriffe (ohne Erklärung, eine Zeile) für eine Internetsuche zu dieser Frage aus:\n$question"

    fun firstLineTerms(answer: String): String =
        answer.lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '.', '*')?.take(100).orEmpty()
}

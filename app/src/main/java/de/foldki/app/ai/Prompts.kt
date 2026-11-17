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

    private val CODE_MATH = Regex(
        "```|#include|</?\\w+>|\\b(def|fun|function|class|import|return|SELECT)\\b.*[(){}:;=]|[{};]\\s*$|=>|->|==|!=|&&|\\|\\||" +
            "\\d\\s*[+*/^=×÷]\\s*\\d|[√∑∫π]|" +
            "\\b(code|quellcode|programmier\\w*|algorithmus|funktion|skript|script|regex|sql|python|kotlin|java(script)?|" +
            "berechne\\w*|rechne\\w*|gleichung|beweis\\w*|ableitung|integral|wurzel|wahrscheinlichkeit|prozent\\w*)\\b",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    /** Thinking only when it pays off: attachments, a long question, or code/math. Short questions get a direct answer. */
    fun wantsThinking(question: String, hasAttachments: Boolean): Boolean =
        hasAttachments || question.length > 200 || CODE_MATH.containsMatchIn(question)

    /** Messages for a chat answer: no system message when [system] is blank; [data] (files, web) goes in front of the question. */
    fun chatMessages(system: String, earlier: List<Pair<String, String>>, data: String, question: String): List<Pair<String, String>> =
        (if (system.isBlank()) emptyList() else listOf("system" to system)) + earlier + ("user" to (data + question))
}

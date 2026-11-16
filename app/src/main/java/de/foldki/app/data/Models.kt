package de.foldki.app.data

import java.util.UUID

enum class Role { USER, ASSISTANT }

/** [GEMMA] is never selected directly: it answers instead of Nano when "Gemma verwenden" is on (label of the answer). */
enum class Engine(val label: String) { NANO("Gemini Nano (offline)"), CLAUDE("Claude (online)"), GEMMA("Gemma 4 (offline)") }

/** A photo or PDF copied into the app's private storage. [text] is extracted text (PDF text / OCR). */
data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mime: String,
    val path: String,
    val text: String = "",
    /** Read from the first page of an attached letter (never from PDF metadata or the file name). */
    val docDate: String? = null,
    /** Sender of the original letter = recipient of a reply (multi-line). */
    val recipient: String? = null,
    val refNo: String? = null,
    val subject: String? = null,
    /** FA-PDF style reading of the letter by the AI (KURZ:/BRIEFDATUM:/ABSENDER:/VORSCHLAG: … lines), once per file. */
    val analysis: String? = null,
) {
    val isImage get() = mime.startsWith("image/")
    val isPdf get() = mime == "application/pdf"
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val engine: Engine? = null,
    val isError: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val web: Boolean = false,
    /** Data that must stay on the device (daily briefing): never sent to Claude. */
    val localOnly: Boolean = false,
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Neuer Chat",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

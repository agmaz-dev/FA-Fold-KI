package de.foldki.app.data

import java.util.UUID

enum class Role { USER, ASSISTANT }

enum class Engine(val label: String) { NANO("Gemini Nano (offline)"), CLAUDE("Claude (online)") }

/** A photo or PDF copied into the app's private storage. [text] is extracted text (PDF text / OCR). */
data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mime: String,
    val path: String,
    val text: String = "",
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
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Neuer Chat",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

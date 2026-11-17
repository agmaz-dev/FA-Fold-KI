package de.foldki.app.data

import java.util.UUID

enum class Role { USER, ASSISTANT }

/** FA Fold KI has one AI: the local GGUF model via llama.cpp (no Gemini Nano, no Gemma, no cloud). */
enum class Engine(val label: String) { LOCAL("Lokales Modell (offline)") }

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
    /** Data that must stay on the device (daily briefing). */
    val localOnly: Boolean = false,
    /** Reasoning of a thinking model (text before </think>): shown collapsed as "Gedanken", never exported or spoken. */
    val thoughts: String = "",
    /** Name of the local model that wrote this answer. */
    val model: String? = null,
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Neuer Chat",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

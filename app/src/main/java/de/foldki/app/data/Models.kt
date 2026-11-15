package de.foldki.app.data

import java.util.UUID

enum class Role { USER, ASSISTANT }

enum class Engine(val label: String) { NANO("Gemini Nano (offline)"), CLAUDE("Claude (online)") }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val engine: Engine? = null,
    val isError: Boolean = false,
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Neuer Chat",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

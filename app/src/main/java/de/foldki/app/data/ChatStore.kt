package de.foldki.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves all conversations as one JSON file in the app's private storage. */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chats.json")

    fun load(): List<Conversation> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                val msgs = c.getJSONArray("messages")
                Conversation(
                    id = c.getString("id"),
                    title = c.optString("title", "Chat"),
                    updatedAt = c.optLong("updatedAt"),
                    messages = (0 until msgs.length()).map { j ->
                        val m = msgs.getJSONObject(j)
                        ChatMessage(
                            id = m.getString("id"),
                            role = Role.valueOf(m.getString("role")),
                            text = m.getString("text"),
                            engine = m.optString("engine").takeIf { it.isNotEmpty() }
                                ?.let { runCatching { Engine.valueOf(it) }.getOrNull() },
                            isError = m.optBoolean("isError"),
                        )
                    },
                )
            }.sortedByDescending { it.updatedAt }
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun save(conversations: List<Conversation>) {
        val arr = JSONArray()
        conversations.forEach { c ->
            val msgs = JSONArray()
            c.messages.forEach { m ->
                msgs.put(JSONObject().apply {
                    put("id", m.id); put("role", m.role.name); put("text", m.text)
                    put("engine", m.engine?.name ?: ""); put("isError", m.isError)
                })
            }
            arr.put(JSONObject().apply {
                put("id", c.id); put("title", c.title); put("updatedAt", c.updatedAt)
                put("messages", msgs)
            })
        }
        val tmp = File(file.parentFile, "chats.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}

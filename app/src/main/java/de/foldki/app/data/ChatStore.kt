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
                            web = m.optBoolean("web"),
                            attachments = m.optJSONArray("attachments")?.let { a ->
                                (0 until a.length()).map { k ->
                                    val x = a.getJSONObject(k)
                                    Attachment(
                                        id = x.getString("id"), name = x.getString("name"),
                                        mime = x.getString("mime"), path = x.getString("path"),
                                        text = x.optString("text"),
                                    )
                                }
                            } ?: emptyList(),
                        )
                    },
                )
            }.sortedByDescending { it.updatedAt }
        }
    } catch (e: Exception) {
        // Unreadable file: keep a copy instead of overwriting it with an empty list on the next save.
        runCatching { file.copyTo(File(file.parentFile, "chats.corrupt-${System.currentTimeMillis()}.json"), overwrite = false) }
        android.util.Log.e("FoldKI", "chats.json konnte nicht gelesen werden, Sicherung angelegt", e)
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
                    put("engine", m.engine?.name ?: ""); put("isError", m.isError); put("web", m.web)
                    put("attachments", JSONArray().apply {
                        m.attachments.forEach { a ->
                            put(JSONObject().apply {
                                put("id", a.id); put("name", a.name); put("mime", a.mime)
                                put("path", a.path); put("text", a.text)
                            })
                        }
                    })
                })
            }
            arr.put(JSONObject().apply {
                put("id", c.id); put("title", c.title); put("updatedAt", c.updatedAt)
                put("messages", msgs)
            })
        }
        val tmp = File(file.parentFile, "chats.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }
}

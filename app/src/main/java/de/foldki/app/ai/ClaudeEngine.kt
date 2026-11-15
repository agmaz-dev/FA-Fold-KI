package de.foldki.app.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import android.util.Base64
import java.io.File
import de.foldki.app.data.ChatMessage
import de.foldki.app.data.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Claude over the internet, via the official Anthropic Java SDK with streaming. */
class ClaudeEngine {
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    private fun client(apiKey: String): AnthropicClient {
        if (client == null || clientKey != apiKey) {
            client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
            clientKey = apiKey
        }
        return client!!
    }

    fun reply(apiKey: String, model: String, system: String, history: List<ChatMessage>): Flow<String> = flow {
        require(apiKey.isNotBlank()) { "Kein Claude-API-Schlüssel. Bitte in den Einstellungen eintragen." }

        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(64000L)
            .system(system)
            // If a safety classifier declines, let the server retry on a suitable fallback model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))

        // The API needs alternating turns starting with the user.
        val turns = history.filter { !it.isError && it.text.isNotBlank() }
            .dropWhile { it.role != Role.USER }
        turns.forEach { m ->
            when {
                m.role == Role.ASSISTANT -> builder.addAssistantMessage(m.text)
                m.attachments.isEmpty() -> builder.addUserMessage(m.text)
                else -> builder.addUserMessageOfContentBlockParams(userBlocks(m))
            }
        }

        var refused = false
        val pending = ArrayList<String>()
        client(apiKey).messages().createStreaming(builder.build()).use { stream ->
            val events = stream.stream().iterator()
            while (events.hasNext()) {
                val event = events.next()
                event.contentBlockDelta().ifPresent { d ->
                    d.delta().text().ifPresent { t -> pending.add(t.text()) }
                }
                event.messageDelta().ifPresent { md ->
                    md.delta().stopReason().ifPresent { sr ->
                        if (sr.toString().contains("refusal", ignoreCase = true)) refused = true
                    }
                }
                while (pending.isNotEmpty()) emit(pending.removeAt(0))
            }
        }
        if (refused) emit("\n\n(Claude hat diese Anfrage abgelehnt.)")
    }.flowOn(Dispatchers.IO)

    /** Photos as image blocks, PDFs as document blocks, then the user's text. */
    private fun userBlocks(m: ChatMessage): List<ContentBlockParam> {
        val blocks = mutableListOf<ContentBlockParam>()
        m.attachments.forEach { a ->
            val f = File(a.path)
            if (!f.exists()) return@forEach
            val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            if (a.isPdf) {
                blocks += ContentBlockParam.ofDocument(
                    DocumentBlockParam.builder()
                        .source(Base64PdfSource.builder().data(b64).build())
                        .title(a.name)
                        .build()
                )
            } else if (a.isImage) {
                blocks += ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                        .source(
                            Base64ImageSource.builder()
                                .data(b64)
                                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                .build()
                        )
                        .build()
                )
            }
        }
        blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(m.text.ifBlank { "Siehe Anhang." }).build())
        return blocks
    }
}

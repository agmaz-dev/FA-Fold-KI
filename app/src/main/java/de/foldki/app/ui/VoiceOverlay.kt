package de.foldki.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foldki.app.ChatViewModel
import de.foldki.app.UiState
import de.foldki.app.data.Engine
import de.foldki.app.data.Role
import de.foldki.app.export.PdfExporter
import de.foldki.app.voice.VoiceController

private enum class Phase { LISTENING, THINKING, SPEAKING, PAUSED }

/** Full-screen voice conversation: listen → ask the AI → read the answer aloud → listen again. */
@Composable
fun VoiceOverlay(ui: UiState, vm: ChatViewModel, voice: VoiceController, onClose: () -> Unit) {
    var phase by remember { mutableStateOf(Phase.PAUSED) }
    var heard by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }

    fun startListening() {
        heard = ""; info = ""; phase = Phase.LISTENING
        voice.listen(
            onPartial = { heard = it },
            onFinal = { t ->
                level = 0f
                if (t.isBlank()) { phase = Phase.PAUSED; info = "Nichts verstanden – tippe zum Sprechen." }
                else { heard = t; phase = Phase.THINKING; vm.send(t, voice = true) }
            },
            onError = { msg -> level = 0f; phase = Phase.PAUSED; info = msg },
            onLevel = { level = it },
        )
    }

    LaunchedEffect(Unit) { startListening() }
    DisposableEffect(Unit) { onDispose { voice.cancelListening(); voice.stopSpeaking() } }

    // When the answer is complete, read it aloud and then listen again.
    LaunchedEffect(ui.generating) {
        if (!ui.generating && phase == Phase.THINKING) {
            val last = ui.current?.messages?.lastOrNull()
            if (last != null && last.role == Role.ASSISTANT && !last.isError) {
                phase = Phase.SPEAKING
                voice.speak(PdfExporter.clean(last.text)) { startListening() }
            } else {
                phase = Phase.PAUSED; info = last?.text ?: "Keine Antwort."
            }
        }
    }

    val answer = ui.current?.messages?.lastOrNull()?.takeIf { it.role == Role.ASSISTANT && phase != Phase.LISTENING }?.text.orEmpty()

    val pulse = rememberInfiniteTransition(label = "pulse")
    val breathe by pulse.animateFloat(
        0.92f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breathe",
    )
    val target = when (phase) {
        Phase.LISTENING -> 1f + level * 0.35f
        Phase.THINKING, Phase.SPEAKING -> breathe
        Phase.PAUSED -> 0.85f
    }
    val scale by animateFloatAsState(target, label = "scale")

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (ui.engine == Engine.NANO) "Sprachmodus · Gemini Nano" else "Sprachmodus · Claude",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(200.dp).scale(scale).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (phase == Phase.PAUSED) 0.4f else 0.9f))
                    .clickable {
                        when (phase) {
                            Phase.LISTENING -> voice.stopListening()
                            Phase.SPEAKING -> { voice.stopSpeaking(); startListening() }
                            Phase.PAUSED -> startListening()
                            Phase.THINKING -> {}
                        }
                    },
            )
            Spacer(Modifier.height(32.dp))
            Text(
                when (phase) {
                    Phase.LISTENING -> "Ich höre zu…"
                    Phase.THINKING -> "Denke nach…"
                    Phase.SPEAKING -> "Spreche – tippe zum Unterbrechen"
                    Phase.PAUSED -> "Tippe auf den Kreis zum Sprechen"
                },
                fontSize = 18.sp, fontFamily = FontFamily.Serif,
            )
            Spacer(Modifier.height(12.dp))
            val shown = when {
                info.isNotBlank() -> info
                phase == Phase.LISTENING -> heard
                answer.isNotBlank() -> PdfExporter.clean(answer)
                else -> heard
            }
            Text(
                shown, textAlign = TextAlign.Center, maxLines = 6, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 560.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = {
                        when (phase) {
                            Phase.LISTENING -> voice.stopListening()
                            Phase.SPEAKING -> voice.stopSpeaking().also { phase = Phase.PAUSED }
                            Phase.THINKING -> { vm.stop(); phase = Phase.PAUSED }
                            Phase.PAUSED -> startListening()
                        }
                    },
                    modifier = Modifier.size(64.dp),
                ) { Icon(if (phase == Phase.PAUSED) Icons.Default.Mic else Icons.Default.Stop, "Mikrofon") }
                FilledTonalIconButton(onClick = onClose, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.Close, "Sprachmodus beenden")
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

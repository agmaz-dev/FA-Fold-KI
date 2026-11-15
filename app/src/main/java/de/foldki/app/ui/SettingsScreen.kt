package de.foldki.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.foldki.app.ChatViewModel
import de.foldki.app.READY
import de.foldki.app.UiState
import de.foldki.app.data.Settings
import de.foldki.app.voice.Supertonic

/** Full-screen settings page (like the Claude app): back arrow, sections, saved automatically. */
@Composable
fun SettingsScreen(vm: ChatViewModel, ui: UiState, onBack: () -> Unit) {
    var key by remember { mutableStateOf(vm.settings.apiKey) }
    var model by remember { mutableStateOf(vm.settings.claudeModel) }
    var system by remember { mutableStateOf(vm.settings.systemPrompt) }
    var sender by remember { mutableStateOf(vm.settings.sender) }
    var rate by remember { mutableFloatStateOf(vm.settings.ttsRate) }
    var stVoice by remember { mutableStateOf(vm.settings.stVoice) }
    val stStatus by vm.stStatus.collectAsStateWithLifecycle()
    val stBusy by vm.stBusy.collectAsStateWithLifecycle()
    val stReady = stStatus == READY || vm.voice.isReady

    fun saveAndClose() {
        vm.saveSettings(key, model, system); vm.saveSender(sender); vm.saveVoice(stVoice, rate)
        onBack()
    }
    BackHandler { saveAndClose() }
    DisposableEffect(Unit) { onDispose { vm.voice.stopSpeaking() } }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::saveAndClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                Text("Einstellungen", fontFamily = FontFamily.Serif, fontSize = 22.sp)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Section("Sprache – Tessa & Caleb") {
                        Hint("Sprechen und Zuhören laufen komplett auf dem Handy, ohne Samsung- oder Google-Dienste.")
                        Text(stStatus, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (stBusy) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                TextButton(onClick = vm::cancelVoiceSetup) { Text("Abbrechen") }
                            } else {
                                TextButton(onClick = vm::setupVoice) {
                                    Text(if (stReady) "Neu laden / reparieren" else "Sprachfunktion einrichten (ca. 500 MB)")
                                }
                            }
                        }
                        Supertonic.VOICES.forEach { (id, v) ->
                            Row(Modifier.fillMaxWidth().clickable { stVoice = id }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = stVoice == id, onClick = { stVoice = id })
                                Text(v.second, modifier = Modifier.weight(1f))
                                IconButton(
                                    enabled = vm.voice.supertonic.isReady && !stBusy,
                                    onClick = { vm.voice.rate = rate; vm.voice.previewSupertonic(id) { vm.notify("Stimme: $it") } },
                                ) { Icon(Icons.Default.PlayArrow, "Probehören") }
                            }
                        }
                        Text("Tempo: ${"%.1f".format(rate)}×", fontSize = 13.sp)
                        Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.6f..1.6f)
                    }

                    Section("Gemini Nano (offline)") {
                        Text(ui.nanoStatus, fontSize = 14.sp)
                    }

                    Section("Claude (online)") {
                        OutlinedTextField(
                            value = key, onValueChange = { key = it },
                            label = { Text("API-Schlüssel") },
                            placeholder = { Text("sk-ant-…") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Hint("Erhältlich unter console.anthropic.com. Wird nur auf diesem Gerät gespeichert.")
                        Settings.MODELS.forEach { (id, label) ->
                            Row(Modifier.fillMaxWidth().clickable { model = id }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = model == id, onClick = { model = id })
                                Text(label)
                            }
                        }
                    }

                    Section("Briefe") {
                        OutlinedTextField(
                            value = sender, onValueChange = { sender = it },
                            label = { Text("Mein Absender") },
                            placeholder = { Text("Vorname Nachname\nStraße Nr.\nPLZ Ort") },
                            minLines = 3, modifier = Modifier.fillMaxWidth(),
                        )
                        Hint("Wird nur benutzt, wenn du einen Brief oder Widerspruch schreiben lässt.")
                    }

                    Section("Persönlichkeit") {
                        OutlinedTextField(
                            value = system, onValueChange = { system = it },
                            label = { Text("Anweisung an die KI") },
                            minLines = 4, modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { system = Settings.DEFAULT_SYSTEM }) { Text("Auf Standard zurücksetzen") }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title, fontWeight = FontWeight.Medium, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

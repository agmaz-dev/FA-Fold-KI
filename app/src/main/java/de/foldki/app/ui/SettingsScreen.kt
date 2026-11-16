package de.foldki.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.saveable.rememberSaveable
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
    var ghToken by remember { mutableStateOf(vm.githubToken) }
    // Sub-page: edit a sender profile (folded full screen, unfolded inside the left pane).
    var editProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var rate by remember { mutableFloatStateOf(vm.settings.ttsRate) }
    var stVoice by remember { mutableStateOf(vm.settings.stVoice) }
    val stStatus by vm.stStatus.collectAsStateWithLifecycle()
    val stBusy by vm.stBusy.collectAsStateWithLifecycle()
    val stReady = stStatus == READY || vm.voice.isReady

    fun saveAndClose() {
        vm.saveSettings(key, model, system); vm.saveVoice(stVoice, rate); vm.saveGitHubToken(ghToken)
        onBack()
    }
    editProfile?.let { id ->
        SenderProfileScreen(vm, id, onBack = { editProfile = null })
        return
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
                val openCards = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver(
                    save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) },
                )) { mutableStateOf(emptySet<String>()) }
                androidx.compose.runtime.CompositionLocalProvider(LocalOpenCards provides openCards) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Collapsible cards (like the Grok app): tap the header to open or close a card.
                    Card("nano", Icons.Default.PhoneAndroid, "Gemini Nano", if (ui.nanoReady) "Bereit · offline · Standard" else ui.nanoStatus) {
                        Text(ui.nanoStatus, fontSize = 14.sp)
                        Hint("Gemini Nano läuft auf dem Handy und ist immer Standard und Ersatz, auch wenn Gemma eingeschaltet ist.")
                    }
                    Card("claude", Icons.Default.Cloud, "Claude (online)", if (key.isBlank()) "Kein API-Schlüssel" else Settings.MODELS.firstOrNull { it.first == model }?.second ?: model) {
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
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { model = id }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = model == id, onClick = { model = id })
                                Text(label)
                            }
                        }
                    }
                    Card("github", Icons.Default.Code, "GitHub (über Claude)", if (ghToken.isBlank()) "Nicht verbunden" else "Verbunden · Änderungen nur mit Freigabe") {
                        OutlinedTextField(
                            value = ghToken, onValueChange = { ghToken = it },
                            label = { Text("GitHub-Token") },
                            placeholder = { Text("github_pat_… oder ghp_…") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Hint(
                            "Mit Token kann Claude auf GitHub suchen, Code lesen, Bausteine übernehmen, Repositorys anlegen, " +
                                "Dateien committen, Pull Requests erstellen und APKs mit GitHub Actions bauen. " +
                                "Jede Änderung zeigt die App dir vorher – erst nach „Freigeben“ passiert sie.",
                        )
                        Hint(
                            "Token erstellen: github.com → Settings → Developer settings → Personal access tokens. " +
                                "Rechte: Contents, Pull requests, Issues, Actions und Workflows (Lesen und Schreiben), " +
                                "Administration (zum Anlegen von Repositorys). Wird nur auf diesem Gerät gespeichert.",
                        )
                        if (key.isBlank()) Hint("Dafür braucht es auch den Claude-API-Schlüssel (Karte „Claude“).")
                    }
                    Card("gemma", Icons.Default.Memory, "Optionale On-Device-Modelle", gemmaSummary(ui)) { GemmaSettings(vm, ui) }
                    Card("brief", Icons.Default.WbSunny, "Tagesstart (offline)", briefSummary(vm)) { BriefSettings(vm) }
                    Card("sender", Icons.Default.Draw, "Briefe – Absender", senderSummary(vm)) { SenderProfilesList(vm, onEdit = { editProfile = it }) }
                    Card("voice", Icons.Default.RecordVoiceOver, "Stimme – Tessa & Caleb", if (stReady) "Eingerichtet · " + (Supertonic.VOICES[stVoice]?.second ?: stVoice) else "Noch nicht eingerichtet") {
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
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { stVoice = id }, verticalAlignment = Alignment.CenterVertically) {
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
                    Card("system", Icons.Default.Tune, "Systemanweisung", if (system.trim() == Settings.DEFAULT_SYSTEM.trim()) "Standard" else "Eigene Anweisung") {
                        OutlinedTextField(
                            value = system, onValueChange = { system = it },
                            label = { Text("Anweisung an die KI") },
                            minLines = 4, modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { system = Settings.DEFAULT_SYSTEM }) { Text("Auf Standard zurücksetzen") }
                    }
                    val mem by vm.memories.collectAsStateWithLifecycle()
                    Card("memory", Icons.Default.Tune, "Gedächtnis", if (mem.isEmpty()) "Noch nichts gemerkt" else "${mem.size} Einträge") {
                        Text(
                            "Was die KI über dich weiß – gilt in allen Chats (nur Nano/Gemma, bleibt auf dem Handy). " +
                                "Sag im Chat z. B. „Merk dir: …“ oder „Ich heiße …“.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        mem.forEachIndexed { i, f ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("• $f", fontSize = 14.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.removeMemory(i) }) { Text("Löschen") }
                            }
                        }
                        var newFact by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = newFact, onValueChange = { newFact = it },
                            label = { Text("Selbst hinzufügen") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { vm.addMemory(newFact); newFact = "" }, enabled = newFact.isNotBlank()) { Text("Merken") }
                    }
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val version = remember {
                        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
                    }
                    Card("report", Icons.Default.Tune, "Fehlerbericht", "Version $version") {
                        Text(
                            "Zeigt dem Entwickler, was die KI bekommen und geantwortet hat, welches Modell lief und wo Fehler waren. " +
                                "Bleibt auf dem Handy, bis du ihn teilst. Enthält deine Fragen und Antworten, aber keinen API-Schlüssel.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            runCatching { de.foldki.app.data.DebugLog.copy(ctx, version) }
                            vm.notify("Fehlerbericht kopiert – jetzt einfach im Chat einfügen")
                        }) { Text("Fehlerbericht kopieren") }
                        TextButton(onClick = { runCatching { de.foldki.app.data.DebugLog.share(ctx, version) } }) { Text("Als Datei teilen") }
                        TextButton(onClick = { de.foldki.app.data.DebugLog.clear(); vm.notify("Fehlerbericht geleert") }) { Text("Bericht leeren") }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                }
            }
        }
    }
}

@Composable
private fun Card(
    id: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val open = LocalOpenCards.current
    val expanded = id in open.value
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clickable(onClickLabel = if (expanded) "Zuklappen" else "Aufklappen") {
                        open.value = if (expanded) open.value - id else open.value + id
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "Zuklappen" else "Aufklappen",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}

/** Which cards are open (kept across rotation and folding). */
private val LocalOpenCards = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<Set<String>>> { error("no cards") }

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

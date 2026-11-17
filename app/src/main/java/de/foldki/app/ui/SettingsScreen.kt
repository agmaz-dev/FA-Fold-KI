package de.foldki.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import android.widget.Toast
import de.foldki.app.data.MemoryStore
import de.foldki.app.data.MemoryEntry
import de.foldki.app.data.MemoryCategory
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.foldki.app.ChatViewModel
import de.foldki.app.READY
import de.foldki.app.UiState
import de.foldki.app.ai.LocalModelManager
import de.foldki.app.voice.Supertonic

/**
 * Settings (approved design): an overview in groups, each row opens its own page.
 * Everything is saved when a page or the settings are left.
 */
@Composable
fun SettingsScreen(vm: ChatViewModel, ui: UiState, onBack: () -> Unit) {
    var system by remember { mutableStateOf(vm.settings.systemPrompt) }
    var instrOpen by remember { mutableStateOf(false) }
    var ghToken by remember { mutableStateOf(vm.githubToken) }
    var rate by remember { mutableFloatStateOf(vm.settings.ttsRate) }
    var stVoice by remember { mutableStateOf(vm.settings.stVoice) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var editProfile by rememberSaveable { mutableStateOf<String?>(null) }
    val stStatus by vm.stStatus.collectAsStateWithLifecycle()
    val stBusy by vm.stBusy.collectAsStateWithLifecycle()
    val stReady = stStatus == READY || vm.voice.isReady

    fun saveAll() { vm.saveVoice(stVoice, rate); vm.saveGitHubToken(ghToken) }

    editProfile?.let { id ->
        SenderProfileScreen(vm, id, onBack = { editProfile = null })
        return
    }
    DisposableEffect(Unit) { onDispose { vm.voice.stopSpeaking() } }

    val back: () -> Unit = { saveAll(); if (page == "hf") page = "models" else if (page != null) page = null else onBack() }
    BackHandler { back() }
    if (instrOpen) InstructionsDialog(
        initial = system,
        onSave = { v -> vm.saveSettings(v); system = v; instrOpen = false },
        onDismiss = { instrOpen = false },
    )

    val title = when (page) {
        "models" -> "KI-Modelle"; "hf" -> "Hugging Face"; "github" -> "GitHub"
        "sender" -> "Absender & Unterschrift"; "voice" -> "Stimme"; "brief" -> "Tagesstart"
        "memory" -> "Gedächtnis"; "report" -> "Fehlerbericht"
        else -> "Einstellungen"
    }

    Surface(color = FK.Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = FK.Text) }
                Spacer(Modifier.width(4.dp))
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, maxLines = 1)
            }
            if (page == "hf") {
                // Mini browser for GGUF models: full height, scrolls itself (no outer scroll around the WebView).
                Box(Modifier.weight(1f).fillMaxWidth()) { HfBrowser(vm, ui, onDone = { page = "models" }) }
            } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                ) {
                    when (page) {
                        null -> Overview(vm, ui, ghToken, stReady, stVoice, rate, system) { if (it == "system") instrOpen = true else page = it }
                        "models" -> Group { Column(Modifier.padding(16.dp)) { ModelSettings(vm, ui, openBrowser = { page = "hf" }) } }
                                                "github" -> GitHubPage(vm, ghToken, { ghToken = it })
                        "sender" -> Group { Column(Modifier.padding(12.dp)) { SenderProfilesList(vm, onEdit = { editProfile = it }) } }
                        "voice" -> VoicePage(vm, stStatus, stBusy, stReady, stVoice, { stVoice = it }, rate, { rate = it })
                        "brief" -> Group { Column(Modifier.padding(16.dp)) { BriefSettings(vm) } }
                        "memory" -> MemoryPage(vm)
                        "report" -> ReportPage(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun Overview(
    vm: ChatViewModel, ui: UiState, ghToken: String, stReady: Boolean,
    stVoice: String, rate: Float, system: String, open: (String) -> Unit,
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    vm.sigVersion.collectAsStateWithLifecycle().value
    val (list, activeId) = profiles
    val active = list.firstOrNull { it.id == activeId }
    var brief by remember { mutableStateOf(vm.settings.dailyBriefEnabled) }
    val login by vm.githubLogin.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val mem by vm.memories.collectAsStateWithLifecycle()

    Group {
        SettingRow(
            title = active?.name?.ifBlank { active.title } ?: "Absender anlegen",
            subtitle = if (active == null) "Für Briefe: Name, Adresse, Unterschrift"
                else "Absender aktiv · " + if (vm.signatures.exists(active.id)) "Unterschrift gespeichert" else "keine Unterschrift",
            leading = { Avatar(active?.effectiveInitials ?: "", 34.dp) },
            onClick = { open("sender") },
        )
    }

    SectionLabel("KI-Modelle")
    Group {
        val op = ui.modelOp
        SettingRow(
            if (ui.modelReady) ui.modelName else "Lokales Modell (GGUF)",
            when (op) {
                is LocalModelManager.State.Downloading -> "lädt … ${op.pct} %"
                is LocalModelManager.State.Verifying -> "prüft … ${op.pct} %"
                is LocalModelManager.State.Importing -> "importiert … ${op.pct} %"
                is LocalModelManager.State.Failed -> "Fehler – bitte öffnen"
                LocalModelManager.State.Idle -> when {
                    ui.modelReady -> "aktiv · " + (ui.localModels.size.let { if (it == 1) "1 Modell" else "$it Modelle" }) + " auf dem Handy"
                    ui.localModels.isNotEmpty() -> "kein Modell ausgewählt"
                    else -> "nicht geladen · herunterladen oder importieren"
                }
            },
            leading = { Tile(Icons.Default.Diamond, FK.T3) }, onClick = { open("models") },
        ) {
            if (ui.modelReady && op == LocalModelManager.State.Idle) Tag("bereit", on = true) else Chevron()
        }
    }

    SectionLabel("Zugänge")
    Group {
        SettingRow(
            "GitHub",
            when {
                ghToken.isBlank() -> "Nicht verbunden · nur mit Freigabe"
                login.isNotBlank() -> "Verbunden als $login · nur mit Freigabe"
                else -> "Token eingetragen · nur mit Freigabe"
            },
            leading = { Tile(Icons.Default.Code, FK.T6) }, onClick = { open("github") },
        )
    }

    SectionLabel("Briefe & Stimme")
    Group {
        SettingRow("Absender & Unterschrift", if (list.size == 1) "1 Profil" else "${list.size} Profile",
            leading = { Tile(Icons.Default.Draw, FK.T5) }, onClick = { open("sender") })
        GroupDivider()
        SettingRow(
            "Stimme",
            if (stReady) (Supertonic.VOICES[stVoice]?.second?.substringBefore(" (") ?: stVoice) + " · Tempo " + "%.1f".format(java.util.Locale.GERMANY, rate) + "×"
            else "Noch nicht eingerichtet",
            leading = { Tile(Icons.AutoMirrored.Filled.VolumeUp, FK.T4) }, onClick = { open("voice") },
        )
        GroupDivider()
        SettingRow("Tagesstart", if (brief) "an · morgens 5–11 Uhr" else "aus",
            leading = { Tile(Icons.Default.WbSunny, FK.T1) }, onClick = { open("brief") }) {
            FkToggle(brief, { on -> brief = on; vm.setDailyBrief(on) })
        }
    }

    SectionLabel("Allgemein")
    Group {
        SettingRow("Anweisungen an die KI", systemPreview(system),
            leading = { Tile(Icons.Default.Edit, FK.T6) }, onClick = { open("system") })
        GroupDivider()
        SettingRow("Gedächtnis", if (mem.isEmpty()) "noch nichts gemerkt" else "${mem.count { it.active }} aktiv von ${mem.size}",
            leading = { Tile(Icons.Default.Psychology, FK.T6) }, onClick = { open("memory") })
        GroupDivider()
        SettingRow("Fehlerbericht", "Version $version",
            leading = { Tile(Icons.Default.ReportProblem, FK.T6) }, onClick = { open("report") })
    }
}

/** Big centred status block at the top of a page (like the GitHub page). */
@Composable
private fun PageHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, tone: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>, status: String, note: String) {
    Group {
        Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Tile(icon, tone, size = 56.dp, radius = 16.dp, iconSize = 26.dp)
            Spacer(Modifier.height(10.dp))
            Text(status, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Note(note, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp).fillMaxWidth())
        }
    }
}

/** Input field of the design: small label above, value below, dark background, thin border. */
@Composable
fun FkField(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, minLines: Int = 1, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
            .background(FK.Bg, RoundedCornerShape(14.dp)).border(1.dp, FK.Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 11.sp, color = FK.Muted)
        BasicTextField(
            value = value, onValueChange = onChange,
            singleLine = minLines == 1, minLines = minLines,
            textStyle = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = FK.Text, letterSpacing = if (password) 2.sp else 0.sp),
            cursorBrush = SolidColor(FK.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

@Composable
private fun GitHubPage(vm: ChatViewModel, token: String, setToken: (String) -> Unit) {
    val login by vm.githubLogin.collectAsStateWithLifecycle()
    val checking by vm.githubChecking.collectAsStateWithLifecycle()
    PageHeader(
        Icons.Default.Code, FK.T6,
        when { token.isBlank() -> "Nicht verbunden"; login.isNotBlank() -> "Verbunden als $login"; else -> "Token eingetragen – noch nicht geprüft" },
        "Das lokale Modell kann auf deinem Handy suchen, Code lesen, Repos anlegen, committen und Builds starten. Jede Änderung zeigt die App dir vorher.",
    )
    SectionLabel("Zugang")
    Group {
        FkField("GitHub-Token", token, setToken, password = true)
        if (checking) Box(Modifier.fillMaxWidth().height(48.dp).padding(bottom = 14.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = FK.Accent)
        } else FkButton("Verbinden & prüfen", Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp), enabled = token.isNotBlank()) {
            vm.checkGitHub(token)
        }
    }
    SectionLabel("Was darf Fold KI?")
    Group {
        SettingRow("Lesen & suchen", "sofort, ohne Nachfrage", leading = { Tile(Icons.Default.Visibility, FK.T3) }) {
            FkToggle(true, {}, enabled = false)
        }
        GroupDivider()
        SettingRow("Änderungen", "immer mit deiner Freigabe", leading = { Tile(Icons.Default.Check, FK.T1) }) {
            FkToggle(true, { vm.notify("Änderungen brauchen immer deine Freigabe.") })
        }
    }
    Note(
        "Token anlegen: github.com → Settings → Developer settings → Personal access tokens. Rechte: Contents, Pull requests, " +
            "Issues, Actions, Workflows (Lesen und Schreiben), Administration (zum Anlegen von Repos). " +
            "FA Fold KI arbeitet dafür mit dem lokalen Modell auf dem Handy – ein Modell muss geladen sein. Kein weiterer Schlüssel nötig.",
        Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
    )
}

@Composable
private fun VoicePage(
    vm: ChatViewModel, stStatus: String, stBusy: Boolean, stReady: Boolean,
    stVoice: String, setVoice: (String) -> Unit, rate: Float, setRate: (Float) -> Unit,
) {
    PageHeader(
        Icons.AutoMirrored.Filled.VolumeUp, FK.T4, if (stReady) "Eingerichtet" else "Noch nicht eingerichtet",
        "Sprechen und Zuhören laufen komplett auf dem Handy, ohne Samsung- oder Google-Dienste.",
    )
    if (!stReady || stBusy) {
        Spacer(Modifier.height(14.dp))
        Note(stStatus, Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(10.dp))
        if (stBusy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = FK.Accent)
            Spacer(Modifier.width(12.dp))
            FkButton("Abbrechen", ghost = true) { vm.cancelVoiceSetup() }
        } else FkButton("Sprachfunktion einrichten (ca. 500 MB)") { vm.setupVoice() }
    }
    SectionLabel("Stimme")
    Group {
        Supertonic.VOICES.entries.forEachIndexed { i, (id, v) ->
            if (i > 0) GroupDivider()
            SettingRow(v.second, null, leading = {
                RadioButton(selected = stVoice == id, onClick = { setVoice(id) }, colors = RadioButtonDefaults.colors(selectedColor = FK.Accent))
            }, onClick = { setVoice(id) }) {
                IconButton(
                    enabled = vm.voice.supertonic.isReady && !stBusy,
                    onClick = { vm.voice.rate = rate; vm.voice.previewSupertonic(id) { vm.notify("Stimme: $it") } },
                ) { Icon(Icons.Default.PlayArrow, "Probehören", tint = FK.Muted) }
            }
        }
    }
    SectionLabel("Tempo")
    Group {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("%.1f".format(java.util.Locale.GERMANY, rate) + "×", fontSize = 15.sp, color = FK.Text)
            Slider(value = rate, onValueChange = setRate, valueRange = 0.6f..1.6f,
                colors = SliderDefaults.colors(thumbColor = FK.Accent, activeTrackColor = FK.Accent, inactiveTrackColor = FK.Line))
        }
    }
    if (stReady && !stBusy) {
        Spacer(Modifier.height(14.dp))
        FkButton("Neu laden / reparieren", ghost = true) { vm.setupVoice() }
    }
}

/** Subtitle of the "Anweisungen an die KI" row: "Leer" or a short preview of the user's own text. */
private fun systemPreview(system: String): String {
    val t = system.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (system.isBlank()) "Leer" else if (t.length > 40) t.take(40) + "…" else t.ifEmpty { "Eigene Anweisung" }
}

/** "Anweisungen an die KI": the user's own system instruction, empty by default. Sent as the system message exactly as typed. */
@Composable
fun InstructionsDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FK.Surface,
        title = { Text("Anweisungen an die KI", color = FK.Text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text("Gilt für alle Chats und wird genau so als Systemanweisung gesendet. Leer = keine Anweisung.", fontSize = 13.sp, color = FK.Muted)
                Spacer(Modifier.height(10.dp))
                FkField("Anweisung an die KI", value, { value = it }, minLines = 6, modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(value) }, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FK.Accent, contentColor = FK.OnAccent),
            ) { Text("Speichern", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            Row {
                if (value.isNotEmpty()) TextButton(onClick = { value = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Leeren", color = FK.Muted) }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abbrechen", color = FK.Muted) }
            }
        },
    )
}

@Composable
private fun MemoryPage(vm: ChatViewModel) {
    val mem by vm.memories.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<String?>(null) } // category name; null = all
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryEntry?>(null) }
    var deleting by remember { mutableStateOf<MemoryEntry?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Note(
        "Kurze Einträge (Regeln, Beispiele, Fakten), die nur auf dem Handy bleiben. Aktive Einträge gehen mit jeder Frage ans Modell, " +
            "ausgeschaltete nicht. Du kannst alles ändern oder löschen. Im Chat: „Merk dir: …“ oder „Merken“ unter einer Antwort.",
        Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
    )
    FkButton("+ Eintrag", Modifier.padding(top = 6.dp, bottom = 10.dp)) { adding = true }
    if (mem.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FkChip("Alle ${mem.size}", active = filter == null) { filter = null }
            MemoryCategory.values().forEach { c ->
                val n = mem.count { it.category == c }
                if (n > 0) FkChip("${c.label} $n", active = filter == c.name) { filter = if (filter == c.name) null else c.name }
            }
        }
        MemoryCategory.values().forEach { c ->
            val items = mem.filter { it.category == c && (filter == null || filter == c.name) }.sortedByDescending { it.created }
            if (items.isNotEmpty()) {
                SectionLabel(c.label)
                Group {
                    items.forEachIndexed { i, e ->
                        if (i > 0) GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.text, fontSize = 15.sp, color = if (e.active) FK.Text else FK.Muted, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                    Text(if (e.active) c.label else c.label + " · aus", fontSize = 12.sp, color = FK.Muted)
                                }
                                Spacer(Modifier.width(10.dp))
                                FkToggle(e.active, { on -> vm.setMemoryActive(e.id, on) })
                            }
                            Row(Modifier.padding(top = 4.dp)) {
                                Text("Bearbeiten", color = FK.Accent, fontSize = 14.sp, modifier = Modifier.clickable { editing = e }.padding(end = 16.dp, top = 8.dp, bottom = 8.dp))
                                Text("Löschen", color = FK.Muted, fontSize = 14.sp, modifier = Modifier.clickable { deleting = e }.padding(end = 16.dp, top = 8.dp, bottom = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) MemoryEditDialog(
        title = "Neuer Eintrag", initialText = "", initialCategory = filter?.let { MemoryCategory.of(it) } ?: MemoryCategory.PERSONAL,
        onSave = { t, c ->
            if (!vm.addMemory(t, c)) Toast.makeText(ctx, "Nicht gespeichert: zu kurz oder schon vorhanden.", Toast.LENGTH_SHORT).show()
            adding = false
        },
        onDismiss = { adding = false },
    )
    editing?.let { e ->
        MemoryEditDialog(
            title = "Eintrag bearbeiten", initialText = e.text, initialCategory = e.category,
            onSave = { t, c ->
                if (!vm.updateMemory(e.id, t, c)) Toast.makeText(ctx, "Nicht gespeichert: Text zu kurz.", Toast.LENGTH_SHORT).show()
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = FK.Surface,
            title = { Text("Eintrag löschen?", color = FK.Text, fontWeight = FontWeight.SemiBold) },
            text = { Text(e.text, fontSize = 14.sp, color = FK.Muted) },
            confirmButton = {
                Button(
                    onClick = { vm.removeMemory(e.id); deleting = null }, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FK.Accent, contentColor = FK.OnAccent),
                ) { Text("Löschen", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abbrechen", color = FK.Muted) } },
        )
    }
}

/** Dialog for one memory entry: text (1–2 sentences) and category. Used for "+ Eintrag", editing and "Merken". */
@Composable
fun MemoryEditDialog(
    title: String, initialText: String, initialCategory: MemoryCategory,
    onSave: (String, MemoryCategory) -> Unit, onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var cat by remember { mutableStateOf(initialCategory) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FK.Surface,
        title = { Text(title, color = FK.Text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                FkField("Eintrag (kurz, 1–2 Sätze)", text, { text = it.take(MemoryStore.MAX_TEXT) }, minLines = 3, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoryCategory.values().forEach { c -> FkChip(c.label, active = cat == c) { cat = c } }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(text, cat) }, enabled = text.trim().length >= 3, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FK.Accent, contentColor = FK.OnAccent),
            ) { Text("Speichern", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abbrechen", color = FK.Muted) } },
    )
}

@Composable
private fun ReportPage(vm: ChatViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    PageHeader(
        Icons.Default.ReportProblem, FK.T6, "Version $version",
        "Zeigt dem Entwickler, was die KI bekommen und geantwortet hat, welches Modell lief und wo Fehler waren. " +
            "Bleibt auf dem Handy, bis du ihn teilst. Enthält keinen API-Schlüssel.",
    )
    Spacer(Modifier.height(14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FkButton("Fehlerbericht kopieren") {
            runCatching { de.foldki.app.data.DebugLog.copy(ctx, version) }
            vm.notify("Fehlerbericht kopiert – jetzt einfach im Chat einfügen")
        }
        FkButton("Als Datei teilen", ghost = true) { runCatching { de.foldki.app.data.DebugLog.share(ctx, version) } }
        FkButton("Bericht leeren", ghost = true) { de.foldki.app.data.DebugLog.clear(); vm.notify("Fehlerbericht geleert") }
    }
}

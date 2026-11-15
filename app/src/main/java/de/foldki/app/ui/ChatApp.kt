package de.foldki.app.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import de.foldki.app.voice.VoiceController
import de.foldki.app.voice.Supertonic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import de.foldki.app.data.Attachment
import de.foldki.app.export.PdfExporter
import de.foldki.app.export.DocumentParser
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.foldki.app.ChatViewModel
import de.foldki.app.READY
import de.foldki.app.UiState
import de.foldki.app.data.ChatMessage
import de.foldki.app.data.Engine
import de.foldki.app.data.Role
import de.foldki.app.data.Settings
import kotlinx.coroutines.launch

@Composable
fun ChatApp(vm: ChatViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 600.dp // Fold7 unfolded / tablets: sidebar always visible
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(300.dp).fillMaxHeight()) {
                    Sidebar(ui, vm, onSettings = { showSettings = true }, onPicked = {})
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outline)
                ChatScreen(ui, vm, showMenu = false, onMenu = {}, onSettings = { showSettings = true })
            }
        } else {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Sidebar(ui, vm, onSettings = { showSettings = true }, onPicked = { scope.launch { drawer.close() } })
                    }
                },
            ) {
                ChatScreen(ui, vm, showMenu = true, onMenu = { scope.launch { drawer.open() } }, onSettings = { showSettings = true })
            }
        }
    }

    // Settings slide in as a full-screen page; back arrow or back gesture returns to the chat.
    AnimatedVisibility(
        visible = showSettings,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        SettingsScreen(vm, ui, onBack = { showSettings = false })
    }
}

@Composable
private fun Sidebar(ui: UiState, vm: ChatViewModel, onSettings: () -> Unit, onPicked: () -> Unit) {
    Column(Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
        Text(
            "Fold KI", fontFamily = FontFamily.Serif, fontSize = 24.sp,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .clickable { vm.newChat(); onPicked() }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("Neuer Chat", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        Text(
            "Letzte Chats", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 6.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(ui.conversations, key = { it.id }) { c ->
                val selected = c.id == ui.currentId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { vm.open(c.id); onPicked() }
                        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    IconButton(onClick = { vm.delete(c.id) }) {
                        Icon(Icons.Default.Delete, "Löschen", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSettings(); onPicked() }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text("Einstellungen")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(ui: UiState, vm: ChatViewModel, showMenu: Boolean, onMenu: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showMenu) IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menü") } else Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (ui.engine == Engine.NANO) Icons.Default.PhoneAndroid else Icons.Default.Cloud, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (ui.engine == Engine.NANO) "Gemini Nano" else Settings.MODELS.firstOrNull { it.first == ui.model }?.second?.substringBefore(" (") ?: ui.model,
                    fontWeight = FontWeight.Medium,
                )
                Text("  ▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Column { Text("Gemini Nano"); Text("Offline auf dem Gerät", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) },
                    onClick = { vm.setEngine(Engine.NANO); open = false },
                )
                Settings.MODELS.forEach { (id, label) ->
                    DropdownMenuItem(
                        text = { Column { Text(label.substringBefore(" (")); Text("Online · " + label.substringAfter("(").removeSuffix(")"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                        leadingIcon = { Icon(Icons.Default.Cloud, null) },
                        onClick = {
                            vm.saveSettings(vm.settings.apiKey, id, vm.settings.systemPrompt)
                            vm.setEngine(Engine.CLAUDE); open = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun EmptyState(ui: UiState, vm: ChatViewModel, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("Wie kann ich helfen?", fontFamily = FontFamily.Serif, fontSize = 30.sp)
        Spacer(Modifier.height(16.dp))
        if (ui.engine == Engine.NANO) {
            Text(ui.nanoStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ui.nanoDownloadable) TextButton(onClick = { vm.downloadNano() }) { Text("Gemini Nano herunterladen") }
            if (!ui.nanoReady && !ui.nanoDownloadable) TextButton(onClick = { vm.checkNano() }) { Text("Erneut prüfen") }
        } else if (!ui.apiKeySet) {
            Text("Für Claude wird ein API-Schlüssel benötigt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onSettings) { Text("API-Schlüssel eintragen") }
        }
    }
}

@Composable
private fun ChatScreen(ui: UiState, vm: ChatViewModel, showMenu: Boolean, onMenu: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val messages = ui.current?.messages.orEmpty()
    val listState = rememberLazyListState()
    val lastLen = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastLen) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1, Int.MAX_VALUE / 2)
    }
    LaunchedEffect(ui.notice) {
        ui.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.notify(null) }
    }

    val voice = vm.voice
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var afterMicGranted by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterMicGranted?.invoke() else vm.notify("Ohne Mikrofon-Berechtigung geht die Spracheingabe nicht.")
        afterMicGranted = null
    }
    val withMic: (() -> Unit) -> Unit = { action ->
        if (!voice.isReady) { vm.notify("Bitte zuerst unter Einstellungen die Sprachfunktion einrichten (Tessa & Caleb)."); onSettings() }
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { afterMicGranted = action; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    var speakingId by remember { mutableStateOf<String?>(null) }

    // "Save as PDF": remember the text, then let the user choose where to store the file.
    var pdfText by remember { mutableStateOf("") }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { PdfExporter.write(pdfText, it) }
            }.onSuccess { vm.notify("PDF gespeichert") }
                .onFailure { vm.notify("PDF konnte nicht gespeichert werden: ${it.message}") }
        }
    }
    val actions = MessageActions(
        copy = { text ->
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Text", text))
            vm.notify("Kopiert")
        },
        savePdf = { text -> pdfText = text; savePdf.launch(pdfName(text)) },
        sharePdf = { text ->
            runCatching { PdfExporter.share(context, text, pdfName(text)) }
                .onFailure { vm.notify("Teilen fehlgeschlagen: ${it.message}") }
        },
        speak = { id, text ->
            if (speakingId == id) { voice.stopSpeaking(); speakingId = null }
            else if (!voice.supertonic.isReady) { vm.notify("Bitte zuerst unter Einstellungen die Sprachfunktion einrichten (Tessa & Caleb)."); onSettings() }
            else { speakingId = id; voice.speak(text) { if (speakingId == id) speakingId = null } }
        },
        speakingId = speakingId,
        open = { a ->
            runCatching {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", java.io.File(a.path))
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, a.mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }.onFailure { vm.notify("Keine App zum Öffnen gefunden.") }
        },
        shareFile = { a ->
            runCatching {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", java.io.File(a.path))
                context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = a.mime
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Teilen"))
            }.onFailure { vm.notify("Teilen fehlgeschlagen.") }
        },
    )

    if (voiceMode) {
        VoiceOverlay(ui, vm, voice, onClose = { voiceMode = false })
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(ui, vm, showMenu, onMenu)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (messages.isEmpty()) {
                EmptyState(ui, vm, onSettings)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        MessageRow(m, typing = ui.generating && m === messages.last() && m.role == Role.ASSISTANT, actions = actions)
                    }
                }
            }
        }
        InputBar(ui, vm, voice, withMic, onVoiceMode = { withMic { voice.stopSpeaking(); speakingId = null; voiceMode = true } })
    }
}

private fun pdfName(text: String): String {
    val lines = PdfExporter.clean(text).lines().map { it.trim() }
    val first = lines.firstOrNull { it.startsWith("Betreff") }
        ?: lines.firstOrNull { it.isNotBlank() }?.takeIf { it.length <= 50 && !it.endsWith(",") }
        ?: "Fold-KI-Dokument"
    val base = first.removePrefix("Betreff:").trim().take(40).replace(Regex("[^A-Za-z0-9ÄÖÜäöüß _-]"), "").trim()
    return (base.ifBlank { "Fold-KI-Dokument" }) + ".pdf"
}

private class MessageActions(
    val copy: (String) -> Unit,
    val savePdf: (String) -> Unit,
    val sharePdf: (String) -> Unit,
    val speak: (id: String, text: String) -> Unit,
    val speakingId: String?,
    val open: (Attachment) -> Unit,
    val shareFile: (Attachment) -> Unit,
)

/** Ready-made requests, e.g. a reply letter to an uploaded letter. */
private val QUICK_ACTIONS = listOf(
    "Antwortschreiben" to "Schreibe ein formelles Antwortschreiben (Gegenschreiben) auf das angehängte Schreiben. Gehe auf alle Punkte ein.",
    "Widerspruch" to "Schreibe einen formellen, fristgerechten Widerspruch gegen das angehängte Schreiben mit sachlicher Begründung.",
    "Zusammenfassen" to "Fasse das angehängte Dokument verständlich zusammen: Worum geht es, was wird von mir verlangt, welche Fristen gibt es?",
    "Brief schreiben" to "Schreibe einen formellen Brief. Mein Anliegen: ",
)

@Composable
private fun Thumbnail(a: Attachment, size: Int) {
    val bmp = remember(a.path) {
        runCatching {
            BitmapFactory.decodeFile(a.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
        }.getOrNull()
    }
    if (a.isImage && bmp != null) {
        Image(
            bmp, a.name, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary)
                Text("PDF", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun AnswerText(color: androidx.compose.ui.graphics.Color, text: String) {
    SelectionContainer { Text(text, fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 25.sp, color = color) }
}

@Composable
private fun MessageRow(m: ChatMessage, typing: Boolean, actions: MessageActions) {
    if (m.role == Role.USER) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (m.attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    m.attachments.forEach { a ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).clickable { actions.open(a) }) {
                            Thumbnail(a, 72)
                            Text(a.name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.widthIn(max = 560.dp),
            ) {
                SelectionContainer { Text(m.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 16.sp) }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            val parts = remember(m.text) { DocumentParser.parse(m.text) }
            val clean = remember(m.text) { DocumentParser.display(m.text) }
            val doc = parts.document
            // What the PDF/share buttons export: only the document, or the cleaned answer if there is none.
            val exportText = doc ?: clean
            val textColor = if (m.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
            if (doc == null) {
                AnswerText(textColor, if (m.text.isEmpty() && typing) (if (m.web) "Suche im Internet…" else "…") else clean)
            } else {
                if (parts.before.isNotBlank()) AnswerText(textColor, DocumentParser.clean(parts.before))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(vertical = 8.dp).widthIn(max = 720.dp).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        SelectionContainer {
                            Text(doc, fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 23.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!typing) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                if (!parts.complete) Text("Unvollständig", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { actions.savePdf(doc) }) {
                                    Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp)); Text("PDF")
                                }
                                IconButton(onClick = { actions.sharePdf(doc) }) {
                                    Icon(Icons.Default.Share, "Dokument als PDF teilen", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                if (parts.after.isNotBlank()) AnswerText(textColor, DocumentParser.clean(parts.after))
            }
            if (m.attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    m.attachments.forEach { a ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { actions.open(a) },
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(a.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                                    Text("Tippen zum Öffnen", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { actions.shareFile(a) }) {
                                    Icon(Icons.Default.Share, "Teilen", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (!typing && !m.isError && m.text.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    IconButton(onClick = { actions.copy(clean) }) {
                        Icon(Icons.Default.ContentCopy, "Kopieren", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { actions.savePdf(exportText) }) {
                        Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp)); Text(if (doc != null) "Als PDF" else "Antwort als PDF")
                    }
                    IconButton(onClick = { actions.speak(m.id, DocumentParser.spoken(m.text)) }) {
                        Icon(
                            if (actions.speakingId == m.id) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                            if (actions.speakingId == m.id) "Vorlesen stoppen" else "Vorlesen",
                            modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { actions.sharePdf(exportText) }) {
                        Icon(Icons.Default.Share, "PDF teilen", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    if (m.engine != null) Text(
                        (if (m.engine == Engine.NANO) (if (m.web) "Gemini Nano · Web" else "Gemini Nano · offline") else "Claude · online") +
                            (if (m.web && m.engine == Engine.CLAUDE) " · Websuche" else ""),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    ui: UiState, vm: ChatViewModel, voice: VoiceController,
    withMic: (() -> Unit) -> Unit, onVoiceMode: () -> Unit,
) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var dictating by remember { mutableStateOf(false) }
    val dictate = {
        if (dictating) { voice.stopListening() } else withMic {
            val before = text
            dictating = true
            voice.listen(
                onPartial = { text = (before + " " + it).trim() },
                onFinal = { t -> text = (before + " " + t).trim(); dictating = false },
                onError = { msg -> dictating = false; vm.notify(msg) },
            )
        }
    }
    var plusOpen by remember { mutableStateOf(false) }
    // Path survives if Android recreates the activity while the camera app is open.
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        uris.forEach { vm.addAttachment(it) }
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { vm.addAttachment(it) }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraPath?.let { java.io.File(it) }
        cameraPath = null
        if (ok && f != null && f.length() > 0) vm.addAttachment(android.net.Uri.fromFile(f), deleteAfter = f)
        else f?.delete()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Quick actions (reply letter, objection, …)
        val showQuick = ui.pending.isNotEmpty() || ui.current == null
        if (showQuick && !ui.generating) {
            Row(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QUICK_ACTIONS.forEach { (label, prompt) ->
                    AssistChip(
                        onClick = {
                            if (prompt.endsWith(": ")) text = prompt + text
                            else { vm.send(prompt + if (text.isNotBlank()) "\nZusätzliche Hinweise: $text" else ""); text = "" }
                        },
                        label = { Text(label) },
                        leadingIcon = { Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 2.dp,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
        ) {
            Column {
                if (ui.pending.isNotEmpty() || ui.importing) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, top = 10.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ui.pending.forEach { a ->
                            Box {
                                Thumbnail(a, 60)
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                        .clickable { vm.removePending(a.id) },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Default.Close, "Entfernen", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(14.dp)) }
                            }
                        }
                        if (ui.importing) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("Lese Datei…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                // Text field over the full width (also on the narrow cover screen), buttons below it.
                TextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text(when { dictating -> "Ich höre zu…"; ui.pending.isNotEmpty() -> "Frage zum Anhang…"; ui.webSearch -> "Im Internet nachfragen…"; else -> "Antworten…" }) },
                    minLines = 1, maxLines = 5,
                    // Full width on top; at most ~5 lines, then it scrolls inside instead of pushing the chat up.
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp)) {
                    Box {
                        IconButton(onClick = { plusOpen = true }) {
                            Icon(Icons.Default.Add, "Datei hinzufügen")
                        }
                        DropdownMenu(expanded = plusOpen, onDismissRequest = { plusOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Foto aus Galerie") },
                                leadingIcon = { Icon(Icons.Default.Image, null) },
                                onClick = {
                                    plusOpen = false
                                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Foto aufnehmen") },
                                leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                                onClick = {
                                    plusOpen = false
                                    val f = vm.attachments.newCameraFile()
                                    cameraPath = f.absolutePath
                                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                                    runCatching { takePhoto.launch(uri) }.onFailure { vm.notify("Keine Kamera-App gefunden") }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("PDF-Dokument") },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                onClick = { plusOpen = false; pickPdf.launch(arrayOf("application/pdf")) },
                            )
                        }
                    }
                    IconButton(onClick = vm::toggleWeb) {
                        Icon(
                            Icons.Default.Language, if (ui.webSearch) "Websuche aus" else "Websuche an",
                            tint = if (ui.webSearch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (!ui.generating) {
                        IconButton(onClick = dictate) {
                            Icon(
                                if (dictating) Icons.Default.MicOff else Icons.Default.Mic, "Diktieren",
                                tint = if (dictating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (ui.generating) {
                        FilledIconButton(onClick = vm::stop, modifier = Modifier.padding(4.dp)) { Icon(Icons.Default.Stop, "Stopp") }
                    } else if (text.isBlank() && ui.pending.isEmpty() && !dictating) {
                        // Like the Claude app: with an empty field the button starts the voice conversation.
                        FilledIconButton(
                            onClick = onVoiceMode,
                            modifier = Modifier.padding(4.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) { Icon(Icons.Default.GraphicEq, "Sprachmodus") }
                    } else {
                        FilledIconButton(
                            onClick = { vm.send(text); text = "" },
                            enabled = (text.isNotBlank() || ui.pending.isNotEmpty()) && !ui.importing,
                            modifier = Modifier.padding(4.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) { Icon(Icons.AutoMirrored.Filled.Send, "Senden") }
                    }
                }
            }
        }
    }
}

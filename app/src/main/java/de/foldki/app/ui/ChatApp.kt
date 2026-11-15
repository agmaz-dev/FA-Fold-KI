package de.foldki.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

    if (showSettings) SettingsDialog(vm, ui, onDismiss = { showSettings = false })
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

@Composable
private fun ChatScreen(ui: UiState, vm: ChatViewModel, showMenu: Boolean, onMenu: () -> Unit, onSettings: () -> Unit) {
    val messages = ui.current?.messages.orEmpty()
    val listState = rememberLazyListState()
    val lastLen = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastLen) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1, Int.MAX_VALUE / 2)
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
                        MessageRow(m, typing = ui.generating && m === messages.last() && m.role == Role.ASSISTANT)
                    }
                }
            }
        }
        InputBar(ui, onSend = vm::send, onStop = vm::stop)
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
private fun MessageRow(m: ChatMessage, typing: Boolean) {
    if (m.role == Role.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
            val shown = if (m.text.isEmpty() && typing) "…" else m.text
            SelectionContainer {
                Text(
                    shown,
                    fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 25.sp,
                    color = if (m.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                )
            }
            if (!typing && m.engine != null) {
                Text(
                    if (m.engine == Engine.NANO) "Gemini Nano · offline" else "Claude · online",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun InputBar(ui: UiState, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 2.dp,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(6.dp)) {
                TextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("Antworten…") },
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
                if (ui.generating) {
                    FilledIconButton(onClick = onStop, modifier = Modifier.padding(4.dp)) { Icon(Icons.Default.Stop, "Stopp") }
                } else {
                    FilledIconButton(
                        onClick = { onSend(text); text = "" },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.padding(4.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Senden") }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(vm: ChatViewModel, ui: UiState, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(vm.settings.apiKey) }
    var model by remember { mutableStateOf(vm.settings.claudeModel) }
    var system by remember { mutableStateOf(vm.settings.systemPrompt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen") },
        text = {
            LazyColumn {
                item {
                    Text("Gemini Nano: ${ui.nanoStatus}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text("Claude API-Schlüssel") },
                        placeholder = { Text("sk-ant-…") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Erhältlich unter console.anthropic.com. Wird nur auf diesem Gerät gespeichert.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Claude-Modell", fontWeight = FontWeight.Medium)
                }
                items(Settings.MODELS) { (id, label) ->
                    Row(Modifier.fillMaxWidth().clickable { model = id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model == id, onClick = { model = id })
                        Text(label)
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = system, onValueChange = { system = it },
                        label = { Text("Anweisung an die KI (System-Prompt)") },
                        minLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveSettings(key, model, system); onDismiss() }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

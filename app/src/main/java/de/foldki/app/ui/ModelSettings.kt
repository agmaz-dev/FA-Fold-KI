package de.foldki.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foldki.app.ChatViewModel
import de.foldki.app.UiState
import de.foldki.app.ai.HfModels
import de.foldki.app.ai.LocalModelManager
import de.foldki.app.ai.LocalModelManager.State

/**
 * Section "KI-Modelle" (same building blocks and layout as the original model section): suggestion Qwen3 4B Thinking,
 * Hugging Face browser, import of own .gguf files, list of downloaded models with "aktiv" switch and delete.
 * Stacked, full width – works folded (cover screen) and unfolded.
 */
@Composable
fun ModelSettings(vm: ChatViewModel, ui: UiState, openBrowser: () -> Unit) {
    var confirmDelete by remember { mutableStateOf<LocalModelManager.LocalModel?>(null) }
    var bigWarning by remember { mutableStateOf<HfModels.GgufFile?>(null) }
    val st = ui.modelOp
    val free = remember(st, ui.localModels) { vm.models.freeBytes() }
    val ram = remember { vm.models.totalRam() }
    val pickGguf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importModel(uri) }
    val busy = st is State.Downloading || st is State.Verifying || st is State.Importing
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Lokale KI-Modelle (GGUF, llama.cpp)", fontSize = 14.sp)
        Text(
            "Frei: ${LocalModelManager.gb(free)}" + if (ram > 0) " · Arbeitsspeicher: ${"%.0f".format(java.util.Locale.GERMANY, ram / 1e9)} GB" else "",
            fontSize = 12.sp, color = muted,
        )
        Text(
            "Läuft nur auf dem Handy. Ins Internet gehen nur der Hugging-Face-Browser und der Download des Modells.",
            fontSize = 12.sp, color = muted,
        )
        when (st) {
            is State.Downloading -> {
                Text("Lädt ${HfModels.displayName(st.name)}… ${st.pct} % (${LocalModelManager.gb(st.bytes)} von ${LocalModelManager.gb(st.total)})", fontSize = 13.sp)
                LinearProgressIndicator(progress = { st.pct / 100f }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = vm::cancelModelOp, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Abbrechen") }
                Text("Geht über WLAN und Mobilfunk. Der Download läuft auch weiter, wenn die App zu ist.", fontSize = 12.sp, color = muted)
            }
            is State.Verifying -> {
                Text("Prüfe die Datei (Größe, SHA-256)… ${st.pct} %", fontSize = 13.sp)
                LinearProgressIndicator(progress = { st.pct / 100f }, modifier = Modifier.fillMaxWidth())
            }
            is State.Importing -> {
                Text("Importiere ${st.name}… ${st.pct} %", fontSize = 13.sp)
                LinearProgressIndicator(progress = { st.pct / 100f }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = vm::cancelModelOp, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Abbrechen") }
            }
            is State.Failed -> Text(st.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            State.Idle -> Unit
        }
        if (!busy) {
            val suggested = HfModels.SUGGESTED
            val have = ui.localModels.any { it.file == suggested.fileName }
            Text("Vorschlag: ${HfModels.SUGGESTED_NAME}", fontSize = 14.sp)
            Text("${HfModels.SUGGESTED_INFO} Q4_K_M von TeichAI.", fontSize = 12.sp, color = muted)
            if (!have) Button(
                onClick = {
                    vm.clearModelError()
                    if (HfModels.tooBigFor(ram, suggested.size)) bigWarning = suggested else vm.downloadModel(suggested)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Herunterladen (${LocalModelManager.gb(suggested.size)})") }
            OutlinedButton(onClick = { vm.clearModelError(); openBrowser() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Hugging Face durchsuchen")
            }
            OutlinedButton(onClick = { vm.clearModelError(); pickGguf.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Eigene .gguf importieren")
            }
            Text("Geht über WLAN und Mobilfunk. Geteilte Dateien (…-00001-of-…) werden nicht unterstützt.", fontSize = 12.sp, color = muted)
        }
        if (ui.localModels.isNotEmpty()) Text("Auf dem Handy", fontSize = 14.sp)
        ui.localModels.forEach { m ->
            val on = m.file == ui.activeModel
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(value = on, role = Role.Switch) { if (it) vm.selectModel(m.file) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        LocalModelManager.gb(m.size) + " · " + m.source + " · " + if (on) "aktiv" else "tippen zum Auswählen",
                        fontSize = 12.sp, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = on, onCheckedChange = null)
            }
            if (vm.settings.llmCrashed == m.file) Text(
                "Dieses Modell hat die App beendet (zu wenig Arbeitsspeicher?). Zum erneuten Versuch einfach wieder auswählen.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
            )
            TextButton(
                onClick = { confirmDelete = m },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Modell löschen") }
        }
    }
    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Modell löschen?") },
            text = { Text("${m.name} (${LocalModelManager.gb(m.size)}) wird vom Handy entfernt, auch halb geladene Teile. Du kannst es später erneut herunterladen oder importieren.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = null; vm.deleteModel(m.file) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } },
        )
    }
    bigWarning?.let { f -> BigModelWarning(f, ram, onDismiss = { bigWarning = null }) { bigWarning = null; vm.downloadModel(f) } }
}

/** Warning before a model that is probably too big for the phone's RAM (Galaxy Z Fold 7: 12 GB). */
@Composable
fun BigModelWarning(f: HfModels.GgufFile, ram: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sehr großes Modell") },
        text = {
            Text(
                "${f.fileName} hat ${LocalModelManager.gb(f.size)}. Das Handy hat ${"%.0f".format(java.util.Locale.GERMANY, ram / 1e9)} GB Arbeitsspeicher – " +
                    "das Modell wird wahrscheinlich sehr langsam oder die App wird beendet. Empfohlen sind Dateien bis etwa " +
                    LocalModelManager.gb((ram * 0.6).toLong() - 1_000_000_000L) + ". Trotzdem herunterladen?",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Trotzdem laden")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

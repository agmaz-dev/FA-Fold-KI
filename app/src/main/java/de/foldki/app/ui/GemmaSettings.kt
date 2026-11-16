package de.foldki.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foldki.app.ChatViewModel
import de.foldki.app.UiState
import de.foldki.app.ai.LocalModelManager
import de.foldki.app.ai.LocalModelManager.State

/** Short status line for the card header. */
fun gemmaSummary(ui: UiState, label: String = "Gemma 4"): String = when (val s = ui.gemma) {
    State.NotDownloaded -> "$label · nicht geladen"
    is State.Downloading -> "Download ${s.pct} %"
    is State.Verifying -> "Prüfe Datei ${s.pct} %"
    State.Ready -> if (ui.gemmaEnabled) "$label · aktiv" else "$label · aus (Nano antwortet)"
    is State.Failed -> "Fehler – Nano antwortet"
}

/** Section "Optionale On-Device-Modelle": download, switch "Gemma verwenden", delete. Stacked, full width (also folded). */
@Composable
fun GemmaSettings(vm: ChatViewModel, ui: UiState) {
    var confirmDelete by remember { mutableStateOf(false) }
    val st = ui.gemma
    val free = remember(st) { vm.models.freeBytes() }
    var selected by remember { mutableStateOf(vm.models.selectedId) }
    val spec = remember(selected, st) { vm.models.spec }
    val busy = st is State.Downloading || st is State.Verifying
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Modell wählen (Google Gemma 4, Apache 2.0)", fontSize = 14.sp)
        listOf(
            "e4b" to "Gemma 4 E4B – klüger, für Alltagsfragen und Gespräche (ca. 3,7 GB)",
            "e2b" to "Gemma 4 E2B – kleiner und schneller (2,59 GB)",
        ).forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = spec.id == id, enabled = !busy, role = Role.RadioButton) { vm.selectGemmaModel(id); selected = vm.models.selectedId },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = spec.id == id, onClick = null, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 14.sp)
            }
        }
        Text(
            "Größe: ${if (spec.size > 0) LocalModelManager.gb(spec.size) else "ca. 3,7 GB"} · frei: ${LocalModelManager.gb(free)}",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Läuft nur auf dem Handy. Ins Internet geht nur dieser eine Download. Gemini Nano bleibt Standard und springt ein, " +
                "wenn Gemma nicht antworten kann.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (st) {
            State.NotDownloaded, is State.Failed -> {
                if (st is State.Failed) Text(st.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::downloadGemma, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("${spec.label} herunterladen")
                }
                Text("Geht über WLAN und Mobilfunk. Der Download läuft auch weiter, wenn die App zu ist.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is State.Downloading -> {
                Text("Lädt… ${st.pct} % (${LocalModelManager.gb(st.bytes)})", fontSize = 13.sp)
                LinearProgressIndicator(progress = { st.pct / 100f }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = vm::cancelGemma, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Abbrechen") }
            }
            is State.Verifying -> {
                Text("Prüfe die Datei (SHA-256)… ${st.pct} %", fontSize = 13.sp)
                LinearProgressIndicator(progress = { st.pct / 100f }, modifier = Modifier.fillMaxWidth())
            }
            State.Ready -> Text("Heruntergeladen und geprüft.", fontSize = 13.sp)
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = ui.gemmaEnabled, enabled = st is State.Ready, role = Role.Switch) { vm.setGemmaEnabled(it) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Gemma verwenden", fontSize = 15.sp,
                    color = if (st is State.Ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (st !is State.Ready) "Erst nach Download und Prüfung" else if (ui.gemmaEnabled) "An: Gemma antwortet, Nano springt ein" else "Aus: Nano antwortet, Datei bleibt",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = ui.gemmaEnabled, onCheckedChange = null, enabled = st is State.Ready)
        }
        if (vm.settings.gemmaCrashCount > 0) Text(
            "Gemma hat die App ${vm.settings.gemmaCrashCount}× beendet und wurde ausgeschaltet. Beim nächsten Versuch wird die CPU benutzt.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
        )
        if (st is State.Ready || st is State.Failed) {
            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Modell löschen") }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("${spec.label} löschen?") },
            text = { Text("Die Datei wird vom Handy entfernt. Danach antwortet wieder Gemini Nano. Du kannst Gemma später erneut herunterladen.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; vm.deleteGemma() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

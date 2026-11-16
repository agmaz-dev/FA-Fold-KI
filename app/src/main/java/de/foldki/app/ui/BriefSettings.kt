package de.foldki.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.foldki.app.ChatViewModel
import de.foldki.app.brief.CalendarSource
import de.foldki.app.brief.NotificationCollector

fun briefSummary(vm: ChatViewModel): String =
    if (vm.settings.dailyBriefEnabled) "An · morgens 5–11 Uhr" else "Aus"

/** Section "Tagesstart (offline)": master switch, sources, permissions. */
@Composable
fun BriefSettings(vm: ChatViewModel) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(vm.settings.dailyBriefEnabled) }
    var cal by remember { mutableStateOf(vm.settings.briefCalendar) }
    var notif by remember { mutableStateOf(vm.settings.briefNotifications) }
    var chats by remember { mutableStateOf(vm.settings.briefChats) }
    var refresh by remember { mutableIntStateOf(0) }
    // Re-check the permissions when coming back from Android's settings.
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }
    val calGranted = remember(refresh) { CalendarSource.hasPermission(context) }
    val notifGranted = remember(refresh) { NotificationCollector.isEnabled(context) }
    val calPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (!granted) vm.notify("Ohne Kalender-Berechtigung zeigt der Tagesstart keine Termine.")
    }
    fun save() = vm.setBriefSources(cal, notif, chats)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Morgens zwischen 5 und 11 Uhr zeigt die App beim ersten Öffnen einmal am Tag eine kurze Übersicht. " +
                "Alles bleibt auf dem Handy, Gemini Nano fasst zusammen. Claude bekommt diese Daten nie.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Tagesstart", if (enabled) "An" else "Aus", enabled) { enabled = it; vm.setDailyBrief(it) }
        if (enabled) {
            SwitchRow("Kalender", "Termine heute und morgen", cal) { cal = it; save(); if (it && !calGranted) calPermission.launch(Manifest.permission.READ_CALENDAR) }
            if (cal && !calGranted) OutlinedButton(
                onClick = { calPermission.launch(Manifest.permission.READ_CALENDAR) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Kalender-Zugriff erlauben") }
            SwitchRow("Mitteilungen", "Nur die gerade angezeigten, nichts wird gespeichert", notif) { notif = it; save() }
            if (notif && !notifGranted) {
                OutlinedButton(
                    onClick = {
                        val component = ComponentName(context, NotificationCollector::class.java)
                        val detail = if (Build.VERSION.SDK_INT >= 30) Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString()) else null
                        runCatching { context.startActivity(detail ?: Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                            .recoverCatching { context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                            .onFailure { vm.notify("Einstellungen konnten nicht geöffnet werden.") }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Zugriff auf Mitteilungen erlauben") }
                Text(
                    "Ist der Schalter dort grau („Eingeschränkte Einstellung“): App-Info von Fold KI öffnen, oben rechts ⋮ antippen, " +
                        "„Eingeschränkte Einstellungen zulassen“ wählen und dann den Zugriff erneut einschalten.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("App-Info öffnen") }
            }
            SwitchRow("Offene Punkte aus Chats", "Erkannt per Regeln, z. B. „muss“, „Frist“, „bis Montag“", chats) { chats = it; save() }
            OutlinedButton(onClick = { vm.showBriefAgain() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Heute erneut zeigen")
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

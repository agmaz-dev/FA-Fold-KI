package de.foldki.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foldki.app.brief.Briefing
import de.foldki.app.brief.DailyBriefing

/** The daily briefing card above "Wie kann ich helfen?". Scrolls inside, so it never takes the whole cover screen. */
@Composable
fun BriefCard(
    b: Briefing,
    onClose: () -> Unit,
    onOpenChat: () -> Unit,
    onToggle: (id: String, done: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.WbSunny, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Tagesstart", fontFamily = FontFamily.Serif, fontSize = 18.sp)
                    Text(b.date, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Tagesstart schließen") }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (b.loading) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Gemini Nano fasst zusammen…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                b.summary?.let { Text(it, fontSize = 15.sp, lineHeight = 22.sp) }
                if (b.isEmpty && !b.loading) Text("Heute steht nichts an. Keine Termine, keine offenen Punkte.", fontSize = 14.sp)
                if (b.events.isNotEmpty()) {
                    Label("Termine")
                    b.events.forEach { Text("• " + DailyBriefing.eventLine(it), fontSize = 14.sp) }
                }
                if (b.notifications.isNotEmpty()) {
                    Label("Mitteilungen")
                    b.notifications.forEach { n ->
                        Text("• ${n.app} – ${n.sender}" + (if (n.preview.isNotBlank()) ": ${n.preview}" else ""), fontSize = 14.sp, maxLines = 2)
                    }
                }
                if (!b.notificationsAllowed) Text(
                    "Mitteilungen: Zugriff fehlt (Einstellungen → Tagesstart).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (b.openItems.isNotEmpty()) {
                    Label("Offene Punkte")
                    b.openItems.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(value = item.done, role = Role.Checkbox) { onToggle(item.id, it) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = item.done, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                item.text, fontSize = 14.sp,
                                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenChat, modifier = Modifier.heightIn(min = 48.dp)) { Text("Als Chat öffnen") }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

package de.foldki.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.foldki.app.ChatViewModel
import de.foldki.app.data.SenderProfile

/**
 * Edit one sender profile: all fields stacked over the full width (works on the cover screen with keyboard),
 * saved immediately. At the bottom the signature: tap, sign once, it stays stored in the profile.
 */
@Composable
fun SenderProfileScreen(vm: ChatViewModel, profileId: String, onBack: () -> Unit) {
    val all by vm.profiles.collectAsStateWithLifecycle()
    val sigVersion by vm.sigVersion.collectAsStateWithLifecycle()
    val initial = all.first.firstOrNull { it.id == profileId }
    if (initial == null) { androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }; return }
    var p by remember(profileId) { mutableStateOf(initial) }
    var signing by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteSig by remember { mutableStateOf(false) }
    fun update(n: SenderProfile) { p = n; vm.updateProfile(n) }
    BackHandler { onBack() }

    if (signing) {
        SignaturePad(onDone = { bmp -> vm.saveSignature(p.id, bmp); signing = false }, onCancel = { signing = false })
        return
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                Text(p.title, fontFamily = FontFamily.Serif, fontSize = 22.sp, maxLines = 1)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Field("Bezeichnung (z. B. Privat, Firma)", p.label) { update(p.copy(label = it)) }
                    Field("Name", p.name, cap = KeyboardCapitalization.Words) { update(p.copy(name = it)) }
                    Field("Straße und Hausnummer", p.street, cap = KeyboardCapitalization.Words) { update(p.copy(street = it)) }
                    Field("PLZ und Ort", p.zipCity, cap = KeyboardCapitalization.Words) { update(p.copy(zipCity = it)) }
                    Field("Telefon", p.phone, type = KeyboardType.Phone) { update(p.copy(phone = it)) }
                    Field("E-Mail", p.email, type = KeyboardType.Email, cap = KeyboardCapitalization.None) { update(p.copy(email = it)) }
                    OutlinedTextField(
                        value = p.extra, onValueChange = { update(p.copy(extra = it.take(maxOf(SenderProfile.EXTRA_MAX, p.extra.length)))) },
                        label = { Text("Zusatz (z. B. Kunden- oder Steuernummer)") },
                        supportingText = { Text("${p.extra.length}/${SenderProfile.EXTRA_MAX}") },
                        minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = p.effectiveInitials,
                        onValueChange = { v -> update(p.copy(initials = v.take(6), initialsManual = v.isNotBlank())) },
                        label = { Text("Initialen") },
                        supportingText = { Text(if (p.initialsManual) "Selbst festgelegt" else "Automatisch aus dem Namen · in förmlichen Briefen „Unser Zeichen“") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    )
                    if (p.initialsManual) TextButton(
                        onClick = { update(p.copy(initials = "", initialsManual = false)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Initialen automatisch") }

                    Text("Unterschrift", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    val sig = remember(p.id, sigVersion) { vm.signatures.load(p.id) }
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 96.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .clickable { if (sig == null) signing = true }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sig != null) Image(sig.asImageBitmap(), "Gespeicherte Unterschrift", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(80.dp))
                        else Text("Tippen zum Unterschreiben", color = Color(0xFF555555))
                    }
                    Text(
                        "Wird automatisch in jedes Brief-PDF dieses Profils eingesetzt. Bleibt nur auf dem Handy, keine KI bekommt das Bild.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (sig != null) {
                        OutlinedButton(onClick = { signing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Unterschrift ersetzen") }
                        TextButton(
                            onClick = { confirmDeleteSig = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Unterschrift löschen") }
                    }
                }
            }
        }
    }
    if (confirmDeleteSig) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteSig = false },
            title = { Text("Unterschrift löschen?") },
            text = { Text("Briefe dieses Profils bekommen dann keine Unterschrift mehr.") },
            confirmButton = { TextButton(onClick = { confirmDeleteSig = false; vm.deleteSignature(p.id) }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDeleteSig = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Field(
    label: String, value: String,
    type: KeyboardType = KeyboardType.Text, cap: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type, capitalization = cap),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Profile list for the settings section "Briefe": radio = active, pencil = edit, bin = delete (with question). */
@Composable
fun SenderProfilesList(vm: ChatViewModel, onEdit: (String) -> Unit) {
    val all by vm.profiles.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<SenderProfile?>(null) }
    val (list, active) = all
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (list.isEmpty()) Text("Noch kein Absender. Lege ein Profil an, dann übernimmt Nano Name und Adresse in jeden Brief.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEach { p ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { vm.setActiveProfile(p.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(selected = p.id == active, onClick = { vm.setActiveProfile(p.id) })
                Column(Modifier.weight(1f)) {
                    Text(p.title, fontSize = 15.sp, maxLines = 1)
                    Text(
                        listOf(p.name, p.zipCity).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Noch leer" } +
                            (if (vm.signatures.exists(p.id)) " · mit Unterschrift" else ""),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                IconButton(onClick = { onEdit(p.id) }) { Icon(Icons.Default.Edit, "Profil bearbeiten") }
                IconButton(onClick = { confirmDelete = p }) {
                    Icon(Icons.Default.Delete, "Profil löschen", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        OutlinedButton(onClick = { onEdit(vm.addProfile().id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Profil hinzufügen")
        }
        Text(
            "Der aktive Absender wird bei Briefen an Nano gegeben – und an Claude, wenn Claude den Brief schreibt. Die Unterschrift bekommt keine KI.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    confirmDelete?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("„${p.title}“ löschen?") },
            text = { Text("Das Profil und seine Unterschrift werden vom Handy gelöscht.") },
            confirmButton = {
                TextButton(
                    onClick = { vm.deleteProfile(p.id); confirmDelete = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } },
        )
    }
}

fun senderSummary(vm: ChatViewModel): String {
    val (list, active) = vm.profiles.value
    val a = list.firstOrNull { it.id == active } ?: return "Kein Absender"
    return "Aktiv: " + a.title + if (list.size > 1) " · ${list.size} Profile" else ""
}

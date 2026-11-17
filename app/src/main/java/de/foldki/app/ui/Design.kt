package de.foldki.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Rounded icon tile (settings rows, sheets, cards). [tone] = background to symbol colour. */
@Composable
fun Tile(icon: ImageVector, tone: Pair<Color, Color>, size: Dp = 36.dp, radius: Dp = 10.dp, iconSize: Dp = 18.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(tone.first), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tone.second, modifier = Modifier.size(iconSize))
    }
}

/** Grey upper-case section title above a group. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = FK.Muted, letterSpacing = 0.8.sp,
        modifier = modifier.padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** Rounded block of rows with thin lines between them. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(FK.Surface), content = content)
}

@Composable
fun GroupDivider() = HorizontalDivider(thickness = 1.dp, color = FK.Line)

/** One settings row: tile, title, grey line, something on the right. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    leading: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = { Chevron() },
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = FK.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, color = FK.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

@Composable
fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = FK.Dim, modifier = Modifier.size(22.dp))

/** Small status label, green when [on] ("bereit"). */
@Composable
fun Tag(text: String, on: Boolean = false) {
    Text(
        text, fontSize = 11.sp, color = if (on) FK.Ok else FK.Muted, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) FK.OkSoft else FK.Surface2)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Switch in the Fold KI look (46 × 28, orange when on). [enabled] false = shown but locked. */
@Composable
fun FkToggle(on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.size(46.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) FK.Accent else FK.Line)
            .clickable(enabled = enabled) { onChange(!on) },
    ) {
        Box(
            Modifier.offset(x = if (on) 21.dp else 3.dp, y = 3.dp).size(22.dp).clip(CircleShape)
                .background(if (on) Color.White else FK.Muted),
        )
    }
}

/** Status dot (green = ready). */
@Composable
fun StatusDot(ok: Boolean) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) FK.Ok else FK.Dim))
}

/** Four-pointed star of the start screen. */
@Composable
fun Spark(size: Dp = 34.dp, color: Color = FK.Accent) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val c = Offset(w / 2, h / 2); val k = 0.16f
        val p = Path().apply {
            moveTo(c.x, 0f)
            quadraticTo(c.x + w * k * 0.35f, c.y - h * k * 0.35f, w, c.y)
            quadraticTo(c.x + w * k * 0.35f, c.y + h * k * 0.35f, c.x, h)
            quadraticTo(c.x - w * k * 0.35f, c.y + h * k * 0.35f, 0f, c.y)
            quadraticTo(c.x - w * k * 0.35f, c.y - h * k * 0.35f, c.x, 0f)
            close()
        }
        drawPath(p, color)
    }
}

/** Round outlined 40-dp button (+, mic). */
@Composable
fun RoundOutline(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).border(1.dp, FK.Line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Outlined 36-dp chip (Web, suggestions). */
@Composable
fun FkChip(text: String, icon: ImageVector? = null, active: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .border(1.dp, if (active) FK.Accent else FK.Line, RoundedCornerShape(18.dp))
            .background(if (active) FK.AccentSoft else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (active) FK.Accent else FK.Muted, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 13.sp, color = if (active) FK.Accent else FK.Muted, maxLines = 1)
    }
}

/** Initials in a round orange-tinted badge. */
@Composable
fun Avatar(initials: String, size: Dp = 34.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(FK.AccentSoft), contentAlignment = Alignment.Center) {
        Text(initials.ifBlank { "?" }, color = FK.Accent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp)
    }
}

/** Grey explanatory text. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, color = FK.Muted, lineHeight = 18.sp, modifier = modifier)
}

/** Full-width orange (or outlined) 48-dp button. */
@Composable
fun FkButton(text: String, modifier: Modifier = Modifier, ghost: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp))
            .then(if (ghost) Modifier.border(1.dp, FK.Line, RoundedCornerShape(24.dp)) else Modifier.background(if (enabled) FK.Accent else FK.Line))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (ghost) FK.Text else FK.OnAccent) }
}

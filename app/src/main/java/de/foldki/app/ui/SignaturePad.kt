package de.foldki.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sign once with finger or S-Pen. "Übernehmen" renders the strokes into a transparent bitmap
 * (the caller crops/scales and stores it). Full screen, so it also works on the narrow cover screen.
 */
@Composable
fun SignaturePad(onDone: (Bitmap) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var canvasSize by remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
    val ink = Color(0xFF10204A)

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Unterschrift", fontFamily = FontFamily.Serif, fontSize = 22.sp)
            Text("Einmal mit Finger oder S-Pen unterschreiben. Sie bleibt im Profil gespeichert.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().aspectRatio(3f)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { current.clear(); current.add(it) },
                            onDrag = { change, _ -> change.consume(); current.add(change.position) },
                            onDragEnd = { if (current.size > 1) strokes.add(current.toList()); current.clear() },
                            onDragCancel = { current.clear() },
                        )
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    (strokes + listOf(current.toList())).forEach { pts ->
                        if (pts.size > 1) {
                            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                            drawPath(path, ink, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    drawLine(Color(0x33000000), Offset(24f, this.size.height * 0.78f), Offset(this.size.width - 24f, this.size.height * 0.78f), 1f)
                }
            }
            Row(Modifier.widthIn(max = 720.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Abbrechen") }
                OutlinedButton(onClick = { strokes.clear(); current.clear() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Löschen") }
            }
            Button(
                onClick = { if (canvasSize.width > 0) onDone(render(strokes, canvasSize, ink, 3f * (canvasSize.width / 360f).coerceIn(2f, 6f))) },
                enabled = strokes.isNotEmpty(),
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Übernehmen") }
        }
    }
}

private fun render(strokes: List<List<Offset>>, size: IntSize, ink: Color, width: Float): Bitmap {
    val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888) // transparent
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(255, (ink.red * 255).toInt(), (ink.green * 255).toInt(), (ink.blue * 255).toInt())
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = width
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    strokes.forEach { pts ->
        val path = android.graphics.Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
        c.drawPath(path, p)
    }
    return bmp
}

package jp.jig.sabera.hello.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

/** ピッチ計の縦方向の縮尺。円の半径いっぱいでこの角度になる */
private const val PITCH_RANGE_DEGREES = 45f

/**
 * ピッチとヨーを水平儀風に描く。
 *
 * 数値だけだと「今どちらを向いているか」が読み取れず、軸の対応を人間が判断する
 * テストの役に立たない。傾けた向きと絵の動きが一致することを目で確かめられるようにする。
 */
@Composable
fun AttitudeView(pitchDegrees: Float, yawDegrees: Float, modifier: Modifier = Modifier) {
    val sky = MaterialTheme.colorScheme.primaryContainer
    val ground = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                drawPitchIndicator(pitchDegrees, sky, ground, line, accent)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "ピッチ ${format1(pitchDegrees)}°",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "上向きが負",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                drawYawIndicator(yawDegrees, ground, line, accent)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "ヨー ${format1(yawDegrees)}°",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "0はAR起動時の向き",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 方位盤を 1 枚だけ描く。
 *
 * 中身は [AttitudeView] の右半分と同じもの。指針は真上に固定で、盤の 0 の目盛りが
 * 「基準の方位が今どちら側にあるか」を指す。北向き矢印のテストではこれがそのまま
 * 「グラスに出している矢印」のプレビューになるので、描画を書き足さずに流用する。
 */
@Composable
fun HeadingDial(
    headingDegrees: Float,
    title: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    val ground = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            drawYawIndicator(headingDegrees, ground, line, accent)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$title ${format1(headingDegrees)}°",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 人工水平儀。機体マークを固定し、地平線のほうを動かす。
 * 上を向く（pitch が負）と地平線は視界の下に降りるので、符号を反転して y に足す。
 */
private fun DrawScope.drawPitchIndicator(
    pitchDegrees: Float,
    sky: Color,
    ground: Color,
    line: Color,
    accent: Color,
) {
    val radius = size.minDimension / 2f - 2.dp.toPx()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val pxPerDegree = radius / PITCH_RANGE_DEGREES
    val horizonY = cy - pitchDegrees.coerceIn(-90f, 90f) * pxPerDegree

    val circle = Path().apply {
        addOval(Rect(Offset(cx - radius, cy - radius), Size(radius * 2, radius * 2)))
    }
    clipPath(circle) {
        drawRect(sky, topLeft = Offset(0f, horizonY - size.height), size = size)
        drawRect(ground, topLeft = Offset(0f, horizonY), size = size)
        drawLine(line, Offset(0f, horizonY), Offset(size.width, horizonY), 2.dp.toPx())
        // 10度ごとの目盛り。傾きの大きさを絵から読めるようにする
        for (degrees in -80..80 step 10) {
            if (degrees == 0) continue
            val y = horizonY - degrees * pxPerDegree
            val half = if (degrees % 30 == 0) radius * 0.30f else radius * 0.15f
            drawLine(line, Offset(cx - half, y), Offset(cx + half, y), 1.dp.toPx())
        }
    }
    drawCircle(line, radius, Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))

    // 機体マーク。これが動かない基準になる
    val wing = radius * 0.45f
    val gap = radius * 0.10f
    val width = 3.dp.toPx()
    drawLine(accent, Offset(cx - wing, cy), Offset(cx - gap, cy), width)
    drawLine(accent, Offset(cx + gap, cy), Offset(cx + wing, cy), width)
    drawCircle(accent, width / 2f, Offset(cx, cy))
}

/**
 * ヨーの方位盤。目盛りのほうを回し、指針は真上に固定する。
 * 磁力計が無いので北ではなく「AR起動時の向き」が 0 である点に注意。
 */
private fun DrawScope.drawYawIndicator(
    yawDegrees: Float,
    face: Color,
    line: Color,
    accent: Color,
) {
    val radius = size.minDimension / 2f - 2.dp.toPx()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val center = Offset(cx, cy)

    drawCircle(face, radius, center)
    drawCircle(line, radius, center, style = Stroke(width = 1.dp.toPx()))

    rotate(degrees = -yawDegrees, pivot = center) {
        for (degrees in 0 until 360 step 15) {
            val zero = degrees == 0
            val long = zero || degrees % 45 == 0
            val length = if (long) radius * 0.28f else radius * 0.14f
            val color = if (zero) accent else line
            val width = if (zero) 3.dp.toPx() else 1.dp.toPx()
            rotate(degrees = degrees.toFloat(), pivot = center) {
                drawLine(
                    color,
                    Offset(cx, cy - radius),
                    Offset(cx, cy - radius + length),
                    width,
                )
            }
        }
    }

    // 指針。常に真上を向いたままで、盤の 0 との差が現在のヨーになる
    val pointer = Path().apply {
        moveTo(cx, cy - radius * 0.62f)
        lineTo(cx - radius * 0.12f, cy - radius * 0.30f)
        lineTo(cx + radius * 0.12f, cy - radius * 0.30f)
        close()
    }
    drawPath(pointer, accent)
    drawCircle(accent, 3.dp.toPx(), center)
}

internal fun format1(value: Float): String = String.format(Locale.US, "%.1f", value)

package com.usepocket.geologger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usepocket.geologger.data.Point
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 緯度経度を画面に落とす。Pebble 版と同じ正距円筒図法で、狭い範囲なら十分な精度。
 * 地図タイルは使わないのでオフラインでも描ける。
 */
class TrailProjection(
    val xy: List<Offset>,
    val metersPerPx: Float,
)

fun projectTrail(
    points: List<Point>,
    width: Float,
    height: Float,
    pad: Float = 22f,
    minSpanM: Float = 200f,
): TrailProjection {
    if (points.isEmpty()) return TrailProjection(emptyList(), 1f)

    val mPerDeg = 111_320.0
    val latAvg = points.sumOf { it.lat } / points.size
    val kx = cos(latAvg * Math.PI / 180.0)

    val xs = points.map { it.lon * kx }
    val ys = points.map { -it.lat }   // 画面の y は下向きなので反転して北を上に

    val minX = xs.min(); val maxX = xs.max()
    val minY = ys.min(); val maxY = ys.max()

    // 止まっているときに GPS の揺らぎだけで画面いっぱいに広がらないよう下限を設ける
    val minSpanDeg = minSpanM / mPerDeg
    val spanX = max(maxX - minX, minSpanDeg * kx)
    val spanY = max(maxY - minY, minSpanDeg)

    val scale = min((width - 2 * pad) / spanX, (height - 2 * pad) / spanY)
    val cx = (minX + maxX) / 2
    val cy = (minY + maxY) / 2

    val xy = points.indices.map { i ->
        Offset(
            (width / 2 + (xs[i] - cx) * scale).toFloat(),
            (height / 2 + (ys[i] - cy) * scale).toFloat()
        )
    }
    return TrailProjection(xy, (mPerDeg / scale).toFloat())
}

/** スケールバーに使うきりの良い長さを選ぶ。 */
fun niceScaleBar(metersPerPx: Float, maxPx: Float): Triple<Int, Float, String> {
    val steps = intArrayOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000)
    var best = steps[0]
    for (s in steps) if (s / metersPerPx <= maxPx) best = s
    val label = if (best >= 1000) "${best / 1000} km" else "$best m"
    return Triple(best, best / metersPerPx, label)
}

/**
 * 軌跡を描く。最新の1区間だけ色を変えて進行方向を示し、起点と現在地に印を置く。
 * タップは最も近い点を選ぶ方式。点ごとに当たり判定を置くより軽く、
 * 点数が増えても効き方が変わらない。
 */
@Composable
fun TrailCanvas(
    points: List<Point>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer: TextMeasurer = rememberTextMeasurer()
    var proj: TrailProjection? = null

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(points) {
                detectTapGestures { tap ->
                    val p = proj ?: return@detectTapGestures
                    var best = -1
                    var bestD = Float.MAX_VALUE
                    p.xy.forEachIndexed { i, o ->
                        val d = (o.x - tap.x) * (o.x - tap.x) + (o.y - tap.y) * (o.y - tap.y)
                        if (d < bestD) { bestD = d; best = i }
                    }
                    onSelect(if (sqrt(bestD) <= 44f) best else -1)
                }
            }
    ) {
        if (points.isEmpty()) return@Canvas
        val p = projectTrail(points, size.width, size.height)
        proj = p

        // 経路
        if (p.xy.size >= 2) {
            val path = Path().apply {
                moveTo(p.xy[0].x, p.xy[0].y)
                for (i in 1 until p.xy.size) lineTo(p.xy[i].x, p.xy[i].y)
            }
            drawPath(path, Hue.trail, style = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round))

            // 最新の1区間
            val a = p.xy[p.xy.size - 2]
            val b = p.xy[p.xy.size - 1]
            drawLine(Hue.trailNew, a, b, strokeWidth = 3.5f.dp.toPx(), cap = StrokeCap.Round)
        }

        // 起点
        if (p.xy.size > 1) {
            drawCircle(Hue.start, radius = 5f.dp.toPx(), center = p.xy.first(), style = Stroke(2.5f.dp.toPx()))
        }
        // 現在地（いちばん新しい点）
        drawCircle(Hue.now, radius = 4.5f.dp.toPx(), center = p.xy.last())
        // 選択中の点
        if (selected in p.xy.indices) {
            drawCircle(Hue.pick, radius = 9f.dp.toPx(), center = p.xy[selected], style = Stroke(2f.dp.toPx()))
        }

        // 方位
        val north = measurer.measure("N ↑", TextStyle(color = Hue.faint, fontSize = 12.sp))
        drawText(north, topLeft = Offset(size.width - north.size.width - 10f, 10f))

        // スケールバー
        val (_, barPx, label) = niceScaleBar(p.metersPerPx, size.width * 0.45f)
        val y = size.height - 16f
        val x0 = 14f
        drawLine(Hue.faint, Offset(x0, y), Offset(x0 + barPx, y), strokeWidth = 2f.dp.toPx())
        drawLine(Hue.faint, Offset(x0, y - 6f), Offset(x0, y + 6f), strokeWidth = 2f.dp.toPx())
        drawLine(Hue.faint, Offset(x0 + barPx, y - 6f), Offset(x0 + barPx, y + 6f), strokeWidth = 2f.dp.toPx())
        val lab = measurer.measure(label, TextStyle(color = Hue.faint, fontSize = 11.sp))
        drawText(lab, topLeft = Offset(x0, y - 6f - lab.size.height))
    }
}

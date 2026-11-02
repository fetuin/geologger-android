package com.usepocket.geologger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pebble の設定画面や Expo 版と同じ配色にそろえている。 */
object Hue {
    val bg = Color(0xFF0E0F11)
    val card = Color(0xFF171A1D)
    val cardEdge = Color(0xFF24282D)
    val text = Color(0xFFE8EAED)
    val dim = Color(0xFF9AA0A6)
    val faint = Color(0xFF6B7177)
    val accent = Color(0xFF1F6FEB)
    val good = Color(0xFF3FB950)
    val goodBg = Color(0xFF14201A)
    val goodEdge = Color(0xFF23402E)
    val warn = Color(0xFFD29922)
    val bad = Color(0xFFF85149)
    val badBg = Color(0xFF241718)
    val badEdge = Color(0xFF40242A)
    val trail = Color(0xFF2EA36F)
    val trailNew = Color(0xFFE8833A)
    val start = Color(0xFFE5C100)
    val now = Color(0xFFF85149)
    val pick = Color(0xFF5BC8FF)
    val field = Color(0xFF000000)
    val fieldEdge = Color(0xFF2A2F35)
    val pillOff = Color(0xFF20242A)
    val pillOn = Color(0xFF13335C)
    val pillOnText = Color(0xFFCFE4FF)
}

@Composable
fun GeoLoggerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Hue.accent,
            background = Hue.bg,
            surface = Hue.card,
            onBackground = Hue.text,
            onSurface = Hue.text,
            error = Hue.bad,
        ),
        content = content
    )
}

/** 画面のどこでも使う囲み。配色だけ差し替えられるようにしている。 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    fill: Color = Hue.card,
    edge: Color = Hue.cardEdge,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(fill, RoundedCornerShape(12.dp))
            .border1(edge)
            .padding(14.dp),
        content = content
    )
}

@Composable
fun SectionTitle(text: String) =
    Text(text, color = Hue.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)

@Composable
fun Body(text: String, color: Color = Hue.dim) =
    Text(text, color = color, fontSize = 13.sp, lineHeight = 19.sp)

@Composable
fun Note(text: String, color: Color = Hue.faint) =
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))

private fun Modifier.border1(color: Color) = this.border(1.dp, color, RoundedCornerShape(12.dp))

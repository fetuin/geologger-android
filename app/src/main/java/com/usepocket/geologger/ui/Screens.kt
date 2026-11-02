package com.usepocket.geologger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usepocket.geologger.data.Point
import com.usepocket.geologger.data.Settings
import com.usepocket.geologger.data.Stats
import com.usepocket.geologger.export.ExportRange
import com.usepocket.geologger.export.Formats
import com.usepocket.geologger.geo.Geo
import com.usepocket.geologger.geo.LatLon
import com.usepocket.geologger.net.Protocol
import com.usepocket.geologger.net.UploadStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun time(sec: Long?): String =
    if (sec == null || sec <= 0) "--"
    else SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(sec * 1000))

// ------------------------------------------------------------------ Log

@Composable
fun LogScreen(
    stats: Stats,
    status: UploadStatus,
    points: List<Point>,
    tracking: Boolean,
    settings: Settings,
    busy: String?,
    onToggleTracking: () -> Unit,
    onCaptureOnce: () -> Unit,
    onUploadNow: () -> Unit,
) {
    val walked = Geo.totalDistance(points.asReversed().map { LatLon(it.lat, it.lon) })

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        item {
            Card(
                fill = if (tracking) Hue.goodBg else Hue.card,
                edge = if (tracking) Hue.goodEdge else Hue.cardEdge
            ) {
                Text(
                    if (tracking) "Recording" else "Not recording",
                    color = Hue.text, fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
                Note(
                    if (tracking)
                        "A fix is taken at most every ${settings.intervalSec} s, and stored only " +
                            "when you have moved ${settings.minMoveM} m or more."
                    else
                        "Start recording to log your location in the background."
                )
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        label = if (busy == "track") "…" else if (tracking) "Stop" else "Start recording",
                        modifier = Modifier.weight(1f),
                        enabled = busy == null,
                        danger = tracking,
                        onClick = onToggleTracking
                    )
                    PrimaryButton(
                        label = if (busy == "capture") "…" else "Log one now",
                        modifier = Modifier.weight(1f),
                        enabled = busy == null,
                        plain = true,
                        onClick = onCaptureOnce
                    )
                }
            }
        }

        item {
            Card {
                SectionTitle("Stored on this phone")
                StatRow(
                    listOf(
                        "%,d".format(stats.total) to "points",
                        "%,d".format(stats.unsent) to "not uploaded",
                        Geo.formatDistance(walked) to "last ${points.size} pts",
                    )
                )
                if (stats.firstT != null) {
                    Note("${time(stats.firstT)} → ${time(stats.lastT)}")
                } else {
                    Note("Nothing recorded yet.")
                }
            }
        }

        item {
            val bad = status.lastError.isNotEmpty() || status.unsent > 0
            Card(
                fill = if (bad) Hue.badBg else Hue.goodBg,
                edge = if (bad) Hue.badEdge else Hue.goodEdge
            ) {
                SectionTitle("Upload")
                Body(
                    "Last upload ${Geo.formatAgo(status.lastUploadAt)}  ·  " +
                        (if (status.unsent == 0) "all sent" else "%,d queued".format(status.unsent)) +
                        "  ·  " +
                        (if (settings.batchMode) "batches of ${settings.batchSize}" else "every fix")
                )
                if (status.lastError.isNotEmpty()) {
                    Note(
                        status.lastError + if (status.blocked) "  (automatic retries paused)" else "",
                        color = Color(0xFFFFB4B4)
                    )
                }
                if (!settings.canUpload) {
                    Note("Uploads stay queued until the server ID and password are set in Settings.")
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    PrimaryButton(
                        label = if (busy == "flush") "Uploading…" else "Upload now",
                        modifier = Modifier.weight(1f),
                        enabled = busy == null,
                        onClick = onUploadNow
                    )
                }
            }
        }

        item {
            Text(
                "Recent points" + if (points.isNotEmpty()) " (newest ${points.size})" else "",
                color = Hue.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
            )
        }

        if (points.isEmpty()) {
            item {
                Body("No points yet. Start recording, or tap “Log one now”.")
            }
        }

        items(points, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Geo.formatCoords(p.lat, p.lon), color = Hue.text, fontSize = 14.sp)
                    Text(
                        time(p.t) + (p.acc?.let { "  ±${it.toInt()} m" } ?: ""),
                        color = Hue.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    if (p.sent == 1) "sent" else "queued",
                    color = if (p.sent == 1) Hue.good else Hue.warn,
                    fontSize = 11.sp
                )
            }
            HorizontalDivider(color = Color(0xFF1F2328))
        }
    }
}

// ------------------------------------------------------------------ Map

@Composable
fun MapScreen(
    points: List<Point>,
    rangeKey: String,
    truncated: Boolean,
    maxDraw: Int,
    selected: Int,
    onRange: (String) -> Unit,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            Pill("Today", rangeKey == "today") { onRange("today") }
            Pill("7 days", rangeKey == "week") { onRange("week") }
            Pill("All", rangeKey == "all") { onRange("all") }
        }

        if (points.isEmpty()) {
            Card {
                Body("No points in this range yet. Once your location is recorded, the trail appears here.")
            }
            return@Column
        }

        Card {
            TrailCanvas(points = points, selected = selected, onSelect = onSelect)
        }

        Card {
            if (selected in points.indices) {
                val p = points[selected]
                Text(Geo.formatCoords(p.lat, p.lon), color = Hue.text, fontSize = 15.sp)
                Note(
                    time(p.t) +
                        (p.acc?.let { "  ·  ±${it.toInt()} m" } ?: "") +
                        "  ·  " + Geo.formatDistance(
                            Geo.distanceMeters(
                                LatLon(points.first().lat, points.first().lon),
                                LatLon(p.lat, p.lon)
                            )
                        ) + " from the start" +
                        "  ·  " + (if (p.sent == 1) "uploaded" else "queued")
                )
            } else {
                Body("Tap the trail to inspect a point.")
            }
        }

        Card {
            val direct = if (points.size > 1) Geo.distanceMeters(
                LatLon(points.first().lat, points.first().lon),
                LatLon(points.last().lat, points.last().lon)
            ) else 0.0
            val along = Geo.totalDistance(points.map { LatLon(it.lat, it.lon) })
            StatRow(
                listOf(
                    "%,d".format(points.size) to "points drawn",
                    Geo.formatDistance(direct) to "start → now",
                    Geo.formatDistance(along) to "along the trail",
                )
            )
            Note(
                "${time(points.first().t)} → ${time(points.last().t)}" +
                    if (truncated) "  ·  showing only the newest $maxDraw points" else ""
            )
            Note(
                "The trail is drawn from the stored coordinates alone — no map tiles are " +
                    "downloaded, so this works offline. Distance “along the trail” is the sum of the " +
                    "straight lines between fixes, so it reads shorter than the real walked distance."
            )
        }
    }
}

// --------------------------------------------------------------- Export

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(
    formatKey: String,
    range: ExportRange,
    counts: Map<ExportRange, Int>,
    busy: Boolean,
    progress: Pair<Int, Int>?,
    lastResult: String?,
    onFormat: (String) -> Unit,
    onRange: (ExportRange) -> Unit,
    onShare: () -> Unit,
    onSaveToDownloads: () -> Unit,
) {
    val n = counts[range] ?: 0
    val fmt = Formats.byKey(formatKey)

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Card {
            SectionTitle("Format")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                Formats.all.forEach { f ->
                    Pill(f.label, formatKey == f.ext) { onFormat(f.ext) }
                }
            }
            Note(
                when (formatKey) {
                    "gpx" -> "Standard GPS track with a time on every point."
                    "geojson" -> "One Point feature per fix, for map and GIS tools."
                    else -> "All fields, one row per point. Opens in any spreadsheet."
                }
            )
        }

        Card {
            SectionTitle("What to include")
            ExportRange.entries.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        ChoiceRow(
                            title = r.label,
                            subtitle = "%,d points".format(counts[r] ?: 0),
                            selected = range == r,
                            onClick = { onRange(r) }
                        )
                    }
                }
            }
        }

        Card {
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                    Text(
                        progress?.let { "%,d / %,d".format(it.first, it.second) } ?: "Preparing…",
                        color = Hue.text, fontSize = 14.sp
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        label = if (n == 0) "Nothing to export" else "Share",
                        modifier = Modifier.weight(1f),
                        enabled = n > 0,
                        onClick = onShare
                    )
                    PrimaryButton(
                        label = "Save to Downloads",
                        modifier = Modifier.weight(1f),
                        enabled = n > 0,
                        plain = true,
                        onClick = onSaveToDownloads
                    )
                }
                Note(
                    "“Share” hands the file to the Android share sheet (Drive, email, a file manager). " +
                        "“Save to Downloads” writes it straight into your Downloads folder. " +
                        "Exporting never deletes anything."
                )
            }
        }

        if (lastResult != null) {
            Card(fill = Hue.goodBg, edge = Hue.goodEdge) {
                SectionTitle("Exported")
                Body(lastResult)
            }
        }

        Card {
            SectionTitle("Columns in the CSV")
            Text(
                com.usepocket.geologger.export.CsvFormat.HEADER,
                color = Hue.dim, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            Note(
                "Timestamps are the moment the phone took the fix. `uploaded` is 1 once the point " +
                    "has been accepted by the server."
            )
        }
    }
}

// ------------------------------------------------------------- Settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    s: Settings,
    onChange: (Settings, Boolean, Boolean) -> Unit,   // 設定 / サービス再起動 / 再送を促す
    onOpenSite: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBatterySettings: () -> Unit,
    onPrune: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val clsError = Protocol.validateClassCode(s.classCode)
    val urlWarn = Protocol.checkUrl(s.serverUrl)

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Card {
            SectionTitle("Server account")
            Field(
                "Upload URL", s.serverUrl,
                { onChange(s.copy(serverUrl = it), false, true) },
                placeholder = Settings.DEFAULT_URL,
                keyboard = androidx.compose.ui.text.input.KeyboardType.Uri
            )
            if (urlWarn != null) Note(urlWarn, color = Hue.warn)
            Field("ID", s.userId, { onChange(s.copy(userId = it), false, true) }, placeholder = "required")
            Field("Password", s.password, { onChange(s.copy(password = it), false, true) }, password = true, placeholder = "required")
            Field(
                "Category code (blank, or exactly 3 characters)",
                s.classCode,
                { if (it.length <= 3) onChange(s.copy(classCode = it), false, true) },
                error = clsError,
                placeholder = "(not sent when blank)"
            )
            Note("Stored on this phone only. The ID and password are sent with every upload, so use https.")
            Spacer(Modifier.height(12.dp))
            Text(
                "Open the site that stores these points ↗",
                color = Hue.pick, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                    .clickable(onClick = onOpenSite)
            )
        }

        Card {
            SectionTitle("Sending")
            ChoiceRow(
                "Send every fix", "One request per point.",
                !s.batchMode
            ) { onChange(s.copy(batchMode = false), false, true) }
            ChoiceRow(
                "Send in batches", "Fewer requests, easier on the battery.",
                s.batchMode
            ) { onChange(s.copy(batchMode = true), false, false) }

            if (s.batchMode) {
                Text("Batch size", color = Hue.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 20, 25, 30, 40, 50).forEach { v ->
                        Pill("$v", s.batchSize == v) { onChange(s.copy(batchSize = v), false, false) }
                    }
                }
            }

            SwitchRow(
                "Send timestamps (t)",
                "Turn on only once the server accepts t. Until then every upload fails with 400.",
                s.sendTime
            ) { onChange(s.copy(sendTime = it), false, true) }

            Note(
                if (s.batchMode)
                    "Points are held until ${s.batchSize} have piled up, then sent in one request. " +
                        "Standing still does not fill the batch, so a half-full batch waits until you " +
                        "move again — use “Upload now” on the Log tab to flush it."
                else
                    "Each new point is sent on its own, so the time recorded on the server stays close " +
                        "to the real fix time."
            )
        }

        Card {
            SectionTitle("Recording")
            Text("Minimum interval between fixes", color = Hue.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30 s", 60 to "1 min", 180 to "3 min", 300 to "5 min", 900 to "15 min").forEach { (v, l) ->
                    Pill(l, s.intervalSec == v) { onChange(s.copy(intervalSec = v), true, false) }
                }
            }

            Text("Minimum movement before a point is kept", color = Hue.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 10, 20, 30, 50, 100).forEach { v ->
                    Pill(if (v == 0) "Keep all" else "$v m", s.minMoveM == v) { onChange(s.copy(minMoveM = v), true, false) }
                }
            }
            Note(
                "Measured from the last point that was kept, not from the previous fix — otherwise " +
                    "creeping along below the threshold would never record anything. “Keep all” stores " +
                    "every fix, including the jitter while you stand still."
            )

            SwitchRow(
                "High accuracy",
                "Uses GPS only, ignoring the coarser network provider. Better fixes, shorter battery life.",
                s.highAccuracy
            ) { onChange(s.copy(highAccuracy = it), true, false) }
        }

        Card {
            SectionTitle("Stored data")
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Remove old uploaded", Modifier.weight(1f), plain = true, onClick = onPrune)
                PrimaryButton("Delete all", Modifier.weight(1f), danger = true, onClick = onDeleteAll)
            }
            Note("Exporting never deletes anything. Use these only to free up space.")
        }

        Card {
            SectionTitle("Android settings")
            Body(
                "Background logging needs location access set to “Allow all the time”. With " +
                    "“Only while using the app”, recording stops as soon as the screen goes off."
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Open app permissions ↗",
                color = Hue.pick, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAppSettings)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Exclude from battery optimisation ↗",
                color = Hue.pick, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onBatterySettings)
            )
            Note(
                "Some manufacturers (Xiaomi, Huawei and others) stop foreground services " +
                    "aggressively. If recording keeps dying, exclude this app from battery optimisation."
            )
        }
    }
}

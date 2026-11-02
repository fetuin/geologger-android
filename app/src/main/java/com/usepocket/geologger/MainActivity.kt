package com.usepocket.geologger

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.usepocket.geologger.data.Point
import com.usepocket.geologger.data.PointDb
import com.usepocket.geologger.data.Prefs
import com.usepocket.geologger.data.Settings
import com.usepocket.geologger.data.Stats
import com.usepocket.geologger.export.ExportRange
import com.usepocket.geologger.export.Exporter
import com.usepocket.geologger.export.Formats
import com.usepocket.geologger.geo.Geo
import com.usepocket.geologger.geo.LatLon
import com.usepocket.geologger.net.UploadStatus
import com.usepocket.geologger.net.Uploader
import com.usepocket.geologger.service.LocationService
import com.usepocket.geologger.ui.ExportScreen
import com.usepocket.geologger.ui.GeoLoggerTheme
import com.usepocket.geologger.ui.Hue
import com.usepocket.geologger.ui.LogScreen
import com.usepocket.geologger.ui.MapScreen
import com.usepocket.geologger.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LIST_LIMIT = 300
private const val MAX_DRAW = 1200

class MainActivity : ComponentActivity() {

    private var onPointRecorded: (() -> Unit)? = null

    private val pointReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onPointRecorded?.invoke()
        }
    }

    // 前景位置の許可 → 続けて背景位置を求める、という二段構え。
    // Android はこの順でなければ背景位置を要求できない。
    private val requestForeground = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            toast("Location permission was denied.")
            return@registerForActivityResult
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBackgroundPrompt = true
        } else {
            beginTracking()
        }
    }

    private val requestBackground = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast("Recording will stop when the screen goes off. Choose “Allow all the time” to log in the background.")
        }
        beginTracking()
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒否されても記録自体は続く。通知が出ないだけ */ }

    private var pendingBackgroundPrompt by mutableStateOf(false)
    private var startAfterPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 起動時に、溜まっていれば送信を試みる
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { Uploader.flush(this@MainActivity, manual = false) }
        }

        setContent {
            GeoLoggerTheme {
                AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            pointReceiver,
            IntentFilter(LocationService.BROADCAST_POINT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(pointReceiver) }
    }

    // ---------------------------------------------------------- 画面本体

    @Composable
    private fun AppRoot() {
        var tab by remember { mutableStateOf("log") }
        var reload by remember { mutableStateOf(0) }

        var stats by remember { mutableStateOf(Stats(0, 0, 0, null, null)) }
        var status by remember { mutableStateOf(UploadStatus(null, "", 0, false)) }
        var points by remember { mutableStateOf(emptyList<Point>()) }
        var settings by remember { mutableStateOf(Prefs.load(this)) }
        var tracking by remember { mutableStateOf(Prefs.isTracking(this)) }
        var busy by remember { mutableStateOf<String?>(null) }

        // 地図
        var mapRange by remember { mutableStateOf("today") }
        var mapPoints by remember { mutableStateOf(emptyList<Point>()) }
        var mapTruncated by remember { mutableStateOf(false) }
        var pick by remember { mutableStateOf(-1) }

        // エクスポート
        var formatKey by remember { mutableStateOf("csv") }
        var range by remember { mutableStateOf(ExportRange.ALL) }
        var counts by remember { mutableStateOf(emptyMap<ExportRange, Int>()) }
        var exporting by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var lastExport by remember { mutableStateOf<String?>(null) }

        var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }

        val db = PointDb.get(this)

        suspend fun refresh() = withContext(Dispatchers.IO) {
            val st = db.stats()
            val us = Uploader.status(this@MainActivity)
            val pts = db.recent(LIST_LIMIT)
            val cnt = ExportRange.entries.associateWith { Exporter.count(this@MainActivity, it) }
            withContext(Dispatchers.Main) {
                stats = st; status = us; points = pts; counts = cnt
                settings = Prefs.load(this@MainActivity)
                tracking = Prefs.isTracking(this@MainActivity)
            }
        }

        suspend fun refreshMap() = withContext(Dispatchers.IO) {
            val since = when (mapRange) {
                "today" -> {
                    val c = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }
                    c.timeInMillis / 1000
                }
                "week" -> System.currentTimeMillis() / 1000 - 7 * 86400
                else -> null
            }
            // 新しい順に取ってから古い順へ戻す（上限超過時は新しいほうを残す）
            val got = db.recent(MAX_DRAW, since)
            withContext(Dispatchers.Main) {
                mapTruncated = got.size >= MAX_DRAW
                mapPoints = got.asReversed()
                pick = -1
            }
        }

        LaunchedEffect(reload) { refresh() }
        LaunchedEffect(reload, mapRange) { refreshMap() }

        // サービスが点を記録したら表示を更新する
        LaunchedEffect(Unit) {
            onPointRecorded = { reload++ }
        }

        startAfterPermission = { reload++ }

        if (pendingBackgroundPrompt) {
            AlertDialog(
                onDismissRequest = { pendingBackgroundPrompt = false },
                containerColor = Hue.card,
                title = { Text("Allow location all the time", color = Hue.text) },
                text = {
                    Text(
                        "Android asks for background access separately. On the next screen choose " +
                            "“Allow all the time”, otherwise recording stops when the screen goes off.",
                        color = Hue.dim, fontSize = 13.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingBackgroundPrompt = false
                        requestBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }) { Text("Continue", color = Hue.pick) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingBackgroundPrompt = false
                        beginTracking()
                        reload++
                    }) { Text("Skip", color = Hue.faint) }
                }
            )
        }

        confirm?.let { (title, body, action) ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                containerColor = Hue.card,
                title = { Text(title, color = Hue.text) },
                text = { Text(body, color = Hue.dim, fontSize = 13.sp) },
                confirmButton = {
                    TextButton(onClick = { confirm = null; action() }) {
                        Text("OK", color = Hue.bad)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirm = null }) { Text("Cancel", color = Hue.faint) }
                }
            )
        }

        Scaffold(
            containerColor = Hue.bg,
            bottomBar = {
                Column {
                    HorizontalDivider(color = Color(0xFF22262B))
                    Row(Modifier.fillMaxWidth().background(Color(0xFF121417))) {
                        listOf(
                            "log" to "Log", "map" to "Map", "export" to "Export", "settings" to "Settings"
                        ).forEach { (key, label) ->
                            val on = tab == key
                            Column(
                                Modifier.weight(1f).clickable { tab = key }.padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier.width(20.dp).height(3.dp).background(
                                        if (on) Hue.accent else Color.Transparent,
                                        RoundedCornerShape(2.dp)
                                    )
                                )
                                Text(
                                    label,
                                    color = if (on) Hue.pillOnText else Hue.faint,
                                    fontSize = 12.sp,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        ) { inner ->
            Column(Modifier.fillMaxSize().padding(inner)) {
                Text(
                    "GeoLogger",
                    color = Hue.text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp)
                )
                when (tab) {
                    "log" -> LogScreen(
                        stats = stats, status = status, points = points,
                        tracking = tracking, settings = settings, busy = busy,
                        onToggleTracking = {
                            lifecycleScope.launch {
                                busy = "track"
                                if (tracking) {
                                    LocationService.stop(this@MainActivity)
                                    Prefs.setTracking(this@MainActivity, false)
                                } else {
                                    askPermissionsThenStart()
                                }
                                busy = null
                                reload++
                            }
                        },
                        onCaptureOnce = {
                            lifecycleScope.launch {
                                busy = "capture"
                                captureOnce()
                                busy = null
                                reload++
                            }
                        },
                        onUploadNow = {
                            lifecycleScope.launch {
                                busy = "flush"
                                val r = withContext(Dispatchers.IO) {
                                    Uploader.flush(this@MainActivity, manual = true)
                                }
                                busy = null
                                reload++
                                when {
                                    r.skipped == "no credentials" ->
                                        toast("Enter the server ID and password in Settings first.")
                                    r.error != null -> toast("Upload failed: ${r.error}")
                                    r.sentCount == 0 -> toast("Every point has already been sent.")
                                    else -> toast("Uploaded ${r.sentCount} point(s).")
                                }
                            }
                        }
                    )

                    "map" -> MapScreen(
                        points = mapPoints, rangeKey = mapRange, truncated = mapTruncated,
                        maxDraw = MAX_DRAW, selected = pick,
                        onRange = { mapRange = it }, onSelect = { pick = it }
                    )

                    "export" -> ExportScreen(
                        formatKey = formatKey, range = range, counts = counts,
                        busy = exporting, progress = progress, lastResult = lastExport,
                        onFormat = { formatKey = it },
                        onRange = { range = it },
                        onShare = {
                            lifecycleScope.launch {
                                exporting = true; progress = null; lastExport = null
                                val res = withContext(Dispatchers.IO) {
                                    runCatching {
                                        Exporter.write(this@MainActivity, formatKey, range) { w, t ->
                                            progress = w to t
                                        }
                                    }
                                }
                                exporting = false; progress = null
                                res.onSuccess { r ->
                                    if (!r.ok || r.file == null) {
                                        toast(r.message ?: "Nothing to export")
                                    } else {
                                        lastExport = "${r.file.name}\n%,d points  ·  %,d KB".format(r.count, r.bytes / 1024)
                                        startActivity(
                                            Intent.createChooser(
                                                Exporter.shareIntent(this@MainActivity, r.file, Formats.byKey(formatKey).mime),
                                                "Export ${r.count} points"
                                            )
                                        )
                                    }
                                }.onFailure { toast("Export failed: ${it.message}") }
                            }
                        },
                        onSaveToDownloads = {
                            lifecycleScope.launch {
                                exporting = true; progress = null; lastExport = null
                                val res = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val r = Exporter.write(this@MainActivity, formatKey, range) { w, t ->
                                            progress = w to t
                                        }
                                        if (r.ok && r.file != null) {
                                            val where = Exporter.saveToDownloads(
                                                this@MainActivity, r.file, Formats.byKey(formatKey).mime
                                            )
                                            r to where
                                        } else r to null
                                    }
                                }
                                exporting = false; progress = null
                                res.onSuccess { (r, where) ->
                                    if (where == null) toast(r.message ?: "Nothing to export")
                                    else {
                                        lastExport = "$where\n%,d points  ·  %,d KB".format(r.count, r.bytes / 1024)
                                        toast("Saved to $where")
                                    }
                                }.onFailure { toast("Save failed: ${it.message}") }
                            }
                        }
                    )

                    "settings" -> SettingsScreen(
                        s = settings,
                        onChange = { next, restart, retry ->
                            settings = next
                            lifecycleScope.launch(Dispatchers.IO) {
                                Prefs.save(this@MainActivity, next)
                                if (restart) LocationService.reconfigure(this@MainActivity)
                                if (retry) {
                                    Uploader.unblock()
                                    runCatching { Uploader.flush(this@MainActivity, manual = true) }
                                }
                                withContext(Dispatchers.Main) { reload++ }
                            }
                        },
                        onOpenSite = { openUrl(settings.serverUrl) },
                        onOpenAppSettings = {
                            startActivity(
                                Intent(
                                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null)
                                )
                            )
                        },
                        onBatterySettings = { openBatterySettings() },
                        onPrune = {
                            confirm = Triple(
                                "Tidy up storage",
                                "Delete points older than 30 days that have already been uploaded. " +
                                    "Anything still queued is kept."
                            ) {
                                lifecycleScope.launch {
                                    val n = withContext(Dispatchers.IO) { db.deleteSentOlderThan(30) }
                                    toast("%,d uploaded points removed.".format(n))
                                    reload++
                                }
                            }
                        },
                        onDeleteAll = {
                            confirm = Triple(
                                "Delete every point?",
                                "This erases all stored locations on this phone, including any that " +
                                    "have not been uploaded. Points already accepted by the server stay there."
                            ) {
                                lifecycleScope.launch {
                                    val n = withContext(Dispatchers.IO) { db.deleteAll() }
                                    toast("%,d points removed.".format(n))
                                    reload++
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ 動作

    private fun askPermissionsThenStart() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            requestForeground.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBackgroundPrompt = true
            return
        }
        beginTracking()
    }

    private fun beginTracking() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            toast("Turn on location in Android settings first.")
            startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        LocationService.start(this)
        startAfterPermission?.invoke()
    }

    /** 「いま測る」。記録していない状態でも1点だけ取り込む。 */
    @SuppressLint("MissingPermission")
    private suspend fun captureOnce() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            requestForeground.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // 直近の測位があればそれを使う。無ければ GPS を1回だけ回す
        val last = sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.allProviders.contains(it) }
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

        if (last == null) {
            toast("No fix available yet. Start recording and give it a moment.")
            return
        }
        val s = Prefs.load(this)
        withContext(Dispatchers.IO) {
            val db = PointDb.get(this@MainActivity)
            val lastStored = db.lastPoint()?.let { LatLon(it.lat, it.lon) }
            val (store, moved) = Geo.shouldStore(lastStored, LatLon(last.latitude, last.longitude), s.minMoveM)
            if (!store) {
                withContext(Dispatchers.Main) {
                    toast(
                        "Still within ${moved?.toInt() ?: 0} m of the last point, so nothing was stored. " +
                            "The minimum movement can be changed in Settings."
                    )
                }
                return@withContext
            }
            db.insert(
                t = last.time.takeIf { it > 0 }?.div(1000) ?: (System.currentTimeMillis() / 1000),
                lat = last.latitude,
                lon = last.longitude,
                acc = if (last.hasAccuracy()) last.accuracy.toDouble() else null,
                alt = if (last.hasAltitude()) last.altitude else null,
                spd = if (last.hasSpeed()) last.speed.toDouble() else null,
            )
            runCatching { Uploader.flush(this@MainActivity, manual = false) }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("Could not open $url") }
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        runCatching {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { toast("Could not open the battery settings.") }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

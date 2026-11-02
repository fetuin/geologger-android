package com.usepocket.geologger.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.usepocket.geologger.MainActivity
import com.usepocket.geologger.R
import com.usepocket.geologger.data.PointDb
import com.usepocket.geologger.data.Prefs
import com.usepocket.geologger.geo.Geo
import com.usepocket.geologger.geo.LatLon
import com.usepocket.geologger.net.Uploader
import java.util.concurrent.Executors

/**
 * 位置を記録し続ける前景サービス。
 *
 * Google Play 開発者サービスは使わず、プラットフォームの LocationManager で済ませている。
 * 依存が増えず、GMS の無い端末でも動く。Android 12 以降は FUSED_PROVIDER を使い、
 * それ未満では GPS と NETWORK の両方を受けて精度の良いほうを採用する。
 */
class LocationService : Service(), LocationListener {

    companion object {
        private const val TAG = "GeoLogger/Service"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFY_ID = 1

        const val ACTION_START = "com.usepocket.geologger.START"
        const val ACTION_STOP = "com.usepocket.geologger.STOP"
        /** 設定が変わったときに間隔を入れ直すため */
        const val ACTION_RECONFIGURE = "com.usepocket.geologger.RECONFIGURE"

        /** 画面側が記録件数の変化を知るためのブロードキャスト */
        const val BROADCAST_POINT = "com.usepocket.geologger.POINT_RECORDED"

        fun start(context: Context) {
            val i = Intent(context, LocationService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LocationService::class.java).setAction(ACTION_STOP))
        }

        fun reconfigure(context: Context) {
            if (Prefs.isTracking(context)) {
                context.startService(
                    Intent(context, LocationService::class.java).setAction(ACTION_RECONFIGURE)
                )
            }
        }
    }

    // DB と通信はメインスレッドで触らない。1本の直列実行にして順序も担保する
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var lm: LocationManager
    private var listening = false
    private var storedCount = 0
    private var lastText = "Waiting for a fix…"

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                Prefs.setTracking(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONFIGURE -> {
                startForegroundCompat()
                stopListening()
                startListening()
                return START_STICKY
            }
            else -> {
                startForegroundCompat()
                Prefs.setTracking(this, true)
                startListening()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopListening()
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------ 測位

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startListening() {
        if (listening) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "権限がないので記録を開始できない")
            lastText = "Location permission is missing"
            updateNotification()
            return
        }
        val s = Prefs.load(this)
        val minTime = s.intervalSec * 1000L
        val minDist = s.minMoveM.toFloat()

        val providers = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            lm.allProviders.contains(LocationManager.FUSED_PROVIDER)
        ) {
            providers += LocationManager.FUSED_PROVIDER
        } else {
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                providers += LocationManager.GPS_PROVIDER
            }
            // 屋内や GPS が掴めない場所の保険。精度は劣るが穴が空きにくい
            if (!s.highAccuracy && lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                providers += LocationManager.NETWORK_PROVIDER
            }
        }
        if (providers.isEmpty()) {
            lastText = "No location provider on this device"
            updateNotification()
            return
        }

        try {
            for (p in providers) {
                lm.requestLocationUpdates(p, minTime, minDist, this, Looper.getMainLooper())
            }
            listening = true
            Log.i(TAG, "記録開始 providers=$providers minTime=${minTime}ms minDist=${minDist}m")
        } catch (e: SecurityException) {
            Log.w(TAG, "requestLocationUpdates が拒否された", e)
            lastText = "Location permission is missing"
            updateNotification()
        }
    }

    private fun stopListening() {
        if (!listening) return
        try {
            lm.removeUpdates(this)
        } catch (e: SecurityException) {
            // 権限が取り消された直後。止める側なので無視してよい
        }
        listening = false
    }

    override fun onLocationChanged(location: Location) {
        // コールバックはメインスレッド。DB と通信はワーカーへ投げる
        worker.execute { ingest(location) }
    }

    @Deprecated("API 29 未満の互換のため")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        Log.i(TAG, "provider が無効になった: $provider")
    }

    /**
     * 測位1件を取り込む。動いていなければ記録もしない（= 蓄積も送信も起きない）。
     * 比較の基準は「最後に記録した点」であって直前の測位ではない。
     */
    private fun ingest(location: Location) {
        val s = Prefs.load(this)
        val db = PointDb.get(this)

        val p = LatLon(location.latitude, location.longitude)
        val last = db.lastPoint()?.let { LatLon(it.lat, it.lon) }
        val (store, moved) = Geo.shouldStore(last, p, s.minMoveM)
        if (!store) {
            lastText = "Still within ${moved?.toInt() ?: 0} m — not recorded"
            updateNotification()
            return
        }

        db.insert(
            t = location.time.takeIf { it > 0 }?.div(1000) ?: (System.currentTimeMillis() / 1000),
            lat = location.latitude,
            lon = location.longitude,
            acc = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            alt = if (location.hasAltitude()) location.altitude else null,
            spd = if (location.hasSpeed()) location.speed.toDouble() else null,
        )
        storedCount++
        lastText = Geo.formatCoords(location.latitude, location.longitude)
        updateNotification()

        sendBroadcast(Intent(BROADCAST_POINT).setPackage(packageName))

        // モードに応じて送るか溜めるかは Uploader が決める
        try {
            Uploader.flush(this, manual = false)
        } catch (e: Exception) {
            Log.w(TAG, "送信に失敗", e)
        }
    }

    // ------------------------------------------------------------ 通知

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            // 記録中ずっと出る通知なので、音も出さず一覧でも静かにしておく
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while GeoLogger is recording your location"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_logging)
            .setContentTitle("GeoLogger is recording")
            .setContentText(lastText)
            .setSubText(if (storedCount > 0) "$storedCount points this session" else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFY_ID, n)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, buildNotification())
    }
}

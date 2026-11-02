package com.usepocket.geologger.geo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** 緯度経度の最小の組。DB の行にもこれで足りる場面が多い。 */
data class LatLon(val lat: Double, val lon: Double)

object Geo {

    /** 2点間の大円距離（メートル）。Pebble 版・Expo 版と同じ haversine。 */
    fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = Math.PI / 180.0
        val dLat = (bLat - aLat) * r
        val dLon = (bLon - aLon) * r
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(aLat * r) * cos(bLat * r) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * asin(min(1.0, sqrt(h)))
    }

    fun distanceMeters(a: LatLon, b: LatLon): Double =
        distanceMeters(a.lat, a.lon, b.lat, b.lon)

    /** 軌跡の各区間を足し合わせた積算距離。往復すると増え続ける。 */
    fun totalDistance(points: List<LatLon>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += distanceMeters(points[i - 1], points[i])
        return sum
    }

    /**
     * この測位を記録するか。
     * 基準は「最後に記録した点」であって直前の測位ではない。直前と比べる方式だと、
     * 閾値未満の移動を繰り返したときに永遠に基準を超えず、何キロ歩いても
     * 1点も残らなくなる。
     */
    fun shouldStore(lastStored: LatLon?, p: LatLon, minMoveM: Int): Pair<Boolean, Double?> {
        if (lastStored == null || minMoveM <= 0) return true to null
        val moved = distanceMeters(lastStored, p)
        return (moved >= minMoveM) to moved
    }

    /** 1km 未満はメートル、それ以上はキロメートル。 */
    fun formatDistance(m: Double): String = when {
        !m.isFinite() -> "--"
        m < 1000 -> "${m.roundToInt()} m"
        m < 10000 -> String.format(Locale.US, "%.2f km", m / 1000)
        else -> String.format(Locale.US, "%.1f km", m / 1000)
    }

    /** 35.68120N 139.76710E の形。南緯・西経は符号ではなく S / W で表す。 */
    fun formatCoords(lat: Double, lon: Double, decimals: Int = 5): String {
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        val f = "%.${decimals}f"
        return String.format(Locale.US, "$f$ns $f$ew", abs(lat), abs(lon))
    }

    /** 「3 min ago」「2 h ago」。送信状況の表示に使う。 */
    fun formatAgo(sec: Long?, nowSec: Long = System.currentTimeMillis() / 1000): String {
        if (sec == null || sec <= 0) return "never"
        val elapsed = nowSec - sec
        // 四捨五入だと 30 秒が「1 min ago」になるので、1分未満は切り上げない
        if (elapsed < 60) return "just now"
        val mins = (elapsed / 60.0).roundToLong()
        if (mins < 60) return "$mins min ago"
        val hours = (mins / 60.0).roundToLong()
        if (hours < 48) return "$hours h ago"
        return "${(hours / 24.0).roundToLong()} days ago"
    }
}

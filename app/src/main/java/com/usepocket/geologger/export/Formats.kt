package com.usepocket.geologger.export

import com.usepocket.geologger.data.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * エクスポートの書式。点数が多くても一度に巨大な文字列を作らないよう、
 * header / body / footer に分けてページングしながら書き足せるようにしている。
 */
sealed interface ExportFormat {
    val ext: String
    val mime: String
    val label: String
    fun header(total: Int): String
    /** offset は通算の書き出し済み件数。GeoJSON の区切りカンマに必要。 */
    fun body(points: List<Point>, offset: Int): String
    fun footer(): String
}

private val isoFmt: SimpleDateFormat
    get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

private fun iso(sec: Long): String = isoFmt.format(Date(sec * 1000))

private fun num(v: Double?, digits: Int): String =
    if (v == null || !v.isFinite()) "" else String.format(Locale.US, "%.${digits}f", v)

private fun six(v: Double): String = String.format(Locale.US, "%.6f", v)

private fun xml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/** 表計算で開く用。全項目を落とさず出す。 */
object CsvFormat : ExportFormat {
    override val ext = "csv"
    override val mime = "text/csv"
    override val label = "CSV"
    const val HEADER = "id,timestamp,datetime_iso,latitude,longitude,accuracy_m,altitude_m,speed_mps,uploaded"

    override fun header(total: Int) = "$HEADER\n"

    override fun body(points: List<Point>, offset: Int): String {
        val sb = StringBuilder(points.size * 90)
        for (p in points) {
            sb.append(p.id).append(',')
                .append(p.t).append(',')
                .append(iso(p.t)).append(',')
                .append(six(p.lat)).append(',')
                .append(six(p.lon)).append(',')
                .append(num(p.acc, 1)).append(',')
                .append(num(p.alt, 1)).append(',')
                .append(num(p.spd, 2)).append(',')
                .append(if (p.sent == 1) '1' else '0').append('\n')
        }
        return sb.toString()
    }

    override fun footer() = ""
}

/** GPS 機器や地図ソフトの標準形式。1点ごとに時刻を持てる。 */
object GpxFormat : ExportFormat {
    override val ext = "gpx"
    override val mime = "application/gpx+xml"
    override val label = "GPX"

    override fun header(total: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GeoLogger\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata>\n")
        append("    <name>").append(xml("GeoLogger track")).append("</name>\n")
        append("    <time>").append(iso(System.currentTimeMillis() / 1000)).append("</time>\n")
        append("  </metadata>\n  <trk>\n    <name>").append(xml("GeoLogger track")).append("</name>\n")
        append("    <trkseg>\n")
    }

    override fun body(points: List<Point>, offset: Int): String {
        val sb = StringBuilder(points.size * 120)
        for (p in points) {
            sb.append("      <trkpt lat=\"").append(six(p.lat))
                .append("\" lon=\"").append(six(p.lon)).append("\">\n")
            // 標高が無い測位では空タグを出さない
            if (p.alt != null && p.alt.isFinite()) {
                sb.append("        <ele>").append(num(p.alt, 1)).append("</ele>\n")
            }
            sb.append("        <time>").append(iso(p.t)).append("</time>\n")
            sb.append("      </trkpt>\n")
        }
        return sb.toString()
    }

    override fun footer() = "    </trkseg>\n  </trk>\n</gpx>\n"
}

/**
 * 地図ライブラリや GIS にそのまま読ませる用。1点を1 Feature として並べる。
 * LineString にまとめるほうがファイルは小さいが、全点の時刻を末尾に並べる必要があり、
 * 点数が多いとメモリに抱えきれない。軌跡を線で見たい場合は GPX のほうが素直。
 */
object GeoJsonFormat : ExportFormat {
    override val ext = "geojson"
    override val mime = "application/geo+json"
    override val label = "GeoJSON"

    override fun header(total: Int) = "{\"type\":\"FeatureCollection\",\"features\":[\n"

    override fun body(points: List<Point>, offset: Int): String {
        val sb = StringBuilder(points.size * 160)
        points.forEachIndexed { i, p ->
            if (offset + i > 0) sb.append(",\n")
            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                .append(six(p.lon)).append(',').append(six(p.lat))
                .append("]},\"properties\":{\"id\":").append(p.id)
                .append(",\"time\":\"").append(iso(p.t)).append('"')
                .append(",\"timestamp\":").append(p.t)
            if (p.acc != null && p.acc.isFinite()) sb.append(",\"accuracy\":").append(num(p.acc, 1))
            if (p.alt != null && p.alt.isFinite()) sb.append(",\"altitude\":").append(num(p.alt, 1))
            if (p.spd != null && p.spd.isFinite()) sb.append(",\"speed\":").append(num(p.spd, 2))
            sb.append(",\"uploaded\":").append(p.sent == 1).append("}}")
        }
        return sb.toString()
    }

    override fun footer() = "\n]}\n"
}

object Formats {
    val all = listOf(CsvFormat, GpxFormat, GeoJsonFormat)
    fun byKey(key: String): ExportFormat = when (key) {
        "gpx" -> GpxFormat
        "geojson" -> GeoJsonFormat
        else -> CsvFormat
    }
}

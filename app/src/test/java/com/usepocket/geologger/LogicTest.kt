package com.usepocket.geologger

import com.usepocket.geologger.data.Point
import com.usepocket.geologger.export.CsvFormat
import com.usepocket.geologger.export.GeoJsonFormat
import com.usepocket.geologger.export.GpxFormat
import com.usepocket.geologger.geo.Geo
import com.usepocket.geologger.geo.LatLon
import com.usepocket.geologger.net.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class GeoTest {

    @Test
    fun `距離は実測に近い`() {
        // 東京駅→新宿駅 はおよそ 6km
        val d = Geo.distanceMeters(35.6812, 139.7671, 35.6896, 139.7006)
        assertTrue("got $d", d > 5800 && d < 6400)
        assertEquals(0.0, Geo.distanceMeters(35.0, 139.0, 35.0, 139.0), 0.001)
        // 緯度0.001度 ≒ 111m
        assertEquals(111.2, Geo.distanceMeters(35.0, 139.0, 35.001, 139.0), 1.0)
    }

    @Test
    fun `積算距離は往復で増える`() {
        val tri = listOf(
            LatLon(35.68, 139.76),
            LatLon(35.69, 139.76),
            LatLon(35.68, 139.76),
        )
        assertEquals(0.0, Geo.distanceMeters(tri[0], tri[2]), 0.001)
        assertTrue(Geo.totalDistance(tri) > 2200)
    }

    @Test
    fun `移動判定の基準は最後に記録した点`() {
        // 29m 刻みで進む。閾値30mなので 0 / 58 / 116m が残る＝進むたびに基準が更新される
        var lastStored: LatLon? = null
        var kept = 0
        for (i in 0..5) {
            val p = LatLon(35.68 + i * 29 / 111320.0, 139.76)
            val (store, _) = Geo.shouldStore(lastStored, p, 30)
            if (store) {
                kept++
                lastStored = p
            }
        }
        assertEquals(3, kept)

        // 直前の測位を基準にすると1点しか残らない（これを避けるための設計）
        var prev: LatLon? = null
        var keptWrong = 0
        for (i in 0..5) {
            val p = LatLon(35.68 + i * 29 / 111320.0, 139.76)
            if (Geo.shouldStore(prev, p, 30).first) keptWrong++
            prev = p
        }
        assertEquals(1, keptWrong)
    }

    @Test
    fun `閾値0ならすべて記録する`() {
        val a = LatLon(35.68, 139.76)
        assertTrue(Geo.shouldStore(a, LatLon(35.680001, 139.76), 0).first)
    }

    @Test
    fun `書式`() {
        assertEquals("999 m", Geo.formatDistance(999.0))
        assertEquals("1.00 km", Geo.formatDistance(1000.0))
        assertEquals("87.6 km", Geo.formatDistance(87600.0))
        assertEquals("35.68120N 139.76710E", Geo.formatCoords(35.6812, 139.7671))
        assertEquals("33.86880S 151.20930W", Geo.formatCoords(-33.8688, -151.2093))
        val now = 1_800_000_000L
        assertEquals("just now", Geo.formatAgo(now - 30, now))
        assertEquals("10 min ago", Geo.formatAgo(now - 600, now))
        assertEquals("2 h ago", Geo.formatAgo(now - 7200, now))
        assertEquals("never", Geo.formatAgo(null, now))
    }
}

class ProtocolTest {

    private val base = Protocol.UploadSettings(
        serverUrl = "https://example.com/geologger.cgi",
        userId = "taro",
        password = "himitsu",
    )

    private fun pt(id: Long, t: Long, lat: Double, lon: Double) = Point(id, t, lat, lon)

    @Test
    fun `1点なら lat_lon 形式`() {
        val body = Protocol.buildBody(listOf(pt(1, 1791357596, 35.68, 139.7616)), base)
        assertEquals("mode=add&id=taro&pw=himitsu&lat=35.680000&lon=139.761600", body)
        // c / t / note は送らない
        assertFalse(body.contains("&c="))
        assertFalse(body.contains("&t="))
        assertFalse(body.contains("note"))
    }

    @Test
    fun `分類コードと t を付けられる`() {
        val body = Protocol.buildBody(
            listOf(pt(1, 1791357596, 35.68, 139.7616)),
            base.copy(classCode = "A1B", sendTime = true)
        )
        assertTrue(body.endsWith("&lat=35.680000&lon=139.761600&c=A1B&t=1791357596"))
    }

    @Test
    fun `複数点は d 形式`() {
        val pts = listOf(pt(1, 100, 35.1, 139.1), pt(2, 200, 35.2, 139.2))
        val body = Protocol.buildBody(pts, base)
        assertEquals(2, Regex("&d=").findAll(body).count())
        val first = URLDecoder.decode(body.split("&d=")[1], "UTF-8")
        // 分類コードが空なら3項目目は空のまま（末尾カンマになる）
        assertEquals("35.100000,139.100000,", first)
    }

    @Test
    fun `d の4項目目が t`() {
        val pts = listOf(pt(1, 100, 35.1, 139.1), pt(2, 200, 35.2, 139.2))
        val body = Protocol.buildBody(pts, base.copy(classCode = "A1B", sendTime = true))
        assertEquals("35.100000,139.100000,A1B,100", URLDecoder.decode(body.split("&d=")[1], "UTF-8"))
    }

    @Test
    fun `ID とパスワードはエスケープされる`() {
        val body = Protocol.buildBody(
            listOf(pt(1, 1, 0.0, 0.0)),
            base.copy(userId = "a&b", password = "p=w?")
        )
        assertTrue(body.contains("id=a%26b"))
        assertTrue(body.contains("pw=p%3Dw%3F"))
    }

    @Test
    fun `応答の判定`() {
        assertEquals(Protocol.Verdict.OK, Protocol.classify(200, "OK 1 registered").verdict)
        assertEquals(Protocol.Verdict.PERMANENT, Protocol.classify(400, "ERROR bad id").verdict)
        assertEquals(Protocol.Verdict.PERMANENT, Protocol.classify(401, "").verdict)
        assertEquals(Protocol.Verdict.TEMPORARY, Protocol.classify(503, "busy").verdict)
        assertEquals(Protocol.Verdict.TEMPORARY, Protocol.classify(500, "").verdict)
    }

    @Test
    fun `送信判定と1回あたりの件数`() {
        assertTrue(Protocol.shouldFlush(false, 1, 50))
        assertFalse(Protocol.shouldFlush(false, 0, 50))
        assertFalse(Protocol.shouldFlush(true, 9, 10))
        assertTrue(Protocol.shouldFlush(true, 10, 10))
        // batch は指定個数ずつ、realtime の backlog は50ずつ
        assertEquals(10, Protocol.chunkSize(true, 10))
        assertEquals(50, Protocol.chunkSize(false, 10))
    }

    @Test
    fun `入力の検証`() {
        assertNull(Protocol.validateClassCode(""))
        assertNull(Protocol.validateClassCode("A1B"))
        assertNotNull(Protocol.validateClassCode("AB"))
        assertNotNull(Protocol.validateClassCode("ABCD"))
        assertNull(Protocol.checkUrl("https://example.com/x.cgi"))
        assertNotNull(Protocol.checkUrl("http://example.com/x.cgi"))   // 警告が出る
        assertNotNull(Protocol.checkUrl("example.com"))
    }
}

class FormatTest {

    private val rows = listOf(
        Point(1, 1791357596, 35.6812, 139.7671, 12.3, 40.5, 1.25, 1),
        Point(2, 1791357896, -33.8688, -151.2093, null, null, null, 0),
    )

    @Test
    fun `CSV は欠損を空欄にする`() {
        val csv = CsvFormat.header(2) + CsvFormat.body(rows, 0) + CsvFormat.footer()
        val lines = csv.trim().split("\n")
        assertEquals(3, lines.size)
        assertEquals(
            "1,1791357596,2026-10-07T07:19:56Z,35.681200,139.767100,12.3,40.5,1.25,1",
            lines[1]
        )
        assertTrue(lines[2].endsWith(",,,,0"))
        // 欠損があっても列数は変わらない
        assertEquals(9, lines[2].split(",").size)
    }

    @Test
    fun `GPX は妥当な外枠と trkpt を持つ`() {
        val gpx = GpxFormat.header(2) + GpxFormat.body(rows, 0) + GpxFormat.footer()
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("lat=\"-33.868800\" lon=\"-151.209300\""))
        // 標高が無い点では空タグを出さない
        assertFalse(gpx.contains("<ele></ele>"))
        assertEquals(1, Regex("<ele>").findAll(gpx).count())
    }

    @Test
    fun `GeoJSON はページをまたいでも妥当`() {
        // 2ページに分けて書き出す。offset を誤るとカンマの位置が崩れて壊れた JSON になる
        val sb = StringBuilder(GeoJsonFormat.header(2))
        sb.append(GeoJsonFormat.body(rows.subList(0, 1), 0))
        sb.append(GeoJsonFormat.body(rows.subList(1, 2), 1))
        sb.append(GeoJsonFormat.footer())
        val json = sb.toString()

        // 波括弧・角括弧の対応と、Feature 数の確認（JSON パーサを足さず素で検証する）
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
        assertEquals(2, Regex("\"type\":\"Feature\"").findAll(json).count())
        assertEquals(1, Regex("\\},\\n\\{").findAll(json).count())   // 区切りカンマは1つだけ
        assertTrue(json.contains("\"coordinates\":[139.767100,35.681200]"))  // 経度, 緯度の順
        assertTrue(json.contains("\"uploaded\":true"))
        assertTrue(json.contains("\"uploaded\":false"))
        // 欠損項目は省く
        assertEquals(1, Regex("\"accuracy\"").findAll(json).count())
    }

    @Test
    fun `0点でも壊れない`() {
        val json = GeoJsonFormat.header(0) + GeoJsonFormat.body(emptyList(), 0) + GeoJsonFormat.footer()
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
        val csv = CsvFormat.header(0) + CsvFormat.body(emptyList(), 0)
        assertEquals(1, csv.trim().split("\n").size)
    }

    @Test
    fun `大量データでも整合する`() {
        val many = (1..5000).map {
            Point(it.toLong(), 1_700_000_000L + it * 60, 35.0 + it * 0.0001, 139.0, 5.0, null, null, it % 2)
        }
        val sb = StringBuilder(GeoJsonFormat.header(many.size))
        var off = 0
        // 2000件ずつページング（実際のエクスポートと同じ刻み）
        many.chunked(2000).forEach { page ->
            sb.append(GeoJsonFormat.body(page, off))
            off += page.size
        }
        sb.append(GeoJsonFormat.footer())
        val json = sb.toString()
        assertEquals(5000, Regex("\"type\":\"Feature\"").findAll(json).count())
        assertEquals(4999, Regex("\\},\\n\\{").findAll(json).count())
        assertEquals(json.count { it == '{' }, json.count { it == '}' })

        val csv = CsvFormat.header(many.size) + CsvFormat.body(many, 0)
        assertEquals(5001, csv.trim().split("\n").size)
    }
}

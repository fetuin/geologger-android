package com.usepocket.geologger.net

import com.usepocket.geologger.data.Point
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.Locale

/**
 * geologger.cgi の登録API（mode=add）に合わせた本文の組み立てと応答の判定。
 *
 * サーバー仕様:
 *   1件  mode=add&id=..&pw=..&lat=..&lon=..[&c=..][&t=..]
 *   一括 mode=add&id=..&pw=..&d=<緯度>,<経度>,<分類>[,<UNIX秒>]&d=...
 *   note は受け付けない。t はサーバーが対応してから送る（未対応なら 400）。
 */
object Protocol {

    data class UploadSettings(
        val serverUrl: String,
        val userId: String,
        val password: String,
        val classCode: String = "",
        val sendTime: Boolean = false,
    )

    enum class Verdict { OK, TEMPORARY, PERMANENT }

    data class Response(val verdict: Verdict, val message: String)

    private fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (e: UnsupportedEncodingException) {
        s
    }

    private fun six(v: Double): String = String.format(Locale.US, "%.6f", v)

    fun buildBody(points: List<Point>, s: UploadSettings): String {
        val parts = ArrayList<String>()
        parts += "mode=add"
        parts += "id=${enc(s.userId)}"
        parts += "pw=${enc(s.password)}"

        if (points.size == 1) {
            val p = points[0]
            parts += "lat=${six(p.lat)}"
            parts += "lon=${six(p.lon)}"
            if (s.classCode.isNotEmpty()) parts += "c=${enc(s.classCode)}"
            if (s.sendTime) parts += "t=${p.t}"
        } else {
            for (p in points) {
                val fields = ArrayList<String>()
                fields += six(p.lat)
                fields += six(p.lon)
                fields += s.classCode
                if (s.sendTime) fields += p.t.toString()
                parts += "d=${enc(fields.joinToString(","))}"
            }
        }
        // note は送らない（送るとサーバーが 400 を返す仕様）
        return parts.joinToString("&")
    }

    /**
     * 応答の扱いを決める。
     *  OK        … 送信済みとして記録してよい
     *  PERMANENT … 再送しても直らない（ID/パスワード違い、t 非対応など）。自動再送を止める
     *  TEMPORARY … 一時的な障害。次の機会に再送する
     */
    fun classify(status: Int, body: String?): Response {
        val text = (body ?: "").replace(Regex("\\s+"), " ").trim().take(80)
        return when {
            status in 200..299 -> Response(Verdict.OK, text)
            status in 400..499 -> Response(Verdict.PERMANENT, "HTTP $status $text".trim())
            else -> Response(Verdict.TEMPORARY, "HTTP $status $text".trim())
        }
    }

    /** 分類コードは空か、ちょうど3文字。 */
    fun validateClassCode(code: String): String? {
        val c = code.trim()
        return if (c.isEmpty() || c.length == 3) null
        else "Category code must be blank or exactly 3 characters."
    }

    /**
     * 送信先 URL の点検。http:// だと 301 で https:// に飛ばされて POST 本文が
     * 失われるため、平文のまま使うのは危ない。
     */
    fun checkUrl(url: String): String? {
        val u = url.trim()
        if (!Regex("^https?://.+").containsMatchIn(u)) {
            return "Enter a full URL starting with http:// or https://"
        }
        if (u.startsWith("http://", ignoreCase = true)) {
            return "Plain http:// can be redirected to https://, which drops the POST body. " +
                "Use https:// if the server supports it."
        }
        return null
    }

    /** いま送るべきか。realtime は1点でも送る。batch は batchSize まで待つ。 */
    fun shouldFlush(batchMode: Boolean, unsent: Int, batchSize: Int): Boolean {
        if (unsent <= 0) return false
        return if (batchMode) unsent >= batchSize else true
    }

    /**
     * 1回の POST に載せる点数。
     * realtime でも圏外明けで溜まっているときは束ねたほうが速いので上限50でまとめる。
     * 結果が1点なら buildBody が自動で lat/lon 形式（サーバー時刻が実測に近い）を選ぶ。
     */
    fun chunkSize(batchMode: Boolean, batchSize: Int): Int =
        if (batchMode) batchSize else 50
}

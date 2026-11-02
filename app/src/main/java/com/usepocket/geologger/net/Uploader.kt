package com.usepocket.geologger.net

import android.content.Context
import android.util.Log
import com.usepocket.geologger.data.PointDb
import com.usepocket.geologger.data.Prefs
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class UploadResult(
    val sentCount: Int = 0,
    val requests: Int = 0,
    val error: String? = null,
    val permanent: Boolean = false,
    val skipped: String? = null,
)

data class UploadStatus(
    val lastUploadAt: Long?,
    val lastError: String,
    val unsent: Int,
    val blocked: Boolean,
)

/**
 * 未送信を送る。画面からもサービスからも呼ばれる。
 * 通信するので必ずワーカースレッドから呼ぶこと。
 */
object Uploader {
    private const val TAG = "GeoLogger/Upload"
    private const val MAX_REQUESTS = 40

    // 恒久的エラー（ID/パスワード違いなど）を食らったら自動再送を止める。
    // 誤った認証情報を延々と投げ続けないため。設定を直すか手動送信で解除する。
    @Volatile
    var blocked: Boolean = false
        private set

    @Volatile
    private var running = false

    fun unblock() {
        blocked = false
    }

    @Synchronized
    fun flush(context: Context, manual: Boolean): UploadResult {
        if (running) return UploadResult(skipped = "already running")

        val settings = Prefs.load(context)
        val db = PointDb.get(context)

        if (!settings.canUpload) {
            db.setMeta("lastError", "Server ID / password not set")
            return UploadResult(skipped = "no credentials")
        }
        if (manual) blocked = false
        if (blocked) return UploadResult(skipped = "blocked by a permanent error")

        val unsent = db.unsentCount()
        if (!manual && !Protocol.shouldFlush(settings.batchMode, unsent, settings.batchSize)) {
            return UploadResult(skipped = "waiting for the batch to fill")
        }

        running = true
        try {
            val limit = Protocol.chunkSize(settings.batchMode, settings.batchSize)
            val up = settings.toUploadSettings()
            var sentCount = 0
            var requests = 0
            var error: String? = null
            var permanent = false

            for (i in 0 until MAX_REQUESTS) {
                val rows = db.claimUnsent(limit)
                if (rows.isEmpty()) break

                // 2周目以降、まとめ送りで残りが batchSize 未満なら、手動でなければ
                // 中断して次の機会に回す（半端な数で送らない）
                if (!manual && settings.batchMode && rows.size < settings.batchSize && i > 0) {
                    db.releaseClaim(rows.map { it.id })
                    break
                }

                val body = Protocol.buildBody(rows, up)
                requests++

                val res = try {
                    post(settings.serverUrl, body)
                } catch (e: Exception) {
                    db.releaseClaim(rows.map { it.id })
                    error = "Network error: ${(e.message ?: e.toString()).take(60)}"
                    break
                }

                val verdict = Protocol.classify(res.first, res.second)
                if (verdict.verdict == Protocol.Verdict.OK) {
                    db.markSent(rows.map { it.id })
                    sentCount += rows.size
                } else {
                    // 送れなかったぶんは捨てずに未送信へ戻す
                    db.releaseClaim(rows.map { it.id })
                    error = verdict.message
                    permanent = verdict.verdict == Protocol.Verdict.PERMANENT
                    break
                }
            }

            if (error != null) {
                blocked = permanent
                db.setMeta("lastError", error)
                Log.w(TAG, "upload failed: $error (permanent=$permanent)")
            } else {
                blocked = false
                db.setMeta("lastError", "")
                if (sentCount > 0) {
                    db.setMeta("lastUploadAt", (System.currentTimeMillis() / 1000).toString())
                    Log.i(TAG, "uploaded $sentCount points in $requests request(s)")
                }
            }
            return UploadResult(sentCount, requests, error, permanent)
        } finally {
            running = false
        }
    }

    fun status(context: Context): UploadStatus {
        val db = PointDb.get(context)
        return UploadStatus(
            lastUploadAt = db.getMeta("lastUploadAt")?.toLongOrNull(),
            lastError = db.getMeta("lastError") ?: "",
            unsent = db.unsentCount(),
            blocked = blocked,
        )
    }

    /** @return status code と本文 */
    private fun post(urlStr: String, body: String): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept-Charset", "UTF-8")
            // http:// のリダイレクトを追わせない。POST が GET に変わり本文が失われるため
            instanceFollowRedirects = false
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}

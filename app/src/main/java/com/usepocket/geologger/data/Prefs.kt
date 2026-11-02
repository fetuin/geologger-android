package com.usepocket.geologger.data

import android.content.Context
import com.usepocket.geologger.net.Protocol

/** 設定。サービスからも画面からも読むので SharedPreferences に置く。 */
data class Settings(
    val serverUrl: String = DEFAULT_URL,
    val userId: String = "",
    val password: String = "",
    val classCode: String = "",      // 空なら c を送らない。入れるならちょうど3文字
    val batchMode: Boolean = false,  // false = 動いたら毎回 / true = まとめて
    val batchSize: Int = 50,         // 5〜50
    val sendTime: Boolean = false,   // t を送る。サーバーが未対応のうちは off
    val minMoveM: Int = 30,          // これ未満の移動は記録も送信もしない
    val intervalSec: Int = 60,       // 測位の最短間隔
    val highAccuracy: Boolean = false,
) {
    val canUpload: Boolean
        get() = serverUrl.isNotEmpty() && userId.isNotEmpty() && password.isNotEmpty()

    fun toUploadSettings() = Protocol.UploadSettings(
        serverUrl = serverUrl,
        userId = userId,
        password = password,
        classCode = classCode,
        sendTime = sendTime,
    )

    companion object {
        const val DEFAULT_URL = "https://usepocket.com/geologger/geologger.cgi"
    }
}

object Prefs {
    private const val FILE = "geologger.settings"

    @Volatile
    private var cache: Settings? = null

    fun load(context: Context): Settings {
        cache?.let { return it }
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val s = Settings(
            serverUrl = p.getString("serverUrl", Settings.DEFAULT_URL) ?: Settings.DEFAULT_URL,
            userId = p.getString("userId", "") ?: "",
            password = p.getString("password", "") ?: "",
            classCode = p.getString("classCode", "") ?: "",
            batchMode = p.getBoolean("batchMode", false),
            batchSize = p.getInt("batchSize", 50).coerceIn(5, 50),
            sendTime = p.getBoolean("sendTime", false),
            minMoveM = p.getInt("minMoveM", 30).coerceIn(0, 500),
            intervalSec = p.getInt("intervalSec", 60).coerceIn(10, 3600),
            highAccuracy = p.getBoolean("highAccuracy", false),
        )
        cache = s
        return s
    }

    fun save(context: Context, s: Settings): Settings {
        val clean = s.copy(
            serverUrl = s.serverUrl.trim(),
            userId = s.userId.trim(),
            classCode = s.classCode.trim(),
            batchSize = s.batchSize.coerceIn(5, 50),
            minMoveM = s.minMoveM.coerceIn(0, 500),
            intervalSec = s.intervalSec.coerceIn(10, 3600),
        )
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            putString("serverUrl", clean.serverUrl)
            putString("userId", clean.userId)
            putString("password", clean.password)
            putString("classCode", clean.classCode)
            putBoolean("batchMode", clean.batchMode)
            putInt("batchSize", clean.batchSize)
            putBoolean("sendTime", clean.sendTime)
            putInt("minMoveM", clean.minMoveM)
            putInt("intervalSec", clean.intervalSec)
            putBoolean("highAccuracy", clean.highAccuracy)
            apply()
        }
        cache = clean
        return clean
    }

    /** 記録中かどうかはサービスの実状なので別キーで持つ。 */
    fun setTracking(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("tracking", on).apply()
    }

    fun isTracking(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean("tracking", false)
}

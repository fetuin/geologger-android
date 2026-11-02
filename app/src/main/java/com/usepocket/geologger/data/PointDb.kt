package com.usepocket.geologger.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 1測位。acc / alt / spd は端末が返さないことがあるので null を許す。 */
data class Point(
    val id: Long,
    val t: Long,
    val lat: Double,
    val lon: Double,
    val acc: Double? = null,
    val alt: Double? = null,
    val spd: Double? = null,
    val sent: Int = 0,
)

data class Stats(
    val total: Int,
    val sent: Int,
    val unsent: Int,
    val firstT: Long?,
    val lastT: Long?,
)

/**
 * SQLite による蓄積。点数が数十万件になっても耐えるよう、
 * 「保存先」と「送信キュー」を1つのテーブルで兼ねている（sent 列で区別）。
 *
 * sent: 0 = 未送信 / 1 = 送信済み / 2 = 送信中（クラッシュ時は起動時に 0 へ戻す）
 */
class PointDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "geologger.db", null, 1) {

    companion object {
        @Volatile
        private var instance: PointDb? = null

        fun get(context: Context): PointDb =
            instance ?: synchronized(this) {
                instance ?: PointDb(context).also {
                    instance = it
                    // 前回の送信中にアプリが落ちた場合の取りこぼしを戻す
                    it.writableDatabase.execSQL("UPDATE points SET sent = 0 WHERE sent = 2")
                }
            }

        private const val COLS = "id, t, lat, lon, acc, alt, spd, sent"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // 書き込みと読み出しが競合しにくくなる。記録中に一覧を開いても固まらない
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE points (
              id   INTEGER PRIMARY KEY AUTOINCREMENT,
              t    INTEGER NOT NULL,
              lat  REAL    NOT NULL,
              lon  REAL    NOT NULL,
              acc  REAL,
              alt  REAL,
              spd  REAL,
              sent INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_points_t ON points(t)")
        db.execSQL("CREATE INDEX idx_points_sent ON points(sent)")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // まだ移行なし
    }

    // -------------------------------------------------------------- 記録

    fun insert(t: Long, lat: Double, lon: Double, acc: Double?, alt: Double?, spd: Double?): Long {
        val v = ContentValues().apply {
            put("t", t); put("lat", lat); put("lon", lon)
            if (acc != null) put("acc", acc) else putNull("acc")
            if (alt != null) put("alt", alt) else putNull("alt")
            if (spd != null) put("spd", spd) else putNull("spd")
            put("sent", 0)
        }
        return writableDatabase.insert("points", null, v)
    }

    /** 直近に記録した点。移動判定の基準に使う。 */
    fun lastPoint(): Point? = query("SELECT $COLS FROM points ORDER BY id DESC LIMIT 1").firstOrNull()

    // -------------------------------------------------------- 送信キュー

    /**
     * 未送信のうち古い順に limit 件を「送信中」にして返す。
     * 取得と印付けを1トランザクションで行うので、画面側とサービスが同時に走っても
     * 同じ点を二重に送らない。
     */
    fun claimUnsent(limit: Int): List<Point> {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val rows = query("SELECT $COLS FROM points WHERE sent = 0 ORDER BY id ASC LIMIT $limit")
            if (rows.isNotEmpty()) {
                db.execSQL("UPDATE points SET sent = 2 WHERE id IN (${rows.joinToString(",") { it.id.toString() }})")
            }
            db.setTransactionSuccessful()
            rows
        } finally {
            db.endTransaction()
        }
    }

    fun markSent(ids: List<Long>) = setSent(ids, 1)

    /** 送信に失敗した点を未送信へ戻す。 */
    fun releaseClaim(ids: List<Long>) = setSent(ids, 0)

    private fun setSent(ids: List<Long>, value: Int) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL(
            "UPDATE points SET sent = $value WHERE id IN (${ids.joinToString(",")})"
        )
    }

    fun unsentCount(): Int = scalar("SELECT COUNT(*) FROM points WHERE sent != 1")

    // -------------------------------------------------------- 読み出し

    /** 一覧・地図用。新しい順に取る。 */
    fun recent(limit: Int, sinceSec: Long? = null): List<Point> =
        if (sinceSec != null) {
            query("SELECT $COLS FROM points WHERE t >= $sinceSec ORDER BY t DESC LIMIT $limit")
        } else {
            query("SELECT $COLS FROM points ORDER BY t DESC LIMIT $limit")
        }

    /** エクスポート用のページング。id 順に少しずつ取り出す。 */
    fun page(afterId: Long, limit: Int, sinceSec: Long?, unsentOnly: Boolean): List<Point> {
        val where = StringBuilder("id > $afterId")
        if (sinceSec != null) where.append(" AND t >= $sinceSec")
        if (unsentOnly) where.append(" AND sent != 1")
        return query("SELECT $COLS FROM points WHERE $where ORDER BY id ASC LIMIT $limit")
    }

    fun stats(): Stats {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN sent = 1 THEN 1 ELSE 0 END), MIN(t), MAX(t) FROM points",
            null
        ).use { c ->
            if (!c.moveToFirst()) return Stats(0, 0, 0, null, null)
            val total = c.getInt(0)
            val sent = if (c.isNull(1)) 0 else c.getInt(1)
            val first = if (c.isNull(2)) null else c.getLong(2)
            val last = if (c.isNull(3)) null else c.getLong(3)
            return Stats(total, sent, total - sent, first, last)
        }
    }

    fun countFor(sinceSec: Long?, unsentOnly: Boolean): Int {
        val conds = ArrayList<String>()
        if (sinceSec != null) conds += "t >= $sinceSec"
        if (unsentOnly) conds += "sent != 1"
        val where = if (conds.isEmpty()) "" else " WHERE ${conds.joinToString(" AND ")}"
        return scalar("SELECT COUNT(*) FROM points$where")
    }

    // ------------------------------------------------------ 削除・メタ

    /** 送信済みのうち、指定日数より古いものを削除。容量の手入れ用。 */
    fun deleteSentOlderThan(days: Int): Int {
        val cutoff = System.currentTimeMillis() / 1000 - days * 86400L
        val n = writableDatabase.delete("points", "sent = 1 AND t < ?", arrayOf(cutoff.toString()))
        writableDatabase.execSQL("VACUUM")
        return n
    }

    /** 全消去。未送信ぶんも含めて消えるので、画面側で確認を取ってから呼ぶ。 */
    fun deleteAll(): Int {
        val n = writableDatabase.delete("points", null, null)
        writableDatabase.execSQL("VACUUM")
        return n
    }

    fun getMeta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    fun setMeta(key: String, value: String?) {
        val v = ContentValues().apply {
            put("key", key)
            if (value != null) put("value", value) else putNull("value")
        }
        writableDatabase.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------------------------------------------------- 内部

    private fun scalar(sql: String): Int =
        readableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun query(sql: String): List<Point> =
        readableDatabase.rawQuery(sql, null).use { c ->
            val out = ArrayList<Point>(c.count)
            while (c.moveToNext()) out += c.toPoint()
            out
        }

    private fun Cursor.toPoint() = Point(
        id = getLong(0),
        t = getLong(1),
        lat = getDouble(2),
        lon = getDouble(3),
        acc = if (isNull(4)) null else getDouble(4),
        alt = if (isNull(5)) null else getDouble(5),
        spd = if (isNull(6)) null else getDouble(6),
        sent = getInt(7),
    )
}

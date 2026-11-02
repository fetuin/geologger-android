package com.usepocket.geologger.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.usepocket.geologger.data.PointDb
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportRange(val key: String, val label: String) {
    ALL("all", "All points"),
    TODAY("today", "Today"),
    WEEK("week", "Last 7 days"),
    UNSENT("unsent", "Not uploaded yet"),
}

data class ExportResult(
    val ok: Boolean,
    val message: String? = null,
    val file: File? = null,
    val count: Int = 0,
    val bytes: Long = 0,
)

/**
 * エクスポート。DB からページングしながらファイルへ書き足すので、
 * 点数が多くてもメモリを圧迫しない。
 */
object Exporter {
    private const val PAGE = 2000

    private fun sinceFor(range: ExportRange): Long? = when (range) {
        ExportRange.TODAY -> {
            val c = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            c.timeInMillis / 1000
        }
        ExportRange.WEEK -> System.currentTimeMillis() / 1000 - 7 * 86400
        else -> null
    }

    private fun unsentOnly(range: ExportRange) = range == ExportRange.UNSENT

    fun count(context: Context, range: ExportRange): Int =
        PointDb.get(context).countFor(sinceFor(range), unsentOnly(range))

    /**
     * ファイルを書き出す。共有や保存はこの結果を使って呼び出し側が行う。
     * @param onProgress 書き出し済み件数と総数
     */
    fun write(
        context: Context,
        formatKey: String,
        range: ExportRange,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): ExportResult {
        val fmt = Formats.byKey(formatKey)
        val db = PointDb.get(context)
        val since = sinceFor(range)
        val unsent = unsentOnly(range)
        val total = db.countFor(since, unsent)
        if (total == 0) {
            return ExportResult(false, "There are no points in that range yet.")
        }

        // FileProvider で公開する場所（file_paths.xml の cache-path と合わせる）
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // 前回のぶんは消しておく。共有し終わったファイルを溜めない
        dir.listFiles()?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = File(dir, "geologger-${range.key}-$stamp.${fmt.ext}")

        file.bufferedWriter().use { w ->
            w.write(fmt.header(total))
            var afterId = 0L
            var written = 0
            while (true) {
                val rows = db.page(afterId, PAGE, since, unsent)
                if (rows.isEmpty()) break
                // offset を渡すのが要点。GeoJSON は2件目以降の先頭にカンマが必要なので、
                // ページをまたいでも通算の位置が分からないと壊れた JSON になる
                w.write(fmt.body(rows, written))
                written += rows.size
                afterId = rows.last().id
                onProgress?.invoke(written, total)
            }
            w.write(fmt.footer())
        }

        return ExportResult(true, null, file, total, file.length())
    }

    /** 共有シートへ渡す Intent。 */
    fun shareIntent(context: Context, file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 端末の「ダウンロード」へコピーする。共有シートを経由せずに
     * ファイルとして残したいときのため。
     */
    fun saveToDownloads(context: Context, file: File, mime: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the file in Downloads")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Downloads/${file.name}"
        }
        // Android 9 以下は直接書ける
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val dest = File(dir, file.name)
        file.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }
}

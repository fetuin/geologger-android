package com.usepocket.geologger.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.usepocket.geologger.data.PointDb
import com.usepocket.geologger.data.Prefs

/**
 * 端末の再起動やアプリの更新のあとに、記録を自動で再開する。
 *
 * START_STICKY は「OS がサービスだけを殺した」ときの復帰しか面倒を見ない。
 * 端末を再起動するとプロセスごと消え、誰も開始し直さないので、
 * ここで BOOT_COMPLETED を受けて自分で立て直す必要がある。
 *
 * 直前まで記録していた（Prefs の tracking が true）ときだけ再開する。
 * ユーザーが自分で止めていたなら、再起動で勝手に始まってはいけない。
 *
 * LOCKED_BOOT_COMPLETED は受けていない。設定も記録先も通常の（ロック解除後に
 * 読める）領域に置いているため、解除前に起こしても読めないまま失敗する。
 * BOOT_COMPLETED はロック解除後に届くので、そちらだけで足りる。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GeoLogger/Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        if (!Prefs.isTracking(context)) {
            Log.i(TAG, "$action: 記録中ではなかったので何もしない")
            return
        }

        // 再起動のあいだに権限を取り消されていることがある。
        // その状態でサービスを立てても測位できないので、理由を残して諦める。
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "$action: 位置情報の権限が無いので再開しない")
            PointDb.get(context).setMeta(
                "lastTrackError",
                "再起動後に自動再開できませんでした（位置情報の権限がありません）"
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 「アプリの使用中のみ許可」のまま。画面を開かないと記録できないので
            // 立ち上げても意味がない。気づけるように理由を残す。
            Log.w(TAG, "$action: 背景位置が許可されていないので再開しない")
            PointDb.get(context).setMeta(
                "lastTrackError",
                "再起動後に自動再開できませんでした（位置情報を「常に許可」にしてください）"
            )
            return
        }

        // BOOT_COMPLETED と MY_PACKAGE_REPLACED は、Android 12 以降でも
        // 背景から前景サービスを開始してよい数少ない例外に入っている。
        val ok = LocationService.start(context)
        Log.i(TAG, "$action: 記録を再開 → ${if (ok) "成功" else "失敗"}")
        if (ok) {
            PointDb.get(context).setMeta("lastTrackError", "")
            PointDb.get(context).setMeta(
                "lastAutoRestart",
                (System.currentTimeMillis() / 1000).toString()
            )
        }
    }
}

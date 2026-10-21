package com.lxithral.adbtools.logic

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.topjohnwu.superuser.Shell

/**
 * `Settings.Global` 读写。
 *
 * 读：普通 app 就能读 global，不需要任何权限，所以读路径永远走 ContentResolver，零开销。
 * 写：优先 ContentResolver（需要 WRITE_SECURE_SETTINGS，可在电脑上一次性授予：
 *     `adb shell pm grant com.lxithral.adbtools android.permission.WRITE_SECURE_SETTINGS`），
 *     没有权限（SecurityException）再退回 root shell。
 *
 * 写完成后一律回读校验，避免"命令返回 0 但实际没生效"这种静默失败。
 */
object SettingsAccess {

    private const val TAG = "SettingsAccess"

    fun getInt(context: Context, key: String): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, key)
    }.getOrNull()

    fun isEnabled(context: Context, key: String): Boolean = getInt(context, key) == 1

    fun putInt(context: Context, key: String, value: Int): Boolean {
        val direct = runCatching {
            Settings.Global.putInt(context.contentResolver, key, value)
        }.isSuccess

        if (direct && getInt(context, key) == value) {
            Log.d(TAG, "putInt($key=$value) ok via ContentResolver")
            return true
        }

        // ContentResolver 没权限：先按当前 shell 试一次 root（已 Root 设备走这里）
        if (writeViaRootShell(context, key, value)) {
            Log.d(TAG, "putInt($key=$value) ok via root shell")
            return true
        }

        // 还失败：多半是启动时还没授权，libsu 缓存了非 root shell。
        // 重新握手一次再试，避免"明明给了 root 还是写不进去"。
        if (Shell.isAppGrantedRoot() != true && reinitRootShell()) {
            Log.d(TAG, "putInt($key=$value) retry after root shell re-init")
            if (writeViaRootShell(context, key, value)) {
                Log.d(TAG, "putInt($key=$value) ok via root shell (after re-init)")
                return true
            }
        }

        Log.e(TAG, "putInt($key=$value) failed: no WRITE_SECURE_SETTINGS and no usable root shell")
        return false
    }

    private fun writeViaRootShell(context: Context, key: String, value: Int): Boolean {
        executeShellCommand("settings put global $key $value", context)
        return getInt(context, key) == value
    }
}

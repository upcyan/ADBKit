package com.lxithral.adbtools.logic

import android.content.Context

object AdbManager {

    /**
     * 获取 USB 调试状态。
     * 读 global settings 不需要权限，直接走 ContentResolver，不依赖 Root。
     */
    fun isUsbAdbEnabled(context: Context): Boolean = SettingsAccess.isEnabled(context, "adb_enabled")

}

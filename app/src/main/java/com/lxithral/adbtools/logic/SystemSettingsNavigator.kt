package com.lxithral.adbtools.logic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/** 需要外部跳转的系统设置页 */
enum class SystemSettingsPage {
    /** 开发者选项总入口 */
    DeveloperOptions,

    /** USB 调试（系统里没有独立页面，落在开发者选项内） */
    UsbDebugging,

    /** 无线调试（配对码 / IP:端口） */
    WirelessDebugging,
}

/**
 * 系统设置页跳转。
 *
 * AOSP 没有对外暴露"USB 调试""无线调试"的公开 Action，各 ROM 又各有各的组件名，
 * 所以这里按候选顺序逐个尝试：先试精确组件，再退回公开 Action，最后退到开发者选项。
 * 全部失败才返回 false，由界面提示用户手动进入。
 */
object SystemSettingsNavigator {

    private const val TAG = "SystemSettingsNavigator"
    private const val SETTINGS_PKG = "com.android.settings"

    fun open(context: Context, page: SystemSettingsPage): Boolean {
        val candidates = when (page) {
            SystemSettingsPage.DeveloperOptions -> developerOptionsIntents()
            SystemSettingsPage.UsbDebugging -> usbDebuggingIntents()
            SystemSettingsPage.WirelessDebugging -> wirelessDebuggingIntents() + developerOptionsIntents()
        }
        for (intent in candidates) {
            if (launch(context, intent)) return true
        }
        // 最后的兜底：设置首页，用户自己点进去
        return launch(context, Intent(Settings.ACTION_SETTINGS))
    }

    private fun developerOptionsIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent().setComponent(
            ComponentName(SETTINGS_PKG, "$SETTINGS_PKG.Settings\$DevelopmentSettingsActivity")
        ),
        // MIUI / HyperOS 的老入口
        Intent("miui.intent.action.DEVELOPMENT_SETTINGS"),
    )

    private fun usbDebuggingIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent().setComponent(
            ComponentName(SETTINGS_PKG, "$SETTINGS_PKG.Settings\$DevelopmentSettingsActivity")
        ),
    )

    /**
     * AOSP（Android 11+）有独立的无线调试页；HyperOS 没有这个 Activity，
     * 用 SubSettings 装载 Fragment 会打开一个空白页，所以这里不再做那种兜底，
     * 直接由上层的"开发者选项"接管。
     */
    private fun wirelessDebuggingIntents(): List<Intent> = listOf(
        Intent().setComponent(
            ComponentName(SETTINGS_PKG, "$SETTINGS_PKG.Settings\$WirelessDebuggingActivity")
        ),
    )

    private fun launch(context: Context, intent: Intent): Boolean {
        if (!canResolve(context, intent)) {
            Log.d(TAG, "skip unresolved: $intent")
            return false
        }
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrElse { throwable ->
            Log.w(TAG, "startActivity failed: $intent", throwable)
            false
        }
    }

    private fun canResolve(context: Context, intent: Intent): Boolean = runCatching {
        intent.resolveActivity(context.packageManager) != null ||
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }.getOrDefault(false)
}

package com.lxithral.adbtools.ui

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lxithral.adbtools.logic.SettingsAccess
import com.lxithral.adbtools.logic.WirelessDebugging
import com.lxithral.adbtools.logic.executeShellCommand
import com.lxithral.adbtools.logic.reinitRootShell
import com.lxithral.adbtools.memory.FairMemoryReceiver
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AdbViewModel(application: Application) : AndroidViewModel(application), FairMemoryReceiver.MemoryCleaner {

    companion object {
        private const val TAG = "AdbViewModel"

        // 无线调试开关后等待 adbd 重启并分配端口的轮询节奏
        private const val PORT_SETTLE_ATTEMPTS = 6
        private const val PORT_SETTLE_INTERVAL_MS = 500L

        private const val KEY_ADB_ENABLED = "adb_enabled"
        private const val KEY_DEV_SETTINGS = "development_settings_enabled"
        private const val KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }

    var wirelessAdbEnabled by mutableStateOf(value = false)
        private set

    var usbAdbEnabled by mutableStateOf(value = false)
        private set

    var developerOptionsEnabled by mutableStateOf(value = false)
        private set

    var usbInstallEnabled by mutableStateOf(value = false)
        private set

    var usbSecurityEnabled by mutableStateOf(value = false)
        private set

    var rootGranted by mutableStateOf(value = false)
        private set

    var ipAddress by mutableStateOf(value = "")
        private set

    var port by mutableStateOf(value = "")
        private set

    private val sharedPrefs = application.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
    private var pollingJob: Job? = null

    init {
        FairMemoryReceiver.getInstance().registerCleaner(this)
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshStateSync()
                delay(10.seconds)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun refreshState() {
        viewModelScope.launch(Dispatchers.IO) {
            Shell.getShell()
            refreshStateSync()
        }
    }

    private suspend fun refreshStateSync() {
        val context = getApplication<Application>()

        // setting 项：ContentResolver 直读，不依赖 Root（读 global 无需权限）
        usbAdbEnabled = SettingsAccess.isEnabled(context, KEY_ADB_ENABLED)
        developerOptionsEnabled = SettingsAccess.isEnabled(context, KEY_DEV_SETTINGS)
        val wifiSettingEnabled = SettingsAccess.isEnabled(context, KEY_ADB_WIFI_ENABLED)

        // 属性项：只能走 shell（Root 设备可用，无 Root 时为空，界面照常显示"已关闭"）
        val propResult = executeShellCommand(
            "echo \"$(getprop persist.security.adbinstall)\" && " +
            "echo \"$(getprop persist.security.adbinput)\"",
            context
        ).lines()
        usbInstallEnabled = propResult.getOrElse(0) { "" }.trim() == "1"
        usbSecurityEnabled = propResult.getOrElse(1) { "" }.trim() == "1"

        rootGranted = Shell.isAppGrantedRoot() == true
        ipAddress = WirelessDebugging.getAddress(context)

        // 端口：关着无线调试时只读一次属性（最便宜）；开着才走完整链路
        // 属性 → dumpsys adb（需要 Root）→ mDNS 广播（不需要权限）。
        val resolvedPort = if (wifiSettingEnabled) {
            WirelessDebugging.getPort(context)
        } else {
            WirelessDebugging.getPropPort()
        }
        port = resolvedPort

        // 端口存活也说明无线调试在跑，避免个别 ROM 不写 adb_wifi_enabled 时状态误判
        wirelessAdbEnabled = wifiSettingEnabled || resolvedPort.isNotEmpty()
    }

    fun toggleDeveloperOptions(enabled: Boolean) {
        toggleSetting(KEY_DEV_SETTINGS, enabled, "开发者选项")
    }

    fun toggleUsbDebugging(enabled: Boolean) {
        toggleSetting(KEY_ADB_ENABLED, enabled, "USB 调试")
    }

    /**
     * 重新申请 root（不杀进程）。用户刚在权限管理器里授完权时点一下即可生效——
     * libsu 的 shell 是进程级单例，不重开的话会一直用启动时那个非 root 实例。
     */
    fun retryRootDetection() {
        viewModelScope.launch(Dispatchers.IO) {
            val granted = reinitRootShell()
            refreshStateSync()
            toast(
                getApplication(),
                if (granted) "已获取 Root 权限"
                else "仍未获取 Root：请在 Root 管理器里允许本应用，或改用 adb 授予 WRITE_SECURE_SETTINGS"
            )
        }
    }

    /** 拿到 root 就用；没有就尝试重新握手一次 */
    private fun ensureRoot(): Boolean =
        Shell.isAppGrantedRoot() == true || reinitRootShell()

    private fun writeFailureMessage(label: String): String =
        if (Shell.isAppGrantedRoot() == true) {
            "$label 切换失败：系统拒绝了这次写入，请检查安全中心 / 隐私保护是否限制修改系统设置"
        } else {
            "$label 切换失败：没有写入权限。请在 Root 管理器授权后点 Root 卡片重新检测，" +
                "或在电脑上执行 adb shell pm grant com.lxithral.adbtools android.permission.WRITE_SECURE_SETTINGS"
        }

    private fun toggleSetting(key: String, enabled: Boolean, label: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val ok = SettingsAccess.putInt(context, key, if (enabled) 1 else 0)
            refreshStateSync()
            toast(context, if (ok) "$label 已${if (enabled) "开启" else "关闭"}" else writeFailureMessage(label))
        }
    }

    fun toggleUsbInstall(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            if (!ensureRoot()) {
                toast(context, "USB 安装需要 Root：请在 Root 管理器里授权本应用")
                return@launch
            }
            val isMiui = executeShellCommand("getprop ro.miui.ui.version.name", context).isNotBlank()

            if (isMiui) {
                val scriptContent = buildMiuioUsbInstallScript(enabled)
                try {
                    val tempFile = File(context.cacheDir, "usb_install.sh")
                    tempFile.writeText(scriptContent)
                    tempFile.setExecutable(true, false)
                    executeShellCommand("sh ${tempFile.absolutePath}", context)
                    tempFile.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Script execution failed", e)
                }
            } else {
                val value = if (enabled) "1" else "0"
                executeShellCommand("setprop persist.security.adbinstall $value", context)
            }

            delay(200.milliseconds)
            refreshStateSync()
            toast(context, if (enabled) "已开启 USB 安装" else "已关闭 USB 安装")
        }
    }

    private fun buildMiuioUsbInstallScript(enable: Boolean): String {
        val xmlFile = "/data/data/com.miui.securitycenter/shared_prefs/remote_provider_preferences.xml"
        val installValue = if (enable) "true" else "false"
        val interceptValue = if (enable) "false" else "true"
        val label = if (enable) "开启" else "关闭"
        val adbInstall = if (enable) "1" else "0"
        return """
            #!/system/bin/sh
            XML_FILE=$xmlFile
            echo "$label Installation via USB"
            LINE_NO=`grep -n "security_adb_install_enable" ${'$'}XML_FILE | awk -F: '{print ${'$'}1}'`
            if [ "${'$'}LINE_NO" != "" ] && [ ${'$'}LINE_NO -gt 0 ]; then
                sed -i '/security_adb_install_enable/s/$installValue/'${if (enable) "true" else "false"}'/' ${'$'}XML_FILE
            else
                sed -i '3a \    <boolean name="security_adb_install_enable" value="$installValue" />' ${'$'}XML_FILE
            fi
            LINE_NO=`grep -n "permcenter_install_intercept_enabled" ${'$'}XML_FILE | awk -F: '{print ${'$'}1}'`
            if [ "${'$'}LINE_NO" != "" ] && [ ${'$'}LINE_NO -gt 0 ]; then
                sed -i '/permcenter_install_intercept_enabled/s/${if (enable) "true" else "false"}/$interceptValue/' ${'$'}XML_FILE
            else
                sed -i '3a \    <boolean name="permcenter_install_intercept_enabled" value="$interceptValue" />' ${'$'}XML_FILE
            fi
            kill -9 $(pidof com.miui.securitycenter.remote)
            setprop persist.security.adbinstall $adbInstall
            echo "USB 安装已$label"
        """.trimIndent()
    }

    fun toggleUsbSecurity(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            if (!ensureRoot()) {
                toast(context, "USB 调试（安全设置）需要 Root：请在 Root 管理器里授权本应用")
                return@launch
            }
            val value = if (enabled) "1" else "0"
            executeShellCommand("setprop persist.security.adbinput $value", context)
            delay(200.milliseconds)
            refreshStateSync()
            toast(context, if (enabled) "已开启 USB 调试（安全设置）" else "已关闭 USB 调试（安全设置）")
        }
    }

    fun toggleWirelessAdb(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val written = WirelessDebugging.setEnabled(context, enabled)

            // adbd 起停 + 端口分配有几拍延迟：最多等 3s 让状态与端口收敛
            var settled = false
            repeat(PORT_SETTLE_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(PORT_SETTLE_INTERVAL_MS)
                refreshStateSync()
                if (wirelessAdbEnabled == enabled) {
                    settled = true
                    if (!enabled || port.isNotEmpty()) return@launch
                }
            }

            val message = when {
                !written -> writeFailureMessage("无线调试")
                !settled -> if (enabled) "系统未响应，请到系统无线调试页开启" else "系统未响应，请到系统无线调试页关闭"
                // 已开启但没拿到端口：不是错误，界面会显示"端口获取中…"
                else -> null
            }
            message?.let { toast(context, it) }
        }
    }

    private suspend fun toast(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // MemoryCleaner 实现

    override fun onTrim(data: FairMemoryReceiver.MemoryData) {
        Log.d(TAG, "onTrim: type=${data.notifyType}, pss=${data.pss}KB/${data.pssLimit}KB, heap=${data.heapSize}KB/${data.heapCapacity}KB")

        viewModelScope.launch(Dispatchers.IO) {
            when (data.notifyType) {
                FairMemoryReceiver.NOTIFY_TYPE_PHYSICAL_MEMORY -> {
                    val context = getApplication<Application>()
                    context.cacheDir?.deleteRecursively()
                }
                FairMemoryReceiver.NOTIFY_TYPE_JAVA_HEAP -> {
                    // 依赖系统自动 GC，不手动调用
                }
            }
            delay(100)
            refreshStateSync()
        }
    }

    override fun onKill(data: FairMemoryReceiver.MemoryData): Boolean {
        Log.d(TAG, "onKill: type=${data.notifyType}, saving state...")
        viewModelScope.launch(Dispatchers.IO) {
            saveApplicationState(getApplication())
        }
        return true
    }

    private fun saveApplicationState(context: Context) {
        try {
            sharedPrefs.edit().apply {
                putBoolean("developer_options", developerOptionsEnabled)
                putBoolean("usb_debugging", usbAdbEnabled)
                putBoolean("wireless_debugging", wirelessAdbEnabled)
                putBoolean("usb_install", usbInstallEnabled)
                putBoolean("usb_security", usbSecurityEnabled)
                apply()
            }
            Log.d(TAG, "Application state saved")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save state", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        FairMemoryReceiver.getInstance().unregisterCleaner(this)
    }
}

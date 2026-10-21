package com.lxithral.adbtools.logic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.net.Inet4Address

object WirelessDebugging {

    private const val TAG = "WirelessDebuggingFeature"
    private const val KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /**
     * 端口合法性收敛：`0` / `-1` / `null` / 空串都表示"还没分配端口"，
     * 必须当成空值处理，否则界面会出现 `192.168.x.x:0` 这种假端口。
     */
    fun normalizePort(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.equals("null", ignoreCase = true)) return ""
        val port = value.toIntOrNull() ?: return ""
        return if (port in 1..65535) value else ""
    }

    fun getEnabled(context: Context): Boolean {
        if (SettingsAccess.isEnabled(context, KEY_ADB_WIFI_ENABLED)) return true
        // 兜底：端口存活即说明 adbd 的 TLS 服务在跑
        return getPort(context).isNotEmpty()
    }

    /**
     * 开关无线调试。
     *
     * 系统设置页写的就是 `Settings.Global.ADB_WIFI_ENABLED`，system_server 里的
     * AdbDebuggingManager 对它注册了 ContentObserver，写进去才会真正拉起 / 关闭
     * adbd 的 TLS 服务（只改 `service.adb.tls.port` 属性是没用的）。
     *
     * 之前的实现写的是 `settings put --user current global ...`，并且没有任何回读校验，
     * 命令失败时界面只会表现为"开关弹回去"，看不出原因。现在统一走 [SettingsAccess]，
     * 写入后回读校验，失败由调用方提示。
     *
     * @return 写入并回读成功才返回 true
     */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val ok = SettingsAccess.putInt(context, KEY_ADB_WIFI_ENABLED, if (enabled) 1 else 0)
        Log.d(TAG, "setEnabled($enabled) -> $ok")
        return ok
    }

    /**
     * 只读系统属性里的端口，不发 dumpsys。
     * 轮询里先用它：绝大多数情况（无线调试关着）一次 getprop 就够了，
     * 不用每 10s 拉一次 `dumpsys adb`。
     */
    fun getPropPort(): String {
        val props = Shell.cmd("getprop service.adb.tls.port; getprop service.adb.tcp.port")
            .exec().out
        props.forEach { line ->
            val port = normalizePort(line)
            if (port.isNotEmpty()) return port
        }
        return ""
    }

    /**
     * 无线调试端口。
     * - Android 11~16：`service.adb.tls.port` 由 adbd 写入，全局可读（系统设置页也读它）；
     * - Android 17 / HyperOS 3 实测（25102RKBEC）：该属性为空，端口只出现在
     *   `dumpsys adb` 的 `adb_wifi{ tls_port=… }` 里——需要 root 或 DUMP 权限
     *   （`adb shell pm grant com.lxithral.adbtools android.permission.DUMP`）。
     */
    fun getPort(context: Context): String {
        val propPort = getPropPort()
        if (propPort.isNotEmpty()) return propPort

        val dump = Shell.cmd("dumpsys adb").exec().out.joinToString("\n")
        for (pattern in PORT_PATTERNS) {
            val port = normalizePort(pattern.find(dump)?.groupValues?.getOrNull(1))
            if (port.isNotEmpty()) return port
        }
        return ""
    }

    fun getAddress(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return "未连接"
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)

        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!isWifi) return "未连接 Wi-Fi"

        return linkProperties?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress ?: "未知 IP"
    }

    private val PORT_PATTERNS = listOf(
        Regex("""tls_port\s*[=:]\s*(\d+)"""),
        Regex("""service\.adb\.tls\.port\s*[=:]\s*(\d+)"""),
    )
}

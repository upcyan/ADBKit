package com.lxithral.adbtools.logic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.net.Inet4Address

object WirelessDebugging {

    private const val TAG = "WirelessDebuggingFeature"
    private const val KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** mDNS 反查较慢：命中后缓存，失败也压一压重试频率，避免每 10s 轮询都扫一遍 */
    private const val PORT_CACHE_TTL_MS = 60_000L
    private var cachedPort: String = ""
    private var cachedAt: Long = 0L
    private var lastMdnsAttemptAt: Long = 0L

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

    suspend fun getEnabled(context: Context): Boolean {
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
     * @return 写入并回读成功才返回 true
     */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val ok = SettingsAccess.putInt(context, KEY_ADB_WIFI_ENABLED, if (enabled) 1 else 0)
        // 每次开关都会重新分配端口，缓存必须立刻失效
        invalidateCache()
        Log.d(TAG, "setEnabled($enabled) -> $ok")
        return ok
    }

    fun invalidateCache() {
        cachedPort = ""
        cachedAt = 0L
        lastMdnsAttemptAt = 0L
    }

    /**
     * 只读系统属性里的端口，不发 dumpsys。
     * 轮询里先用它：绝大多数情况（无线调试关着）一次 getprop 就够了。
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
     * 无线调试端口，按代价从低到高依次尝试：
     * 1. 系统属性 `service.adb.tls.port`（Android 11~16 会写）
     * 2. `dumpsys adb` 里的 `adb_wifi{ tls_port= }`（Android 17 / HyperOS 只写这里，需要 Root）
     * 3. mDNS 反查 `_adb-tls-connect._tcp` 广播（不需要任何权限，重装也不会失效）
     */
    suspend fun getPort(context: Context): String {
        val prop = getPropPort()
        if (prop.isNotEmpty()) {
            cache(prop)
            return prop
        }

        val fromDump = readPortFromDumpsys()
        if (fromDump.isNotEmpty()) {
            cache(fromDump)
            return fromDump
        }

        val now = SystemClock.elapsedRealtime()
        if (cachedPort.isNotEmpty() && now - cachedAt < PORT_CACHE_TTL_MS) return cachedPort
        if (now - lastMdnsAttemptAt < PORT_CACHE_TTL_MS) return ""

        lastMdnsAttemptAt = now
        val discovered = PortDiscovery.discover(context)
        if (discovered.isNotEmpty()) cache(discovered)
        return discovered
    }

    private fun readPortFromDumpsys(): String {
        val dump = Shell.cmd("dumpsys adb").exec().out.joinToString("\n")
        if (dump.isBlank()) return ""
        for (pattern in PORT_PATTERNS) {
            val port = normalizePort(pattern.find(dump)?.groupValues?.getOrNull(1))
            if (port.isNotEmpty()) return port
        }
        return ""
    }

    private fun cache(port: String) {
        cachedPort = port
        cachedAt = SystemClock.elapsedRealtime()
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

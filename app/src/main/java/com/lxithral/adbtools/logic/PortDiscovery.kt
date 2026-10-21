package com.lxithral.adbtools.logic

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

/**
 * 用 mDNS 反查无线调试端口。
 *
 * Android 11~16 会把端口写进 `service.adb.tls.port`，直接读属性就行；
 * 但 Android 17 / HyperOS 3 实测该属性被 SELinux 封了（app 读 `adbd_prop` 被 deny），
 * 端口只在 `dumpsys adb` 里可见，而那个需要 Root。
 *
 * 系统在开启无线调试时会通过 mDNS 广播 `_adb-tls-connect._tcp`，广播里带着 `IP:端口`——
 * 这条路径不需要 Root，也不会因为重装丢授权。
 */
object PortDiscovery {

    private const val TAG = "PortDiscovery"
    private const val SERVICE_TYPE = "_adb-tls-connect._tcp"

    /** 本机所有 IPv4 地址，用来从一堆 mDNS 结果里认出"自己" */
    private fun localAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.filterNot { it.isLoopbackAddress }
            ?.mapNotNull { it.hostAddress }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

    /**
     * 发现本机无线调试端口，超时返回空串。
     * 优先返回"host 命中本机 IP"的那条；实在没有就退回第一条能解析到的。
     */
    suspend fun discover(context: Context, timeoutMs: Long = 6000L): String {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return ""
        val mine = localAddresses()
        var fallback = ""

        val matched = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var finished = false
                lateinit var listener: NsdManager.DiscoveryListener

                fun finish(result: String) {
                    if (finished) return
                    finished = true
                    runCatching { nsd.stopServiceDiscovery(listener) }
                    if (cont.isActive) cont.resume(result)
                }

                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onServiceFound(info: NsdServiceInfo) {
                        // 注意：Android 回传的 serviceType 可能带尾点（`_adb-tls-connect._tcp.`），
                        // 严格相等会一条都匹配不上，这里按前缀判断
                        if (!info.serviceType.trimEnd('.').startsWith(SERVICE_TYPE)) return
                        Log.d(TAG, "found ${info.serviceName} type=${info.serviceType}")
                        runCatching {
                            nsd.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onServiceResolved(resolved: NsdServiceInfo) {
                                    val host = resolved.host?.hostAddress
                                    val p = normalize(resolved.port)
                                    Log.d(TAG, "resolved host=$host port=$p mine=$mine")
                                    if (p.isEmpty()) return
                                    if (host != null && (mine.isEmpty() || host in mine)) {
                                        finish(p)
                                    } else if (fallback.isEmpty()) {
                                        // 认不出是不是本机时先记下来，等超时兜底
                                        fallback = p
                                    }
                                }

                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                    Log.w(TAG, "resolve failed: $errorCode")
                                }
                            })
                        }.onFailure { Log.w(TAG, "resolve throw: ${it.message}") }
                    }

                    override fun onServiceLost(info: NsdServiceInfo) = Unit

                    override fun onDiscoveryStopped(serviceType: String) {
                        Log.d(TAG, "discovery stopped")
                    }

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.w(TAG, "start discovery failed: $errorCode")
                        finish("")
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                }

                cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
                runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
                    .onFailure {
                        Log.w(TAG, "discover throw: ${it.message}")
                        finish("")
                    }
            }
        }

        val result = matched ?: fallback
        Log.d(TAG, "discover result=$result")
        return result
    }

    private fun normalize(port: Int): String =
        if (port in 1..65535) port.toString() else ""
}

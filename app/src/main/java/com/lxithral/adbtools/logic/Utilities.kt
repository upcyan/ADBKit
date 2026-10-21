package com.lxithral.adbtools.logic

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.util.concurrent.TimeUnit

/**
 * 重启应用进程。
 *
 * libsu 的 root shell 是进程级单例：应用启动时没拿到 root，之后在权限管理器里再授权，
 * 进程内的 Shell 也不会重新申请，只能重启进程让 libsu 重新走一遍 su 握手。
 */
fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Process.killProcess(Process.myPid())
}

/**
 * 直接用 `su -c` 跑一条命令，绕开 libsu。
 *
 * 有些 root 方案（KernelSU / APatch）只在**已授权进程的挂载命名空间**里放 `su`，
 * libsu 在应用启动早期探测失败就会缓存一个非 root shell，之后即使授了权也一直认为没 root。
 * 这里直接起进程调 `su`，由 root 管理器自己决定放不放行，绕开这层缓存。
 *
 * @return 退出码为 0 时的输出；否则 null
 */
fun execSu(command: String, timeoutSeconds: Long = 8): String? {
    val tag = "Utilities.execSu"
    return try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        val code = if (finished) process.exitValue() else -1
        Log.e(tag, "su -c [$command] exit=$code out=[${output.take(200)}]")
        if (finished && code == 0) output.trim() else null
    } catch (t: Throwable) {
        Log.e(tag, "su -c [$command] threw ${t.javaClass.simpleName}: ${t.message}")
        RootState.recordProbeFailure(t.message)
        null
    }
}

/**
 * root 状态。libsu 报"没有 root"不一定真没有——见 [execSu] 的注释，
 * 所以这里再用 `su -c id` 复核一次，结果缓存一小会儿，避免轮询频繁起进程。
 */
object RootState {

    private const val CACHE_MS = 30_000L
    @Volatile private var cached: Boolean? = null
    @Volatile private var checkedAt = 0L

    /** 最近一次 `su` 探测的失败原因，用来给用户更准确的提示 */
    @Volatile var probeFailure: String? = null
        private set

    fun recordProbeFailure(message: String?) {
        probeFailure = message
    }

    fun isGranted(force: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        val value = cached
        if (!force && value != null && now - checkedAt < CACHE_MS) return value

        val granted = Shell.isAppGrantedRoot() == true || probeSu()
        cached = granted
        checkedAt = now
        return granted
    }

    fun invalidate() {
        cached = null
        checkedAt = 0L
    }

    private fun probeSu(): Boolean {
        val out = execSu("id", 5) ?: return false
        return out.contains("uid=0")
    }
}

/**
 * 重新申请一次 root，不用杀进程。
 *
 * App 启动时还没在权限管理器里授权，libsu 会缓存一个非 root shell；关掉缓存实例
 * 再 [Shell.getShell] 会重新走一遍 su 请求。[RootState] 的缓存同时失效，
 * 让它重新用 `su -c id` 复核。
 *
 * @return 重新申请后是否拿到了 root
 */
fun reinitRootShell(): Boolean {
    RootState.invalidate()
    runCatching { Shell.getCachedShell()?.close() }
    runCatching { Shell.getShell() }
    return RootState.isGranted(force = true)
}

fun getPrivilegeLevel(
    requiredPrivilegeLevel: PrivilegeLevel = PrivilegeLevel.Root,
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
): PrivilegeLevel {
    if (RootState.isGranted()) {
        return PrivilegeLevel.Root
    }
    return PrivilegeLevel.User
}

fun executeShellCommand(
    command: String,
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
    requiredPrivilegeLevel: PrivilegeLevel = PrivilegeLevel.Root,
): String {
    val tag = "Utilities.executeShellCommand"
    val privilegeLevel = getPrivilegeLevel(requiredPrivilegeLevel, context)

    if (privilegeLevel == PrivilegeLevel.Root) {
        val viaLibsu = runCatching {
            Shell.cmd(command).exec().out.joinToString("\n").trim()
        }.getOrNull()
        if (!viaLibsu.isNullOrBlank()) {
            Log.d(tag, "Root(libsu): $command -> $viaLibsu")
            return viaLibsu
        }
        // libsu 的 shell 不可用时退回直接 su
        execSu(command)?.let {
            Log.d(tag, "Root(su): $command -> $it")
            return it
        }
    }

    Log.e(tag, "Failed: no root permission")
    return ""
}

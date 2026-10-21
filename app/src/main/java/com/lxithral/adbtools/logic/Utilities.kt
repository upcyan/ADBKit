package com.lxithral.adbtools.logic

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.topjohnwu.superuser.Shell

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
 * 重新申请一次 root shell，不用杀进程。
 *
 * 场景：App 启动时还没在权限管理器里授权，libsu 建了一个非 root 的缓存 shell；
 * 之后用户再授权，进程内的 Shell 也不会重新握手。关掉缓存实例再 [Shell.getShell]
 * 会重新走一遍 su 请求，授权后就能立刻拿到 root。
 *
 * @return 重新握手后是否拿到了 root
 */
fun reinitRootShell(): Boolean {
    runCatching { Shell.getCachedShell()?.close() }
    runCatching { Shell.getShell() }
    return Shell.isAppGrantedRoot() == true
}

fun getPrivilegeLevel(
    requiredPrivilegeLevel: PrivilegeLevel = PrivilegeLevel.Root,
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
): PrivilegeLevel {
    if (Shell.isAppGrantedRoot() == true) {
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
        val result = Shell.cmd(command).exec().out.joinToString("\n").trim()
        Log.d(tag, "Root: $command -> $result")
        return result
    }

    Log.e(tag, "Failed: no root permission")
    return ""
}

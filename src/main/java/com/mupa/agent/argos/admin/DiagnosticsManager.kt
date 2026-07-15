package com.mupa.agent.argos.admin

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.os.StatFs

class DiagnosticsManager(private val context: Context) {
    fun snapshotText(): String {
        val sb = StringBuilder()
        sb.appendLine("Modelo: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Uptime: ${formatDuration(SystemClock.elapsedRealtime())}")
        sb.appendLine("IP: ${resolveIp()}")
        sb.appendLine("RAM: ${formatRam()}")
        sb.appendLine("Armazenamento: ${formatStorage()}")
        return sb.toString().trim()
    }

    private fun formatRam(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val avail = info.availMem / (1024 * 1024)
        val total = if (Build.VERSION.SDK_INT >= 16) info.totalMem / (1024 * 1024) else 0L
        return if (total > 0) "${avail}MB / ${total}MB" else "${avail}MB"
    }

    private fun formatStorage(): String {
        val dir = context.filesDir
        val stat = StatFs(dir.absolutePath)
        val avail = stat.availableBytes / (1024 * 1024)
        val total = stat.totalBytes / (1024 * 1024)
        return "${avail}MB / ${total}MB"
    }

    private fun resolveIp(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "-"
        val caps = cm.getNetworkCapabilities(network) ?: return "-"
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return if (hasInternet) "online" else "offline"
    }

    private fun formatDuration(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return "${h}h ${m}m ${s}s"
    }
}


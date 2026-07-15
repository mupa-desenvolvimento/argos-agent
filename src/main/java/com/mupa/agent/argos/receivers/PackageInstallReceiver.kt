package com.mupa.agent.argos.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class PackageInstallResult(
    val sessionId: Int,
    val status: Int,
    val message: String,
)

data class PackageUninstallResult(
    val uninstallId: Int,
    val status: Int,
    val message: String,
    val packageName: String?,
)

class PackageInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            "com.mupa.agent.argos.action.PACKAGE_INSTALL_COMMIT" -> handleInstall(intent)
            "com.mupa.agent.argos.action.PACKAGE_UNINSTALL_COMMIT" -> handleUninstall(context, intent)
            else -> return
        }
    }

    private fun handleInstall(intent: Intent) {
        val sessionId = intent.getIntExtra("session_id", -1)
        if (sessionId <= 0) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.trim().orEmpty()
        waiters.remove(sessionId)?.complete(
            PackageInstallResult(
                sessionId = sessionId,
                status = status,
                message = message,
            ),
        )
    }

    private fun handleUninstall(context: Context, intent: Intent) {
        val uninstallId = intent.getIntExtra("uninstall_id", -1)
        if (uninstallId <= 0) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.trim().orEmpty()
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)?.trim().orEmpty().ifBlank { null }

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                return
            }
        }

        uninstallWaiters.remove(uninstallId)?.complete(
            PackageUninstallResult(
                uninstallId = uninstallId,
                status = status,
                message = message,
                packageName = pkg,
            ),
        )
    }

    companion object {
        private val waiters: ConcurrentHashMap<Int, CompletableFuture<PackageInstallResult>> = ConcurrentHashMap()
        private val uninstallWaiters: ConcurrentHashMap<Int, CompletableFuture<PackageUninstallResult>> = ConcurrentHashMap()

        fun registerWaiter(sessionId: Int): CompletableFuture<PackageInstallResult> {
            val f = CompletableFuture<PackageInstallResult>()
            waiters[sessionId] = f
            return f
        }

        fun unregisterWaiter(sessionId: Int) {
            waiters.remove(sessionId)
        }

        fun registerUninstallWaiter(uninstallId: Int): CompletableFuture<PackageUninstallResult> {
            val f = CompletableFuture<PackageUninstallResult>()
            uninstallWaiters[uninstallId] = f
            return f
        }

        fun unregisterUninstallWaiter(uninstallId: Int) {
            uninstallWaiters.remove(uninstallId)
        }
    }
}


package com.mupa.agent.argos.permissions

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.mupa.agent.argos.mdm.ArgosDeviceAdminReceiver
import org.json.JSONObject

enum class PermissionId {
    DeviceOwner,
    DeviceAdmin,
    BatteryOptimizations,
    Overlay,
    UsageStats,
    Accessibility,
    Notifications,
    UnknownAppSources,
}

enum class PermissionStatus {
    Completed,
    Pending,
    Checking,
    Error,
    NotSupported,
}

data class PermissionState(
    val id: PermissionId,
    val label: String,
    val status: PermissionStatus,
    val critical: Boolean,
    val actionLabel: String?,
    val intent: Intent?,
    val info: String?,
)

class PermissionManager(private val context: Context) {
    private val appContext = context.applicationContext

    fun checkAllPermissions(): List<PermissionState> {
        val out = ArrayList<PermissionState>()
        out += checkDeviceOwner()
        out += checkDeviceAdmin()
        out += checkBatteryOptimizations()
        out += checkOverlay()
        out += checkUsageStats()
        out += checkAccessibility()
        out += checkNotifications()
        out += checkUnknownSources()
        return out
    }

    fun criticalReady(): Boolean {
        return checkAllPermissions()
            .filter { it.critical }
            .all { it.status == PermissionStatus.Completed }
    }

    fun toJsonSnapshot(): JSONObject {
        val states = checkAllPermissions().associateBy { it.id }
        fun ok(id: PermissionId): Boolean = states[id]?.status == PermissionStatus.Completed
        return JSONObject()
            .put("deviceOwner", ok(PermissionId.DeviceOwner))
            .put("admin", ok(PermissionId.DeviceAdmin))
            .put("battery", ok(PermissionId.BatteryOptimizations))
            .put("overlay", ok(PermissionId.Overlay))
            .put("usage", ok(PermissionId.UsageStats))
            .put("accessibility", ok(PermissionId.Accessibility))
            .put("notifications", ok(PermissionId.Notifications))
            .put("unknownApps", ok(PermissionId.UnknownAppSources))
    }

    fun copyDeviceOwnerAdbCommand(): String {
        val cmd = "adb shell dpm set-device-owner com.mupa.agent.argos/.mdm.ArgosDeviceAdminReceiver"
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("argos_device_owner_adb", cmd))
        return cmd
    }

    fun nextAction(states: List<PermissionState>): PermissionState? {
        val ordered = listOf(
            PermissionId.DeviceOwner,
            PermissionId.DeviceAdmin,
            PermissionId.BatteryOptimizations,
            PermissionId.Overlay,
            PermissionId.UsageStats,
            PermissionId.Accessibility,
            PermissionId.Notifications,
            PermissionId.UnknownAppSources,
        )
        val byId = states.associateBy { it.id }
        return ordered.firstNotNullOfOrNull { id ->
            val s = byId[id] ?: return@firstNotNullOfOrNull null
            if (s.status == PermissionStatus.Pending || s.status == PermissionStatus.Error) s else null
        }
    }

    private fun checkDeviceOwner(): PermissionState {
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val ok = runCatching { dpm.isDeviceOwnerApp(appContext.packageName) }.getOrDefault(false)
        return PermissionState(
            id = PermissionId.DeviceOwner,
            label = "Device Owner",
            status = if (ok) PermissionStatus.Completed else PermissionStatus.Pending,
            critical = true,
            actionLabel = if (ok) null else "Copiar ADB",
            intent = null,
            info = if (ok) null else "Permissão crítica",
        )
    }

    private fun checkDeviceAdmin(): PermissionState {
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(appContext, ArgosDeviceAdminReceiver::class.java)
        val ok = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)
        val intent = if (ok) null else {
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Necessário para políticas e gerenciamento avançado.")
        }
        return PermissionState(
            id = PermissionId.DeviceAdmin,
            label = "Administrador do dispositivo",
            status = if (ok) PermissionStatus.Completed else PermissionStatus.Pending,
            critical = true,
            actionLabel = if (ok) null else "Ativar",
            intent = intent,
            info = if (ok) null else "Permissão crítica",
        )
    }

    private fun checkBatteryOptimizations(): PermissionState {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ok = runCatching { pm.isIgnoringBatteryOptimizations(appContext.packageName) }.getOrDefault(false)
        val intent = if (ok) null else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${appContext.packageName}"))
        }
        val resolvable = intent == null || intent.resolveActivity(appContext.packageManager) != null
        val status = when {
            ok -> PermissionStatus.Completed
            !resolvable -> PermissionStatus.NotSupported
            else -> PermissionStatus.Pending
        }
        return PermissionState(
            id = PermissionId.BatteryOptimizations,
            label = "Ignorar otimização de bateria",
            status = status,
            critical = status != PermissionStatus.NotSupported,
            actionLabel = if (status == PermissionStatus.Pending) "Abrir" else null,
            intent = if (status == PermissionStatus.Pending) intent else null,
            info = when (status) {
                PermissionStatus.NotSupported -> "Não suportado pela ROM"
                PermissionStatus.Pending, PermissionStatus.Error -> "Se não abrir, use ADB: adb shell dumpsys deviceidle whitelist +${appContext.packageName}"
                else -> null
            },
        )
    }

    private fun checkOverlay(): PermissionState {
        val ok = runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false)
        val intent = if (ok) null else {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:${appContext.packageName}"))
        }
        val resolvable = intent == null || intent.resolveActivity(appContext.packageManager) != null
        val status = when {
            ok -> PermissionStatus.Completed
            !resolvable -> PermissionStatus.NotSupported
            else -> PermissionStatus.Pending
        }
        return PermissionState(
            id = PermissionId.Overlay,
            label = "Exibir sobre outros apps",
            status = status,
            critical = status != PermissionStatus.NotSupported,
            actionLabel = if (status == PermissionStatus.Pending) "Abrir" else null,
            intent = if (status == PermissionStatus.Pending) intent else null,
            info = when (status) {
                PermissionStatus.NotSupported -> "Não suportado pela ROM"
                PermissionStatus.Pending, PermissionStatus.Error -> "Se não abrir, use ADB: adb shell appops set ${appContext.packageName} SYSTEM_ALERT_WINDOW allow"
                else -> null
            },
        )
    }

    private fun checkUsageStats(): PermissionState {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), appContext.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), appContext.packageName)
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        val ok = mode == AppOpsManager.MODE_ALLOWED
        val intent = if (ok) null else Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        val resolvable = intent == null || intent.resolveActivity(appContext.packageManager) != null
        val status = when {
            ok -> PermissionStatus.Completed
            !resolvable -> PermissionStatus.NotSupported
            else -> PermissionStatus.Pending
        }
        return PermissionState(
            id = PermissionId.UsageStats,
            label = "Usage Stats",
            status = status,
            critical = status != PermissionStatus.NotSupported,
            actionLabel = if (status == PermissionStatus.Pending) "Abrir" else null,
            intent = if (status == PermissionStatus.Pending) intent else null,
            info = when (status) {
                PermissionStatus.NotSupported -> "Não suportado pela ROM"
                PermissionStatus.Pending, PermissionStatus.Error -> "Se não abrir, use ADB: adb shell appops set ${appContext.packageName} GET_USAGE_STATS allow"
                else -> null
            },
        )
    }

    private fun checkAccessibility(): PermissionState {
        val enabled = runCatching {
            Settings.Secure.getInt(appContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }.getOrDefault(false)
        val services = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.lowercase()
                .orEmpty()
        }.getOrDefault("")
        val selfService = "${appContext.packageName}/com.mupa.agent.argos.accessibility.ArgosAccessibilityService".lowercase()
        val ok = enabled && services.contains(selfService)
        val intent = if (ok) null else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        return PermissionState(
            id = PermissionId.Accessibility,
            label = "Acessibilidade",
            status = if (ok) PermissionStatus.Completed else PermissionStatus.Pending,
            critical = false,
            actionLabel = if (ok) null else "Abrir",
            intent = intent,
            info = if (ok) null else "Se necessário (ADB): adb shell settings put secure enabled_accessibility_services ${appContext.packageName}/com.mupa.agent.argos.accessibility.ArgosAccessibilityService && adb shell settings put secure accessibility_enabled 1",
        )
    }

    private fun checkNotifications(): PermissionState {
        val ok = runCatching { NotificationManagerCompat.from(appContext).areNotificationsEnabled() }.getOrDefault(false)
        val intent = if (ok) null else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        }
        val resolvable = intent == null || intent.resolveActivity(appContext.packageManager) != null
        val finalStatus = when {
            ok -> PermissionStatus.Completed
            !resolvable -> PermissionStatus.NotSupported
            else -> PermissionStatus.Pending
        }
        return PermissionState(
            id = PermissionId.Notifications,
            label = "Notificações",
            status = finalStatus,
            critical = false,
            actionLabel = if (finalStatus == PermissionStatus.Pending) "Abrir" else null,
            intent = if (finalStatus == PermissionStatus.Pending) intent else null,
            info = if (finalStatus == PermissionStatus.NotSupported) "Não suportado pela ROM" else null,
        )
    }

    private fun checkUnknownSources(): PermissionState {
        if (Build.VERSION.SDK_INT < 26) {
            return PermissionState(
                id = PermissionId.UnknownAppSources,
                label = "Instalar apps desconhecidos",
                status = PermissionStatus.NotSupported,
                critical = false,
                actionLabel = null,
                intent = null,
                info = "Não suportado pela ROM",
            )
        }

        val ok = runCatching { appContext.packageManager.canRequestPackageInstalls() }
            .getOrElse { return PermissionState(PermissionId.UnknownAppSources, "Instalar apps desconhecidos", PermissionStatus.Error, false, "Abrir", buildUnknownSourcesIntent(), it.javaClass.simpleName) }

        val intent = if (ok) null else buildUnknownSourcesIntent()
        val resolvable = intent == null || intent.resolveActivity(appContext.packageManager) != null
        val status = when {
            ok -> PermissionStatus.Completed
            !resolvable -> PermissionStatus.NotSupported
            else -> PermissionStatus.Pending
        }
        return PermissionState(
            id = PermissionId.UnknownAppSources,
            label = "Instalar apps desconhecidos",
            status = status,
            critical = false,
            actionLabel = if (status == PermissionStatus.Pending) "Abrir" else null,
            intent = if (status == PermissionStatus.Pending) intent else null,
            info = when (status) {
                PermissionStatus.NotSupported -> "Não suportado pela ROM"
                PermissionStatus.Pending, PermissionStatus.Error -> "Se não abrir, use ADB: adb shell appops set ${appContext.packageName} REQUEST_INSTALL_PACKAGES allow"
                else -> null
            },
        )
    }

    private fun buildUnknownSourcesIntent(): Intent {
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${appContext.packageName}"))
    }
}

package com.mupa.agent.argos.commands

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import com.mupa.agent.argos.accessibility.ArgosAccessibilityService
import com.mupa.agent.argos.apps.ApplicationManager
import com.mupa.agent.argos.launcher.WhitelistManager
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.provisioning.ApkInstallManager
import com.mupa.agent.argos.provisioning.ApkInstallRequest
import com.mupa.agent.argos.services.HeartbeatService
import com.mupa.agent.argos.services.PollingService
import com.mupa.agent.argos.ui.ArgosLauncherActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class CommandResult(
    val status: String,
    val message: String,
)

class CommandDispatcher(private val context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val apps = ApplicationManager(appContext)
    private val dpm = DeviceOwnerPolicyManager(appContext)
    private val whitelist = WhitelistManager(appContext)
    private val anyDeskPkg = "com.anydesk.anydeskandroid"
    private val defaultManagedAppPkg = "com.mupa.player.enterprise"
    // Only the agent's own package belongs here (see the identical fix + explanation in
    // ApkInstallManager.kt's selfPackageAliases). "com.mupa.player.enterprise" used to be
    // listed too, which made normalizeExpectedPackage() rewrite every MPlayer install_apk
    // request to the agent's own package — every MPlayer deploy failed with
    // "package_mismatch" while Argos self-updates (which don't go through this rewrite)
    // kept working, making it look like only the agent could ever update.
    private val selfPackageAliases = setOf(
        appContext.packageName.lowercase(),
    )

    fun execute(commandId: String?, command: String, params: JSONObject): CommandResult {
        val name = canonicalCommand(command)

        // AdbCommandReceiver's broadcast (com.mupa.agent.argos.action.ADB_COMMAND) is exported
        // with no permission gate, because android:permission would also block legitimate
        // `adb shell am broadcast` calls from ARGOS Device Tools (Android has no reliable way
        // to distinguish "adb shell" from a malicious co-resident app inside onReceive() — there
        // is no getCallingUid() for manifest broadcast receivers). Since we can't authenticate
        // the sender, we shrink the blast radius instead: irreversible/destructive commands are
        // refused on this specific unauthenticated channel. They remain available via the
        // Supabase-backed command queue, which is gated by backend auth.
        if (commandId == "adb_broadcast" && name in DESTRUCTIVE_COMMANDS_BLOCKED_VIA_ADB) {
            return CommandResult("failed", "blocked_unauthenticated_adb_channel")
        }

        return when (name) {
            "abrir_app", "open_app" -> {
                val pkg = params.optString("package", params.optString("pacote", "")).trim()
                if (pkg.isBlank()) return CommandResult("failed", "package_empty")
                // If kiosk/LockTask is active, temporarily grant this package access to LockTask
                // so it can be launched even if it's not in the current whitelist. We add it to
                // the allowed set but do NOT persist it to the whitelist — next setAllowedPackages
                // or kiosk re-apply will restore the original set.
                val kioskActive = settings.getMdmLockedCached() || settings.getKioskModeCached()
                if (kioskActive && dpm.isDeviceOwner(appContext.packageName)) {
                    val allowed = settings.getAllowedPackagesCached() + pkg + appContext.packageName
                    runCatching { dpm.updateLockTaskPackages(appContext.packageName, allowed) }
                }
                val ok = apps.open(pkg)
                if (ok) CommandResult("success", "opened") else CommandResult("failed", "not_found")
            }

            "fechar_app", "close_app" -> {
                val pkg = resolveTargetPackage(params)
                if (pkg.isBlank()) return CommandResult("failed", "package_empty")
                val err = apps.close(pkg)
                if (err == null) CommandResult("success", "close_requested") else CommandResult("failed", err)
            }

            "restart_app" -> {
                val pkg = resolveTargetPackage(params)
                if (pkg.isBlank()) return CommandResult("failed", "package_empty")
                val closeErr = apps.close(pkg)
                runCatching { Thread.sleep(600L) }
                val opened = apps.open(pkg)
                when {
                    opened -> CommandResult("success", "restarted:$pkg")
                    closeErr != null -> CommandResult("failed", closeErr)
                    else -> CommandResult("failed", "restart_open_failed")
                }
            }

            "reboot_device", "reiniciar", "reboot", "restart_device" -> {
                val err = dpm.rebootDeviceError(appContext.packageName)
                if (err == null) CommandResult("success", "reboot_requested") else CommandResult("failed", err)
            }

            "factory_reset_keep_config",
            "factory_reset",
            "wipe_device",
            "reset_factory",
            "reset_fabrica",
            "resetar_fabrica",
            -> {
                val err = dpm.factoryResetError(appContext.packageName)
                if (err == null) CommandResult("success", "factory_reset_requested") else CommandResult("failed", err)
            }

            "locktask_on", "kiosk_on" -> {
                // Persist kiosk/MDM state before trying to lock
                runBlocking {
                    settings.setKioskMode(true)
                    settings.setMdmLocked(true)
                }
                // Apply locked MDM policy (device owner required for full lock, but we still persist)
                runCatching {
                    val allowed = settings.getAllowedPackagesCached() + appContext.packageName
                    dpm.applyLocked(appContext.packageName, allowed)
                }
                // Start LockTask via bridge activity
                val intent = Intent("com.mupa.agent.argos.action.LOCKTASK_START")
                    .setClassName(appContext.packageName, "com.mupa.agent.argos.ui.LockTaskBridgeActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                return runCatching {
                    appContext.startActivity(intent)
                    CommandResult("success", "kiosk_enabled")
                }.getOrElse { t ->
                    CommandResult("failed", t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName })
                }
            }

            "locktask_off", "kiosk_off", "kiosk_disable" -> {
                runBlocking {
                    settings.setMdmLocked(false)
                    settings.setKioskMode(false)
                }
                runCatching { dpm.applyUnlocked(appContext.packageName) }
                runCatching { dpm.setAndroidLauncherAsHome(appContext.packageName) }
                requestLockTaskStop()
                goAndroidHome()
                CommandResult("success", "kiosk_disabled")
            }

            "set_whitelist", "set_allowed_apps", "set_kiosk_apps" -> {
                val arr = params.optJSONArray("whitelist")
                    ?: params.optJSONArray("allowedPackages")
                    ?: params.optJSONArray("packages")
                if (arr == null || arr.length() == 0) return CommandResult("failed", "packages_empty")
                val pkgs = (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { p -> p.isNotBlank() } }.toSet()
                if (pkgs.isEmpty()) return CommandResult("failed", "packages_empty")
                kotlinx.coroutines.runBlocking { whitelist.setAllowedPackages(pkgs + appContext.packageName) }
                if (settings.getMdmLockedCached() || settings.getKioskModeCached()) {
                    dpm.applyLocked(appContext.packageName, settings.getAllowedPackagesCached())
                }
                CommandResult("success", "allowed_updated")
            }

            "list_installed_apps", "listar_apps_instalados", "get_installed_apps" -> {
                val payload = params.optJSONObject("payload") ?: params
                val includeSystem = payload.optBoolean("include_system", payload.optBoolean("includeSystem", false))
                val items = apps.listLaunchableApps(includeSystem)
                val msg = JSONObject().put("apps", items).toString()
                CommandResult("success", msg)
            }

            "list_kiosk_apps", "get_whitelist", "listar_apps_kiosk", "get_allowed_apps" -> {
                val pkgs = settings.getAllowedPackagesCached().toList().sorted()
                val msg = JSONObject()
                    .put("packages", org.json.JSONArray(pkgs))
                    .put("autostart_package", settings.getAutostartPackageCached())
                    .toString()
                CommandResult("success", msg)
            }

            "set_autostart", "define_autostart", "definir_autostart", "enable_autostart" -> {
                val payload = params.optJSONObject("payload") ?: params
                val pkg = payload.optString("package", payload.optString("packageName", "")).trim()
                if (pkg.isBlank()) return CommandResult("failed", "package_empty")
                if (!apps.isInstalled(pkg)) return CommandResult("failed", "package_not_installed")
                runBlocking {
                    settings.setAutostartPackage(pkg)
                    whitelist.update(pkg) { it.copy(autostart = true) }
                }
                CommandResult("success", "autostart_set:$pkg")
            }

            "disable_autostart", "remove_autostart", "desativar_autostart" -> {
                val payload = params.optJSONObject("payload") ?: params
                val targetPkg = payload.optString("package", payload.optString("packageName", "")).trim()
                val current = settings.getAutostartPackageCached()
                if (targetPkg.isNotBlank() && !current.equals(targetPkg, ignoreCase = true)) {
                    // Asked to disable autostart for a specific package, but it isn't the one
                    // currently configured — nothing to do, but not an error either.
                    return CommandResult("success", "autostart_not_set_for_package")
                }
                runBlocking { settings.setAutostartPackage("") }
                CommandResult("success", "autostart_disabled")
            }

            "open_settings", "abrir_configuracoes", "abrir_config" -> {
                val i = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { appContext.startActivity(i) }
                CommandResult("success", "settings_opened")
            }

            "sincronizar", "sync", "sync_now", "report_now" -> {
                runBlocking {
                    runCatching { PollingService(appContext).tick() }
                    runCatching { HeartbeatService(appContext).send(force = true) }
                }
                CommandResult("success", "synced")
            }

            "volume" -> {
                val value = params.optInt("value", -1).coerceIn(0, 100)
                if (value < 0) return CommandResult("failed", "value_invalid")
                val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val vol = (max * (value / 100f)).toInt().coerceIn(0, max)
                runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0) }
                CommandResult("success", "volume_set")
            }

            "brightness" -> {
                val value = params.optInt("value", -1)
                if (value !in 0..100) return CommandResult("failed", "value_invalid")
                if (!dpm.isDeviceOwner(appContext.packageName)) return CommandResult("failed", "not_device_owner")
                val scaled = ((value / 100f) * 255f).toInt().coerceIn(0, 255)
                val err = dpm.setSystemBrightness(appContext.packageName, scaled)
                if (err == null) CommandResult("success", "brightness_set") else CommandResult("failed", err)
            }

            "set_wifi", "definir_wifi", "configurar_wifi", "wifi_set" -> {
                val payload = params.optJSONObject("payload") ?: params
                val ssid = payload.optString("ssid", payload.optString("wifi_ssid", "")).trim()
                val password = payload.optString("password", payload.optString("wifi_password", ""))
                if (ssid.isBlank()) return CommandResult("failed", "ssid_empty")
                val ok = com.mupa.agent.argos.wifi.WifiConfig(appContext).setAndConnect(ssid, password)
                if (ok) CommandResult("success", "wifi_set:$ssid")
                else CommandResult("failed", "wifi_connect_failed:$ssid")
            }

            "keep_awake" -> {
                val enabled = params.optBoolean("enabled", params.optBoolean("value", true))
                runBlocking { settings.setKeepAwakeEnabled(enabled) }
                CommandResult("success", if (enabled) "keep_awake_enabled" else "keep_awake_disabled")
            }

            "awake_schedule", "set_awake_schedule" -> {
                val enabled = params.optBoolean("enabled", true)
                val startMin = params.optInt("start_min", -1).takeIf { it >= 0 }
                    ?: settings.parseTimeToMinutesOfDay(params.optString("start", params.optString("start_time", "")).trim())
                    ?: 0
                val endMin = params.optInt("end_min", -1).takeIf { it >= 0 }
                    ?: settings.parseTimeToMinutesOfDay(params.optString("end", params.optString("end_time", "")).trim())
                    ?: 1440
                runBlocking { settings.setAwakeSchedule(enabled, startMinOfDay = startMin, endMinOfDay = endMin) }
                CommandResult("success", "awake_schedule_set")
            }

            "rest_mode_on", "rest_on", "sleep_on", "rest", "sleep" -> {
                val now = System.currentTimeMillis()
                val minutes = params.optInt("minutes", params.optInt("min", 30)).coerceIn(1, 24 * 60)
                val untilMsParam = params.optLong("until_ms", params.optLong("until", 0L))
                val untilMs = if (untilMsParam > now) untilMsParam else now + minutes * 60_000L
                val lockNow = params.optBoolean("lock_now", params.optBoolean("screen_off_now", true))
                runBlocking { settings.setRestModeUntilMs(untilMs) }
                if (lockNow) runCatching { dpm.lockNowError(appContext.packageName) }
                CommandResult("success", "rest_mode_enabled")
            }

            "rest_mode_off", "rest_off", "wake_mode", "wake_up" -> {
                runBlocking { settings.setRestModeUntilMs(0L) }
                CommandResult("success", "rest_mode_disabled")
            }

            "rest_screen_timeout" -> {
                val ms = params.optLong("ms", params.optLong("value_ms", 60_000L))
                runBlocking { settings.setRestScreenTimeoutMs(ms) }
                CommandResult("success", "rest_screen_timeout_set")
            }

            // "force_install" is the same install_apk path with force always on — added so
            // the Web's "Forçar Instalação" button doesn't depend on remembering to set
            // force:true in the payload. Without force, ApkInstallManager skips the install
            // whenever the installed version already matches the target, which is exactly
            // the case for devices stuck "looking done" without ever actually getting the
            // new APK (corrupted install, manual sideload mismatch, etc).
            "install_apk", "install_app", "update_app", "atualizar_agent", "update_agent", "atualizar_mplayer", "force_install" -> {
                val payload = params.optJSONObject("payload") ?: params
                val url = payload.optString("url", "").trim()
                val pkg = normalizeExpectedPackage(
                    payload.optString("package", payload.optString("packageName", "")).trim(),
                    name,
                )
                val version = payload.optString("version", "").trim().ifBlank { null }
                val force = name.trim().lowercase() == "force_install" || payload.optBoolean("force", false)
                val silent = true
                val autoOpen = payload.optBoolean("auto_open", payload.optBoolean("autoOpen", false))
                val checksum = payload.optString("checksum", "").trim().ifBlank { null }

                if (!dpm.isDeviceOwner(appContext.packageName)) {
                    val mode = settings.getAppDistributionModeCached()
                    val delegated = mode == "managed_play" || mode == "auto"
                    if (delegated) return CommandResult("delegated", "delegated_to_managed_play")
                    // No device owner privilege — fall back to a non-silent install:
                    // download the APK to a temp file then launch the system installer UI.
                    // This requires user confirmation on screen but at least delivers the
                    // update instead of silently failing. The caller can check the response
                    // message "install_ui_launched" to distinguish this path from a real
                    // silent success.
                    if (url.isBlank()) return CommandResult("failed", "url_empty:not_device_owner")
                    val res = ApkInstallManager(appContext).installViaUi(url, checksum)
                    return CommandResult(res.status, res.message)
                }

                val res = ApkInstallManager(appContext).install(
                    ApkInstallRequest(
                        url = url,
                        expectedPackage = pkg.ifBlank { null },
                        expectedVersion = version,
                        force = force,
                        silent = silent,
                        autoOpen = autoOpen,
                        sha256 = checksum,
                    ),
                )
                val msg = JSONObject()
                    .put("package", res.packageName.orEmpty())
                    .put("version", res.versionName.orEmpty())
                    .put("opened", res.opened)
                    .put("message", res.message)
                    .toString()
                CommandResult(res.status, msg)
            }

            // Uninstalls the target package first, then installs fresh — as a single command,
            // not the Web sending uninstall_app then install_apk separately: those would be
            // two writes to the same single-slot m_argos/devices/{id}/commands node, and the
            // second can land before the device has even started processing the first,
            // silently dropping the uninstall (the exact bug already fixed once this session
            // for the whitelist auto-install loop). Useful when force=true update-in-place
            // isn't enough — e.g. a signature mismatch from a different signing key, or a
            // corrupted install PackageInstaller won't overwrite.
            "reinstall_apk" -> {
                val payload = params.optJSONObject("payload") ?: params
                val url = payload.optString("url", "").trim()
                val pkg = normalizeExpectedPackage(
                    payload.optString("package", payload.optString("packageName", "")).trim(),
                    name,
                )
                val version = payload.optString("version", "").trim().ifBlank { null }
                val checksum = payload.optString("checksum", "").trim().ifBlank { null }

                if (!dpm.isDeviceOwner(appContext.packageName)) {
                    return CommandResult("failed", "silent_required:not_device_owner")
                }
                if (pkg.isBlank()) return CommandResult("failed", "package_empty")

                val installer = ApkInstallManager(appContext)
                val wasInstalled = runCatching { appContext.packageManager.getPackageInfo(pkg, 0) }.isSuccess
                if (wasInstalled) {
                    val uninstallRes = installer.uninstall(pkg, silent = true)
                    if (uninstallRes.status != "success") {
                        return CommandResult("failed", "uninstall_failed:${uninstallRes.message}")
                    }
                }

                val res = installer.install(
                    ApkInstallRequest(
                        url = url,
                        expectedPackage = pkg.ifBlank { null },
                        expectedVersion = version,
                        force = true,
                        silent = true,
                        autoOpen = payload.optBoolean("auto_open", payload.optBoolean("autoOpen", false)),
                        sha256 = checksum,
                    ),
                )
                val msg = JSONObject()
                    .put("package", res.packageName.orEmpty())
                    .put("version", res.versionName.orEmpty())
                    .put("opened", res.opened)
                    .put("message", res.message)
                    .put("was_installed", wasInstalled)
                    .toString()
                CommandResult(res.status, msg)
            }

            "anydesk_accept", "anydesk_start_now" -> {
                val targetPkg = anyDeskPkg
                val installed = runCatching { appContext.packageManager.getPackageInfo(targetPkg, 0) }.isSuccess
                if (!installed) {
                    return CommandResult("failed", "anydesk_not_installed")
                }

                if (!ArgosAccessibilityService.isServiceEnabled(appContext)) {
                    return CommandResult("failed", "accessibility_required")
                }

                requestLockTaskStop()
                val durationMs = params.optLong("duration_ms", params.optLong("timeout_ms", 60_000L)).coerceAtLeast(5_000L)
                val x = params.optInt("tap_x", params.optInt("x", -1)).takeIf { it >= 0 }
                val y = params.optInt("tap_y", params.optInt("y", -1)).takeIf { it >= 0 }
                val xPct = params.optDouble("tap_x_pct", params.optDouble("x_pct", -1.0)).takeIf { it >= 0.0 }
                val yPct = params.optDouble("tap_y_pct", params.optDouble("y_pct", -1.0)).takeIf { it >= 0.0 }
                ArgosAccessibilityService.armAnyDeskAutoClick(appContext, durationMs, tapX = x, tapY = y, tapXPct = xPct, tapYPct = yPct)
                val opened = apps.open(targetPkg)
                CommandResult("success", if (opened) "armed_and_opened" else "armed")
            }

            "remote_support_accept", "remote_support_request" -> {
                // Documented in AGENT_COMMANDS.md as "idem [anydesk_accept], mas para o app
                // ARGOS Remote" but never actually implemented — this fills that gap.
                //
                // Unlike AnyDesk, ARGOS Remote already runs its own accessibility-based
                // auto-consent for the MediaProjection dialog (RemoteAccessibilityService +
                // GatewayConfig.isAutoConsent, on by default for managed fleets) — so this
                // agent doesn't need to arm a click on its behalf like armAnyDeskAutoClick()
                // does. The only thing actually missing was Lock Task blocking the consent
                // Activity from appearing over the kiosk at all: confirmed by hand on a real
                // unit that once Lock Task is released, ARGOS Remote's own auto-consent
                // completes the rest with zero further input. requestLockTaskStop() is
                // self-healing — ArgosLauncherActivity.onResume() re-arms startLockTask()
                // the moment it naturally regains foreground, so no explicit re-lock call is
                // needed here (same as anydesk_accept/uninstall_app above).
                val targetPkg = "com.mupa.argos.remote"
                val installed = runCatching { appContext.packageManager.getPackageInfo(targetPkg, 0) }.isSuccess
                if (!installed) {
                    return CommandResult("failed", "argos_remote_not_installed")
                }

                requestLockTaskStop()
                val opened = apps.open(targetPkg)
                CommandResult("success", if (opened) "unlocked_and_opened" else "unlocked")
            }

            "maintenance_on", "maintenance_off" -> {
                CommandResult("failed", "not_implemented")
            }

            "uninstall_app", "remove_app", "delete_app" -> {
                val payload = params.optJSONObject("payload") ?: params
                val pkg = payload.optString("package", payload.optString("packageName", payload.optString("pacote", ""))).trim()
                if (pkg.isBlank()) return CommandResult("failed", "package_empty")

                val force = payload.optBoolean("force", false)
                if (isSelfPackage(pkg) && !force) return CommandResult("failed", "package_protected")

                val removeFromWhitelist = payload.optBoolean("remove_from_whitelist", payload.optBoolean("removeFromWhitelist", true))
                if (removeFromWhitelist) runBlocking { whitelist.removeFromWhitelist(pkg) }
                if (settings.getMdmLockedCached() || settings.getKioskModeCached()) {
                    runCatching { dpm.applyLocked(appContext.packageName, settings.getAllowedPackagesCached()) }
                }

                val silent = payload.optBoolean("silent", true)
                if (silent && !dpm.isDeviceOwner(appContext.packageName)) {
                    val mode = settings.getAppDistributionModeCached()
                    val delegated = mode == "managed_play" || mode == "auto"
                    return if (delegated) {
                        CommandResult("delegated", "delegated_to_managed_play")
                    } else {
                        CommandResult("failed", "silent_required:not_device_owner")
                    }
                }

                requestLockTaskStop()
                val timeoutMs = payload.optLong("timeout_ms", payload.optLong("timeoutMs", 120_000L)).coerceAtLeast(5_000L)
                val res = ApkInstallManager(appContext).uninstall(pkg, silent = silent, timeoutMs = timeoutMs)
                val msg = JSONObject()
                    .put("package", pkg)
                    .put("message", res.message)
                    .toString()
                CommandResult(res.status, msg)
            }

            "screenshot", "take_screenshot", "capturar_tela" -> {
                val deviceId = com.mupa.agent.argos.managers.DeviceIdentityManager
                    .getPersistentDeviceId(appContext).trim()
                if (deviceId.isBlank()) return CommandResult("failed", "no_device_id")
                val ok = com.mupa.agent.argos.managers.ScreenshotManager(appContext).captureAndUpload(deviceId)
                if (ok) CommandResult("success", "screenshot_uploaded")
                else CommandResult("failed", "screenshot_unavailable")
            }

            "dw_switch_profile", "datawedge_switch_profile", "trocar_perfil_datawedge" -> {
                val payload = params.optJSONObject("payload") ?: params
                val profile = payload.optString("profile_name", payload.optString("profile", "")).trim()
                if (profile.isBlank()) return CommandResult("failed", "profile_name_empty")
                val ok = com.mupa.agent.argos.datawedge.DataWedgeManager(appContext).switchProfile(profile)
                if (ok) CommandResult("success", "profile_switched:$profile") else CommandResult("failed", "switch_failed")
            }

            "dw_enable", "datawedge_enable", "ativar_datawedge" -> {
                com.mupa.agent.argos.datawedge.DataWedgeManager(appContext).setEnabled(true)
                CommandResult("success", "datawedge_enabled")
            }

            "dw_disable", "datawedge_disable", "desativar_datawedge" -> {
                com.mupa.agent.argos.datawedge.DataWedgeManager(appContext).setEnabled(false)
                CommandResult("success", "datawedge_disabled")
            }

            "dw_scan_start", "datawedge_scan_start", "disparar_leitura" -> {
                com.mupa.agent.argos.datawedge.DataWedgeManager(appContext).softScanTrigger(start = true)
                CommandResult("success", "scan_started")
            }

            "dw_scan_stop", "datawedge_scan_stop", "parar_leitura" -> {
                com.mupa.agent.argos.datawedge.DataWedgeManager(appContext).softScanTrigger(start = false)
                CommandResult("success", "scan_stopped")
            }

            "dw_restore_defaults", "datawedge_restore_defaults", "restaurar_datawedge" -> {
                com.mupa.agent.argos.datawedge.DataWedgeManager(appContext).restoreDefaults()
                CommandResult("success", "datawedge_restored")
            }

            "dw_set_config", "datawedge_set_config", "configurar_datawedge" -> {
                val cfg = params.optJSONObject("config") ?: params.optJSONObject("payload") ?: params
                val ok = com.mupa.agent.argos.datawedge.DataWedgeManager(appContext).setConfig(cfg)
                if (ok) CommandResult("success", "datawedge_config_applied:${cfg.optString("profile")}")
                else CommandResult("failed", "profile_empty_or_invalid")
            }

            "dw_create_profile", "datawedge_create_profile", "criar_perfil_datawedge" -> {
                val payload = params.optJSONObject("payload") ?: params
                val profile = payload.optString("profile", payload.optString("profile_name", payload.optString("name", ""))).trim()
                if (profile.isBlank()) return CommandResult("failed", "profile_name_empty")
                val ok = com.mupa.agent.argos.datawedge.DataWedgeManager(appContext).createProfile(profile)
                if (ok) CommandResult("success", "profile_created:$profile") else CommandResult("failed", "create_failed")
            }

            "dw_get_info", "datawedge_get_info", "datawedge_status", "status_datawedge" -> {
                val info = com.mupa.agent.argos.datawedge.DataWedgeManager(appContext).getVersionInfo()
                if (info != null) CommandResult("success", info.toString())
                else CommandResult("failed", "datawedge_not_responding")
            }

            "remote_authorize", "authorize_remote", "meferi_authorize" -> {
                if (!com.mupa.agent.argos.managers.MeferiRemoteAuthorizer.isMeferiMC45()) {
                    return CommandResult("failed", "not_meferi_mc45")
                }
                com.mupa.agent.argos.managers.MeferiRemoteAuthorizer.authorize(appContext)
                CommandResult("success", "remote_authorized")
            }

            "clear_cache", "limpar_cache" -> {
                // Android's DPM has no "clear cache only" API for another app — clearing
                // user data is the closest equivalent and is what DeviceOwnerPolicyManager
                // already exposes elsewhere (e.g. troubleshooting MPlayer media cache).
                val pkg = resolveTargetPackage(params)
                if (pkg.isBlank()) return CommandResult("failed", "package_empty")
                val err = dpm.clearAppUserDataError(appContext.packageName, pkg)
                if (err == null) CommandResult("success", "cache_cleared:$pkg") else CommandResult("failed", err)
            }

            "atualizar_playlist", "update_playlist", "refresh_playlist" -> {
                // MPlayer re-fetches its manifest/playlist from scratch on cold start,
                // so a full restart is the most reliable "refresh now" we can trigger remotely.
                val pkg = resolveTargetPackage(params)
                if (pkg.isBlank()) return CommandResult("failed", "package_empty")
                val closeErr = apps.close(pkg)
                runCatching { Thread.sleep(600L) }
                val opened = apps.open(pkg)
                when {
                    opened -> CommandResult("success", "playlist_refresh_requested:$pkg")
                    closeErr != null -> CommandResult("failed", closeErr)
                    else -> CommandResult("failed", "restart_open_failed")
                }
            }

            "enviar_arquivo", "send_file", "push_file" -> {
                val url = params.optString("url", "").trim()
                if (url.isBlank()) return CommandResult("failed", "url_empty")
                val dir = File(appContext.getExternalFilesDir(null), "received").apply { mkdirs() }
                val saved = downloadFile(url, dir)
                if (saved != null) CommandResult("success", "file_saved:${saved.name}") else CommandResult("failed", "download_failed")
            }

            "executar_script", "run_script", "execute_script" -> {
                val script = params.optString("script", params.optString("command", "")).trim()
                if (script.isBlank()) return CommandResult("failed", "script_empty")
                val timeoutMs = params.optLong("timeout_ms", 15_000L).coerceIn(1_000L, 60_000L)
                runScript(script, timeoutMs)
            }

            else -> CommandResult("failed", "unknown_command")
        }
    }

    /** Downloads [url] into [dir], picking a safe filename from the URL (no path traversal). */
    private fun downloadFile(url: String, dir: File): File? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
        val rawName = url.substringAfterLast('/').substringBefore('?').trim()
        val safeName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file_${System.currentTimeMillis()}" }
        val dest = File(dir, safeName)
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "GET"
            }
            conn.inputStream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            conn.disconnect()
            dest.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }

    /** Runs [script] via `sh -c`, capturing combined output (truncated) within [timeoutMs]. */
    private fun runScript(script: String, timeoutMs: Long): CommandResult {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", script)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult("failed", "script_timeout")
            }
            val output = process.inputStream.bufferedReader().readText().trim().take(4_000)
            if (process.exitValue() == 0) {
                CommandResult("success", output.ifBlank { "script_ok" })
            } else {
                CommandResult("failed", "exit_${process.exitValue()}: ${output.ifBlank { "no_output" }}")
            }
        }.getOrElse { t ->
            CommandResult("failed", "script_error:${t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }}")
        }
    }

    private fun canonicalCommand(raw: String): String {
        return when (raw.trim().lowercase()) {
            "lock_device" -> "kiosk_on"
            "unlock_device" -> "kiosk_off"
            "reiniciar_dispositivo" -> "reboot_device"
            "fecha_app" -> "close_app"
            "reset_app" -> "restart_app"
            else -> raw.trim().lowercase()
        }
    }

    private fun resolveTargetPackage(params: JSONObject): String {
        val explicit = params.optString("package", params.optString("pacote", params.optString("package_name", ""))).trim()
        if (explicit.isNotBlank()) return explicit

        val autostart = settings.getAutostartPackageCached().trim()
        if (autostart.isNotBlank()) return autostart

        return defaultManagedAppPkg.takeIf { apps.isInstalled(it) }.orEmpty()
    }

    private fun isSelfPackage(packageName: String): Boolean {
        return selfPackageAliases.contains(packageName.trim().lowercase())
    }

    private fun normalizeExpectedPackage(packageName: String, commandName: String): String {
        val pkg = packageName.trim()
        if (pkg.isNotBlank()) {
            return if (isSelfPackage(pkg)) appContext.packageName else pkg
        }
        return when (commandName.trim().lowercase()) {
            "atualizar_agent", "update_agent" -> appContext.packageName
            "atualizar_mplayer" -> "com.mupa.mplayer"
            else -> ""
        }
    }

    private fun requestLockTaskStop() {
        // Must target the actual locked task's root activity (ArgosLauncherActivity,
        // launchMode=singleTask) so stopLockTask() runs on the task that's really pinned —
        // a separate bridge activity (the old LockTaskBridgeActivity) lands in a different
        // task, and stopLockTask() only releases lock task mode for the calling task, so it
        // silently did nothing for the task that mattered. FLAG_ACTIVITY_NEW_TASK with
        // singleTask routes this to the existing instance via onNewIntent instead of creating
        // a new task.
        val intent = Intent(ArgosLauncherActivity.ACTION_LOCKTASK_STOP)
            .setClassName(appContext.packageName, "com.mupa.agent.argos.ui.ArgosLauncherActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { appContext.startActivity(intent) }
    }

    private fun goAndroidHome() {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(i) }
    }

    companion object {
        /** Irreversible/destructive commands refused when received via the unauthenticated ADB broadcast. */
        private val DESTRUCTIVE_COMMANDS_BLOCKED_VIA_ADB = setOf(
            "factory_reset_keep_config", "factory_reset", "wipe_device", "reset_factory", "reset_fabrica", "resetar_fabrica",
            "uninstall_app", "remove_app", "delete_app",
            // Arbitrary shell execution and arbitrary file writes are new attack surface —
            // keep them restricted to the authenticated Supabase command queue only.
            "executar_script", "run_script", "execute_script",
            "enviar_arquivo", "send_file", "push_file",
        )
    }
}

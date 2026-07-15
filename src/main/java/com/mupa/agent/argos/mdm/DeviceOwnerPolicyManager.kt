package com.mupa.agent.argos.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mupa.agent.argos.ui.ArgosLauncherActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Locale

class DeviceOwnerPolicyManager(context: Context) {
    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(appContext, ArgosDeviceAdminReceiver::class.java)

    fun isDeviceOwner(packageName: String): Boolean = dpm.isDeviceOwnerApp(packageName)

    fun applyLocked(packageName: String, lockTaskPackages: Set<String>) {
        if (!isDeviceOwner(packageName)) return

        val normalized = lockTaskPackages.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val finalPackages = (setOf(packageName) + normalized).toTypedArray()
        runCatching { dpm.setLockTaskPackages(admin, finalPackages) }
        applyLauncherAsHome(packageName, enabled = true)
        applyDeviceDefaults()
        applyBaselineRestrictions(packageName)
        applyDeviceProfile(packageName)
        setOemNavigationBarVisible(visible = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
            runCatching { dpm.setStatusBarDisabled(admin, true) }
            runCatching { dpm.setKeyguardDisabled(admin, true) }
        }

        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT) }
        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER) }
        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA) }
        tryStopApp(packageName, "com.android.vending")
    }

    fun applyUnlocked(packageName: String) {
        if (!isDeviceOwner(packageName)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dpm.setStatusBarDisabled(admin, false) }
            // applyLocked() sets LOCK_TASK_FEATURE_NONE ("fully locked") — must be restored to
            // something permissive BEFORE trying to exit, otherwise ActivityTaskManager refuses
            // stopLockTask() with "Attempted to stop app pinning while fully locked" and the
            // device stays stuck in kiosk mode forever (this was the actual bug behind "kiosk
            // não desativa" — setLockTaskPackages(emptyArray()) below isn't enough by itself).
            runCatching {
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                        DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                        DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO,
                )
            }
        }

        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT) }
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER) }
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA) }

        applyLauncherAsHome(packageName, enabled = false)
        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
        applyBaselineRestrictions(packageName)
        applyDeviceProfile(packageName)
        setOemNavigationBarVisible(visible = true)
        tryUnsuspendApp(packageName, "com.android.vending")
    }

    /**
     * Hides/shows the OEM navigation bar on devices that expose a toggle for it. The MEFERI
     * MC45 has no physical buttons and renders volume/menu buttons on its custom nav bar —
     * `setLockTaskFeatures(LOCK_TASK_FEATURE_NONE)` doesn't remove that bar, so kiosk users
     * could still open volume/menu UI. The System-table write needs WRITE_SECURE_SETTINGS,
     * which is a development permission granted at provisioning time via
     * `adb shell pm grant com.mupa.agent.argos android.permission.WRITE_SECURE_SETTINGS`
     * (Device Owner cannot self-grant it). No-op on devices without the setting or the grant.
     */
    fun setOemNavigationBarVisible(visible: Boolean) {
        // MEFERI (MC45): the direct System-table write below is rejected for targetSdk ≥ 23
        // apps ("You cannot keep your settings in the secure settings"), so go through the
        // firmware's own device-manager binder instead. No-op on other firmwares.
        if (MeferiSdkBridge.setNavigationBarEnabled(visible)) return

        val resolver = appContext.contentResolver
        for (key in OEM_NAVBAR_SYSTEM_KEYS) {
            runCatching {
                Settings.System.getString(resolver, key) ?: return@runCatching
                Settings.System.putInt(resolver, key, if (visible) 1 else 0)
            }
        }
    }

    /**
     * Self-grants the runtime permissions the agent needs to operate headless. Without this,
     * Build.getSerial() throws SecurityException (READ_PHONE_STATE is a runtime permission even
     * for Device Owner apps) and starting the FGS with type "location" crashes on Android 14 —
     * both bit us on the MEFERI MC45. No-op when not Device Owner.
     */
    fun selfGrantRuntimePermissions(packageName: String) {
        if (!isDeviceOwner(packageName)) return
        grantRuntimePermissions(
            ownerPackage = packageName,
            targetPackage = packageName,
            permissions = listOf(
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.CAMERA,
            ),
        )
    }

    /**
     * Grants MPlayer the runtime permissions it needs so it never pops the system grant dialog
     * on the kiosk screen. In particular CAMERA (audience analytics / facial): PlayerActivity
     * re-requests CAMERA on every content change, so a denied grant re-opens the dialog in a
     * loop over the player. As Device Owner we pre-grant it here; the app then silently proceeds
     * (or silently skips audience if the license doesn't need it). Best-effort; no-op when not
     * Device Owner or the app isn't installed yet.
     */
    fun grantMupaPlayerPermissions(ownerPackage: String) {
        if (!isDeviceOwner(ownerPackage)) return
        grantRuntimePermissions(
            ownerPackage = ownerPackage,
            targetPackage = MUPA_PLAYER_PACKAGE,
            permissions = listOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.READ_PHONE_STATE,
            ),
        )
    }

    private fun grantRuntimePermissions(
        ownerPackage: String,
        targetPackage: String,
        permissions: List<String>,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!isDeviceOwner(ownerPackage)) return
        // Only attempt for installed packages — setPermissionGrantState throws for unknown ones.
        val installed = runCatching {
            appContext.packageManager.getPackageInfo(targetPackage, 0); true
        }.getOrDefault(false)
        if (!installed) return
        for (permission in permissions) {
            runCatching {
                dpm.setPermissionGrantState(
                    admin,
                    targetPackage,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
    }

    fun applyBaselineRestrictions(packageName: String) {
        if (!isDeviceOwner(packageName)) return
        selfGrantRuntimePermissions(packageName)
        grantMupaPlayerPermissions(packageName)
        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET) }
        runCatching { dpm.setMaximumTimeToLock(admin, 0L) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dpm.setKeyguardDisabled(admin, true) }
        }
        runCatching { dpm.setSecureSetting(admin, "lock_screen_lock_after_timeout", (24 * 60 * 60 * 1000).toString()) }

        // Prevent Google Play / GMS from prompting for account sign-in.
        // As Device Owner we can block account management entirely for Google accounts.
        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { dpm.setAccountManagementDisabled(admin, "com.google", true) }
        }
    }

    fun applyDeviceProfile(packageName: String): String? {
        if (!isDeviceOwner(packageName)) return "not_device_owner"
        return runCatching {
            when (detectDeviceProfile()) {
                DeviceProfile.TVBOX_X96 -> {
                    runCatching { dpm.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, (24 * 60 * 60 * 1000).toString()) }
                    runCatching { dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "3") }
                    runCatching { dpm.setSecureSetting(admin, "immersive_mode_confirmations", "confirmed") }
                }
                else -> Unit
            }
            null
        }.getOrElse { t ->
            t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
        }
    }

    fun currentDeviceProfileId(): String {
        return detectDeviceProfile().id
    }

    private enum class DeviceProfile(val id: String) {
        GENERIC("generic"),
        ZEBRA_ET4X("zebra_et4x"),
        UROVO_MC45("urovo_mc45"),
        GERTEC_SK100("gertec_sk100"),
        GERTEC_GBOT("gertec_gbot"),
        TVBOX_X96("tvbox_x96"),
    }

    private fun detectDeviceProfile(): DeviceProfile {
        val m = Build.MANUFACTURER?.trim().orEmpty().lowercase(Locale.ROOT)
        val b = Build.BRAND?.trim().orEmpty().lowercase(Locale.ROOT)
        val model = Build.MODEL?.trim().orEmpty().lowercase(Locale.ROOT)
        val device = Build.DEVICE?.trim().orEmpty().lowercase(Locale.ROOT)
        val fp = Build.FINGERPRINT?.trim().orEmpty().lowercase(Locale.ROOT)

        if (model.contains("x96") || model.contains("x96max") || m.contains("droidlogic") || b.contains("droidlogic") || fp.contains("droidlogic")) {
            return DeviceProfile.TVBOX_X96
        }
        if (m.contains("zebra") || b.contains("zebra") || model.contains("et40") || model.contains("et45")) {
            return DeviceProfile.ZEBRA_ET4X
        }
        if (m.contains("urovo") || model.contains("mc45") || model.contains("i9600") || device.contains("urovo")) {
            return DeviceProfile.UROVO_MC45
        }
        if (m.contains("gertec") || b.contains("gertec") || model.contains("sk100")) {
            return DeviceProfile.GERTEC_SK100
        }
        if (m.contains("gertec") || b.contains("gertec") || model.contains("gbot")) {
            return DeviceProfile.GERTEC_GBOT
        }
        return DeviceProfile.GENERIC
    }

    fun updateLockTaskPackages(packageName: String, lockTaskPackages: Set<String>): String? {
        if (!isDeviceOwner(packageName)) return "not_device_owner"
        val normalized = lockTaskPackages.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val finalPackages = (setOf(packageName) + normalized).toTypedArray()
        return runCatching {
            dpm.setLockTaskPackages(admin, finalPackages)
            null
        }.getOrElse { t ->
            t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
        }
    }

    fun rebootDeviceError(packageName: String): String? {
        if (!isDeviceOwner(packageName)) return "not_device_owner"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return "sdk_too_low"
        return runCatching {
            dpm.reboot(admin)
            null
        }.getOrElse { t ->
            val msg = t.message?.trim().orEmpty()
            if (msg.isBlank()) t::class.java.simpleName else "${t::class.java.simpleName}: $msg"
        }
    }

    fun factoryResetError(packageName: String): String? {
        if (!isDeviceOwner(packageName)) return "not_device_owner"
        return runCatching {
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET) }
            if (!tryWipeDeviceReflectively()) {
                dpm.wipeData(0)
            }
            null
        }.getOrElse { t ->
            val msg = t.message?.trim().orEmpty()
            if (msg.isBlank()) t::class.java.simpleName else "${t::class.java.simpleName}: $msg"
        }
    }

    private fun tryWipeDeviceReflectively(): Boolean {
        val method = dpm.javaClass.methods.firstOrNull {
            it.name == "wipeDevice" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return false

        return runCatching {
            method.invoke(dpm, 0)
            true
        }.getOrElse { t ->
            throw (t.cause ?: t)
        }
    }

    fun setLocationEnabledError(packageName: String, enabled: Boolean): String? {
        if (!isDeviceOwner(packageName)) return "not_device_owner"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                dpm.setLocationEnabled(admin, enabled)
                null
            }.getOrElse { t ->
                val msg = t.message?.trim().orEmpty()
                if (msg.isBlank()) t::class.java.simpleName else "${t::class.java.simpleName}: $msg"
            }
        }
        return runCatching {
            val v = if (enabled) "3" else "0"
            dpm.setSecureSetting(admin, "location_mode", v)
            null
        }.getOrElse { t ->
            val msg = t.message?.trim().orEmpty()
            if (msg.isBlank()) t::class.java.simpleName else "${t::class.java.simpleName}: $msg"
        }
    }

    fun setSystemBrightness(packageName: String, value0to255: Int): String? {
        if (!isDeviceOwner(packageName)) return "not_device_owner"
        val v = value0to255.coerceIn(0, 255)
        return runCatching {
            dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, "0")
            dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, v.toString())
            null
        }.getOrElse { t ->
            t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
        }
    }

    fun setScreenOffTimeoutMsError(packageName: String, timeoutMs: Long): String? {
        if (!isDeviceOwner(packageName)) return "not_device_owner"
        val v = timeoutMs.coerceIn(5_000L, 24 * 60 * 60 * 1000L)
        return runCatching {
            dpm.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, v.toString())
            null
        }.getOrElse { t ->
            t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
        }
    }

    fun setStayOnWhilePluggedInError(packageName: String, enabled: Boolean): String? {
        if (!isDeviceOwner(packageName)) return "not_device_owner"
        val v = if (enabled) "3" else "0"
        return runCatching {
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, v)
            null
        }.getOrElse { t ->
            t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
        }
    }

    fun lockNowError(packageName: String): String? {
        if (!isDeviceOwner(packageName)) return "not_device_owner"
        return runCatching {
            dpm.lockNow()
            null
        }.getOrElse { t ->
            t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
        }
    }

    fun tryStopApp(ownerPackage: String, targetPackage: String): String? {
        if (!isDeviceOwner(ownerPackage)) return "not_device_owner"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return "sdk_too_low"
        val failed = runCatching { dpm.setPackagesSuspended(admin, arrayOf(targetPackage.trim()), true) }
            .getOrElse { return it.message?.trim().orEmpty().ifBlank { it.javaClass.simpleName } }
        return if (failed.isEmpty()) null else "failed:${failed.joinToString(",")}"
    }

    fun tryUnsuspendApp(ownerPackage: String, targetPackage: String): String? {
        if (!isDeviceOwner(ownerPackage)) return "not_device_owner"
        val failed = runCatching { dpm.setPackagesSuspended(admin, arrayOf(targetPackage.trim()), false) }
            .getOrElse { return it.message?.trim().orEmpty().ifBlank { it.javaClass.simpleName } }
        return if (failed.isEmpty()) null else "failed:${failed.joinToString(",")}"
    }

    fun clearAppUserDataError(ownerPackage: String, targetPackage: String): String? {
        if (!isDeviceOwner(ownerPackage)) return "not_device_owner"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "sdk_too_low"
        val latch = CountDownLatch(1)
        val pkg = targetPackage.trim()
        if (pkg.isBlank()) return "package_empty"
        var succeeded = false
        val listener = DevicePolicyManager.OnClearApplicationUserDataListener { _, ok ->
            succeeded = ok
            latch.countDown()
        }
        val completed = runCatching {
            val m3 = dpm.javaClass.methods.firstOrNull { it.name == "clearApplicationUserData" && it.parameterTypes.size == 3 }
            val m4 = dpm.javaClass.methods.firstOrNull { it.name == "clearApplicationUserData" && it.parameterTypes.size == 4 }
            when {
                m4 != null -> {
                    val ex = ContextCompat.getMainExecutor(appContext)
                    m4.invoke(dpm, admin, pkg, ex, listener)
                }
                m3 != null -> {
                    m3.invoke(dpm, admin, pkg, listener)
                }
                else -> return "not_supported"
            }
            latch.await(6, TimeUnit.SECONDS)
        }.getOrDefault(false)
        return if (completed && succeeded) null else "failed"
    }

    fun setLauncherAsHome(packageName: String, enabled: Boolean): String? {
        if (!isDeviceOwner(packageName)) return "not_device_owner"
        return runCatching {
            applyLauncherAsHome(packageName, enabled)
            null
        }.getOrElse { t ->
            t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
        }
    }

    fun setAndroidLauncherAsHome(packageName: String): String? {
        if (!isDeviceOwner(packageName)) return "not_device_owner"
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val pm = appContext.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = runCatching { pm.queryIntentActivities(homeIntent, 0) }.getOrDefault(emptyList())
            .mapNotNull { it.activityInfo }
            .filter { it.packageName.isNotBlank() && it.name.isNotBlank() }
        if (candidates.isEmpty()) return "no_home_candidates"

        val preferredPkgs = listOf(
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
        )

        val chosen = preferredPkgs.firstNotNullOfOrNull { pkg ->
            candidates.firstOrNull { it.packageName == pkg && it.packageName != packageName }
        } ?: candidates.firstOrNull { it.packageName != packageName }
        ?: return "no_non_argos_home"

        return runCatching {
            val toClear = (candidates.map { it.packageName } + packageName).distinct()
            for (pkg in toClear) {
                runCatching { dpm.clearPackagePersistentPreferredActivities(admin, pkg) }
            }
            dpm.addPersistentPreferredActivity(
                admin,
                filter,
                ComponentName(chosen.packageName, chosen.name),
            )
            null
        }.getOrElse { t ->
            t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
        }
    }

    private fun applyLauncherAsHome(packageName: String, enabled: Boolean) {
        if (!isDeviceOwner(packageName)) return
        if (!enabled) {
            runCatching { dpm.clearPackagePersistentPreferredActivities(admin, packageName) }
            return
        }

        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val homeActivity = ComponentName(appContext, ArgosLauncherActivity::class.java)
        runCatching { dpm.addPersistentPreferredActivity(admin, filter, homeActivity) }
    }

    private fun applyDeviceDefaults() {
        runCatching { dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, "0") }
        runCatching { dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, "255") }

        val current = appContext.resources.displayMetrics.densityDpi
        val forced = (current * 1.15f).toInt().coerceIn(120, 640)
        runCatching { dpm.setSecureSetting(admin, "display_density_forced", forced.toString()) }
    }

    companion object {
        private const val MUPA_PLAYER_PACKAGE = "com.mupa.player.enterprise"

        // System-table keys OEMs use to toggle their custom navigation bar. Only keys that
        // already exist on the device are written (see setOemNavigationBarVisible), so this
        // list is safe to grow for other enterprise OEMs.
        private val OEM_NAVBAR_SYSTEM_KEYS = listOf(
            "navbar_enable",   // MEFERI MC45
        )
    }
}

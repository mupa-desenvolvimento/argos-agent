package com.mupa.agent.argos.launcher

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.content.pm.ApplicationInfo
import com.mupa.agent.argos.managers.PlayerAppConstants
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class AllowedApp(
    val packageName: String,
    val autostart: Boolean = false,
    val persistence: Boolean = false,
    val favorite: Boolean = false,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val protected: Boolean = false,
)

data class AppPolicy(
    val packageName: String,
    val favorite: Boolean = false,
    val autostart: Boolean = false,
    val persistence: Boolean = false,
    val pinnedToTop: Boolean = false,
    val hidden: Boolean = false,
)

class AppPolicyManager(private val context: Context) {
    private val settings = SettingsManager(context.applicationContext)

    suspend fun getAll(): Map<String, AppPolicy> {
        return parse(settings.getAppPoliciesJsonCached())
    }

    suspend fun get(packageName: String): AppPolicy {
        return getAll()[packageName] ?: AppPolicy(packageName = packageName)
    }

    suspend fun set(policy: AppPolicy) {
        val all = getAll().toMutableMap()
        all[policy.packageName] = policy
        settings.setAppPoliciesJson(serialize(all))
    }

    suspend fun update(packageName: String, block: (AppPolicy) -> AppPolicy) {
        val current = get(packageName)
        set(block(current))
    }

    private fun parse(raw: String): Map<String, AppPolicy> {
        val root = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val apps = root.optJSONObject("apps") ?: JSONObject()
        val out = LinkedHashMap<String, AppPolicy>()
        val it = apps.keys()
        while (it.hasNext()) {
            val pkg = it.next().trim()
            if (pkg.isBlank()) continue
            val obj = apps.optJSONObject(pkg) ?: JSONObject()
            out[pkg] = AppPolicy(
                packageName = pkg,
                favorite = obj.optBoolean("favorite", false),
                autostart = obj.optBoolean("autostart", false),
                persistence = obj.optBoolean("persistence", false),
                pinnedToTop = obj.optBoolean("pinnedToTop", false),
                hidden = obj.optBoolean("hidden", false),
            )
        }
        return out
    }

    private fun serialize(map: Map<String, AppPolicy>): String {
        val apps = JSONObject()
        for ((pkg, policy) in map) {
            apps.put(
                pkg,
                JSONObject()
                    .put("favorite", policy.favorite)
                    .put("autostart", policy.autostart)
                    .put("persistence", policy.persistence)
                    .put("pinnedToTop", policy.pinnedToTop)
                    .put("hidden", policy.hidden),
            )
        }
        return JSONObject()
            .put("v", 1)
            .put("apps", apps)
            .toString()
    }
}

private class WhitelistStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
              package_name TEXT PRIMARY KEY,
              autostart INTEGER NOT NULL,
              persistence INTEGER NOT NULL,
              favorite INTEGER NOT NULL,
              pinned INTEGER NOT NULL,
              hidden INTEGER NOT NULL,
              protected INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }

    fun listAll(): List<AllowedApp> {
        val cur = readableDatabase.query(
            TABLE,
            arrayOf("package_name", "autostart", "persistence", "favorite", "pinned", "hidden", "protected"),
            null,
            null,
            null,
            null,
            "pinned DESC, favorite DESC, package_name ASC",
        )
        return cur.use { c ->
            val out = ArrayList<AllowedApp>(c.count)
            while (c.moveToNext()) out.add(mapRow(c))
            out
        }
    }

    fun upsertAll(items: List<AllowedApp>, now: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (a in items) {
                val v = ContentValues().apply {
                    put("package_name", a.packageName)
                    put("autostart", if (a.autostart) 1 else 0)
                    put("persistence", if (a.persistence) 1 else 0)
                    put("favorite", if (a.favorite) 1 else 0)
                    put("pinned", if (a.pinned) 1 else 0)
                    put("hidden", if (a.hidden) 1 else 0)
                    put("protected", if (a.protected) 1 else 0)
                    put("updated_at", now)
                }
                db.insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun delete(packageName: String) {
        writableDatabase.delete(TABLE, "package_name=?", arrayOf(packageName.trim()))
    }

    fun deleteNotIn(packages: Set<String>) {
        val normalized = packages.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cur = db.query(TABLE, arrayOf("package_name"), null, null, null, null, null)
            val toDelete = cur.use { c ->
                val out = ArrayList<String>(c.count)
                while (c.moveToNext()) {
                    val pkg = c.getString(0)
                    if (!normalized.contains(pkg)) out.add(pkg)
                }
                out
            }
            for (pkg in toDelete) db.delete(TABLE, "package_name=?", arrayOf(pkg))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun mapRow(c: Cursor): AllowedApp {
        return AllowedApp(
            packageName = c.getString(0),
            autostart = c.getInt(1) == 1,
            persistence = c.getInt(2) == 1,
            favorite = c.getInt(3) == 1,
            pinned = c.getInt(4) == 1,
            hidden = c.getInt(5) == 1,
            protected = c.getInt(6) == 1,
        )
    }

    companion object {
        private const val DB_NAME = "argos_whitelist.db"
        private const val DB_VERSION = 1
        private const val TABLE = "allowed_apps"
    }
}

class WhitelistManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val store = WhitelistStore(appContext)
    private val settings = SettingsManager(appContext)
    private val legacyPolicies = AppPolicyManager(appContext)

    private val _allowedApps = MutableStateFlow<List<AllowedApp>>(emptyList())
    val allowedApps: StateFlow<List<AllowedApp>> = _allowedApps.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val resolved = resolveAllowedApps()
        persistCache(resolved)
        _allowedApps.value = resolved
    }

    suspend fun getAllowedApps(): List<AllowedApp> = withContext(Dispatchers.IO) {
        if (_allowedApps.value.isEmpty()) refresh()
        _allowedApps.value
    }

    suspend fun setAllowedPackages(packages: Set<String>) = withContext(Dispatchers.IO) {
        val current = store.listAll().associateBy { it.packageName }
        val normalized = packages.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val target = normalized.map { pkg ->
            current[pkg] ?: AllowedApp(packageName = pkg)
        }
        val final = ensureFixedApps(target)
        store.deleteNotIn(final.map { it.packageName }.toSet())
        store.upsertAll(final, now = System.currentTimeMillis())
        persistCache(final)
        _allowedApps.value = final
    }

    suspend fun removeFromWhitelist(packageName: String) = withContext(Dispatchers.IO) {
        val pkg = packageName.trim()
        if (pkg.isBlank() || pkg == appContext.packageName) return@withContext
        store.delete(pkg)
        val final = ensureFixedApps(store.listAll())
        persistCache(final)
        _allowedApps.value = final
    }

    suspend fun update(packageName: String, block: (AllowedApp) -> AllowedApp) = withContext(Dispatchers.IO) {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return@withContext
        val current = store.listAll().associateBy { it.packageName }
        val updated = block(current[pkg] ?: AllowedApp(packageName = pkg))
        val final = ensureFixedApps(current.values.filterNot { it.packageName == pkg } + updated)
        store.upsertAll(final, now = System.currentTimeMillis())
        persistCache(final)
        _allowedApps.value = final
    }

    private suspend fun resolveAllowedApps(): List<AllowedApp> {
        val fromSqlite = store.listAll()
        if (fromSqlite.isNotEmpty()) return ensureFixedApps(fromSqlite)

        val fromApi = fetchFromApi()
        if (fromApi.isNotEmpty()) {
            store.upsertAll(fromApi, now = System.currentTimeMillis())
            return ensureFixedApps(fromApi)
        }

        val allowed = settings.getAllowedPackagesCached().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val pol = legacyPolicies.getAll()
        val fromCache = allowed.map { pkg ->
            val p = pol[pkg]
            AllowedApp(
                packageName = pkg,
                autostart = p?.autostart ?: false,
                persistence = p?.persistence ?: false,
                favorite = p?.favorite ?: false,
                pinned = p?.pinnedToTop ?: false,
                hidden = p?.hidden ?: false,
            )
        }
        if (fromCache.isNotEmpty()) {
            store.upsertAll(fromCache, now = System.currentTimeMillis())
            return ensureFixedApps(fromCache)
        }

        // Default: only Argos (ensureFixedApps will add it) + AnyDesk/Settings hidden for management
        return ensureFixedApps(emptyList())
    }

    private fun ensureFixedApps(list: List<AllowedApp>): List<AllowedApp> {
        val required = LinkedHashMap<String, AllowedApp>()
        for (a in list) required[a.packageName] = a

        // Argos agent is always visible (it IS the launcher)
        val self = appContext.packageName
        required[self] = (required[self] ?: AllowedApp(packageName = self)).copy(hidden = false, protected = true)

        // All com.anydesk.* packages are infrastructure (remote support) — always hidden regardless
        // of whether the operator added them to allowed_packages. Force hidden+protected even when
        // already present so ConfigurationEngine's kiosk_hidden_packages un-hide loop can't touch them.
        for (pkg in required.keys.toList()) {
            if (pkg.startsWith("com.anydesk.")) {
                required[pkg] = required[pkg]!!.copy(hidden = true, protected = true)
            }
        }
        val anyDesk = "com.anydesk.anydeskandroid"
        if (!required.containsKey(anyDesk)) {
            required[anyDesk] = AllowedApp(packageName = anyDesk, hidden = true, protected = true)
        }

        // Settings: in lock task for device management, always hidden from end-user launcher
        val settingsPkg = "com.android.settings"
        required[settingsPkg] = (required[settingsPkg] ?: AllowedApp(packageName = settingsPkg))
            .copy(hidden = true, protected = true)

        return required.values.toList()
    }


    private suspend fun fetchFromApi(): List<AllowedApp> {
        return emptyList()
    }

    private fun persistCache(list: List<AllowedApp>) {
        val pkgs = list.map { it.packageName }.toSet()
        runBlocking { settings.setAllowedPackages(pkgs) }

        val policies = list.associate { a ->
            a.packageName to AppPolicy(
                packageName = a.packageName,
                favorite = a.favorite,
                autostart = a.autostart,
                persistence = a.persistence,
                pinnedToTop = a.pinned,
                hidden = a.hidden,
            )
        }
        runBlocking { settings.setAppPoliciesJson(AppPolicyManager(appContext).run { serializeForCache(policies) }) }
    }
}

private fun AppPolicyManager.serializeForCache(map: Map<String, AppPolicy>): String {
    val apps = JSONObject()
    for ((pkg, policy) in map) {
        apps.put(
            pkg,
            JSONObject()
                .put("favorite", policy.favorite)
                .put("autostart", policy.autostart)
                .put("persistence", policy.persistence)
                .put("pinnedToTop", policy.pinnedToTop)
                .put("hidden", policy.hidden),
        )
    }
    return JSONObject()
        .put("v", 1)
        .put("apps", apps)
        .toString()
}

class AutoStartManager(private val context: Context) {
    private val settings = SettingsManager(context.applicationContext)
    private val whitelist = WhitelistManager(context.applicationContext)

    suspend fun getAutostartPackages(): Set<String> {
        val legacy = settings.getAutostartPackageCached().trim().takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet()
        val fromPolicy = whitelist.getAllowedApps().asSequence().filter { it.autostart && !it.hidden }.map { it.packageName }.toSet()
        return legacy + fromPolicy
    }

    suspend fun getAutostartPackagesInPriorityOrder(): List<String> {
        val preferred = settings.getAutostartPackageCached().trim()
        val fromPolicy = whitelist.getAllowedApps()
            .asSequence()
            .filter { it.autostart && !it.hidden }
            .map { it.packageName.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val ordered = LinkedHashSet<String>()
        if (preferred.isNotBlank()) ordered.add(preferred)
        val playerPkg = PlayerAppConstants.resolvePackage()
        if (preferred.isBlank() && fromPolicy.any { it == playerPkg }) {
            ordered.add(playerPkg)
        }
        fromPolicy.forEach { ordered.add(it) }
        return ordered.toList()
    }
}

class PersistenceManager(private val context: Context) {
    private val whitelist = WhitelistManager(context.applicationContext)

    suspend fun getPersistencePackages(): Set<String> {
        return whitelist.getAllowedApps().asSequence().filter { it.persistence && !it.hidden }.map { it.packageName }.toSet()
    }
}

class KioskPolicyManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)

    fun applyNow(): String? {
        val self = appContext.packageName
        val dpm = DeviceOwnerPolicyManager(appContext)
        if (!dpm.isDeviceOwner(self)) return "not_device_owner"

        val allowed = settings.getAllowedPackagesCached() + self
        dpm.tryUnsuspendApp(self, PlayerAppConstants.resolveInstalledPackage(appContext))
        return dpm.updateLockTaskPackages(self, allowed)
    }
}

class LauncherAppRepository(
    private val context: Context,
    private val whitelistManager: WhitelistManager,
) {
    val allowedApps: StateFlow<List<AllowedApp>> = whitelistManager.allowedApps

    suspend fun refresh() {
        whitelistManager.refresh()
    }

    fun listInstalledApplications(): List<ApplicationInfo> {
        return runCatching { context.packageManager.getInstalledApplications(0) }.getOrDefault(emptyList())
    }
}

class LauncherViewModel(
    private val repo: LauncherAppRepository,
    private val scope: CoroutineScope,
) {
    val allowedApps: StateFlow<List<AllowedApp>> = repo.allowedApps

    fun refresh() {
        scope.launch { repo.refresh() }
    }
}

class WatchdogManager(private val context: Context) {
    private val appContext = context.applicationContext

    fun runNow() {
        runBlocking {
            val autostart = AutoStartManager(appContext).getAutostartPackagesInPriorityOrder()
            val persistence = PersistenceManager(appContext).getPersistencePackages()
            ensureRunning(autostart + persistence)
        }
    }

    fun ensureRunning(packages: Iterable<String>) {
        val foreground = currentForegroundPackage()
        val now = SystemClock.elapsedRealtime()
        for (pkg in packages) {
            val normalized = pkg.trim()
            if (normalized.isBlank()) continue
            if (foreground != null && foreground.equals(normalized, ignoreCase = true)) continue
            if (isRunning(normalized)) continue
            val last = lastLaunchByPkg[normalized] ?: 0L
            if (now - last < LAUNCH_COOLDOWN_MS) continue
            val ok = launch(normalized)
            if (ok) lastLaunchByPkg[normalized] = now
        }
    }

    fun isRunning(packageName: String): Boolean {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val procs = runCatching { am.runningAppProcesses }.getOrNull().orEmpty()
        for (p in procs) {
            val list = p.pkgList ?: continue
            if (list.any { it.equals(packageName, ignoreCase = true) }) return true
        }
        return false
    }

    fun launch(packageName: String): Boolean {
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun currentForegroundPackage(): String? {
        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val end = System.currentTimeMillis()
        val begin = end - 15_000L
        val events = runCatching { usm.queryEvents(begin, end) }.getOrNull() ?: return null

        var lastPkg: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                val p = e.packageName?.trim().orEmpty()
                if (p.isNotBlank()) lastPkg = p
            }
        }
        return lastPkg
    }

    companion object {
        private const val LAUNCH_COOLDOWN_MS = 60_000L
        private val lastLaunchByPkg: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    }
}

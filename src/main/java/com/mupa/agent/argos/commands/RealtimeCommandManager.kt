package com.mupa.agent.argos.commands

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.mupa.agent.argos.BuildConfig
import com.mupa.agent.argos.managers.DeviceIdentityManager
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.services.PollingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class RealtimeCommandManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identity = DeviceIdentityManager(appContext)
    private val settings = SettingsManager(appContext)
    private val eventPrefs = appContext.getSharedPreferences(EVENT_PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var subscriptions: List<Pair<DatabaseReference, ValueEventListener>> = emptyList()
    private val lastSeqCache = ConcurrentHashMap<String, Long>()
    private var wakeupJob: Job? = null
    @Volatile
    private var lastWakeupAt: Long = 0L

    fun start(): String? {
        if (subscriptions.isNotEmpty()) return null

        val dbUrl = BuildConfig.FIREBASE_DATABASE_URL.trim()
        val apiKey = BuildConfig.FIREBASE_API_KEY.trim()
        val appId = BuildConfig.FIREBASE_APP_ID.trim()
        val projectId = BuildConfig.FIREBASE_PROJECT_ID.trim()
        val deviceId = runBlocking { identity.getPersistentId() }.trim()

        if (dbUrl.isBlank() || apiKey.isBlank() || appId.isBlank() || projectId.isBlank()) return "firebase_not_configured"
        if (deviceId.isBlank()) return "device_id_unavailable"
        // Note: argos_api_url is intentionally NOT required. The legacy command listener below
        // (m_argos/devices/{id}/commands) runs entirely over Firebase and reports to Firebase
        // logs, so it works on group-linked devices that have no HTTP API configured. The seq
        // event-bus listeners still schedule PollingService wakeups; those simply no-op when
        // there's no API to poll.

        val firebaseApp = ensureFirebaseInitialized(dbUrl, apiKey, appId, projectId)
        val db = FirebaseDatabase.getInstance(firebaseApp, dbUrl)
        // getBoundDeviceGroupCached() is a human-entered group NAME (e.g. "Mupa Teste 2.2"),
        // not a slug — Firebase RTDB rejects any path segment containing '.', '#', '$', '[',
        // ']', or '/', so it must be sanitized before use as a child() key. Crashed every
        // single time the agent started on any device whose group name happened to contain
        // one of those characters (confirmed on a real Android 9 box with group "Mupa Teste
        // 2.2" — the period alone was enough to throw DatabaseException at startup).
        val groupId = sanitizeFirebaseKey(settings.getBoundDeviceGroupCached().trim())

        val subs = ArrayList<Pair<DatabaseReference, ValueEventListener>>(5)
        subscribeSeq(db.getReference("device_events").child(deviceId).child("seq"), "device:$deviceId", subs)
        if (groupId.isNotBlank()) {
            subscribeSeq(db.getReference("group_events").child(groupId).child("seq"), "group:$groupId", subs)
        }
        subscribeSeq(db.getReference("global_events").child("seq"), "global", subs)

        // Directly listens to the legacy "m_argos/devices/{id}/commands" node and executes
        // whatever is written there. Some channels still write commands this way (a simple
        // {comando, timestamp, ...extra} object) instead of going through the Supabase
        // device_commands queue + seq bump — rather than leave those commands stranded, the
        // agent now reacts to this path directly too.
        //
        // The legacy writer keys devices by the bare Android serial (e.g. "24067524701479",
        // matching `adb devices`), NOT by the agent's own persistent id (which is usually
        // "IMEI:..." or similar) — so we must listen on the plain serial path too, not just
        // deviceId, or every legacy command silently misses this device entirely.
        subscribeLegacyCommand(db.getReference("m_argos").child("devices").child(deviceId).child("commands"), deviceId, subs)
        val plainSerial = identity.getDeviceFingerprint().serial?.trim().orEmpty()
        if (plainSerial.isNotBlank() && plainSerial != deviceId) {
            subscribeLegacyCommand(db.getReference("m_argos").child("devices").child(plainSerial).child("commands"), plainSerial, subs)
        }

        subscriptions = subs

        Log.i(TAG, "event_bus_started device=$deviceId group=${groupId.ifBlank { "-" }}")
        return null
    }

    fun stop() {
        val subs = subscriptions
        for ((ref, listener) in subs) {
            runCatching { ref.removeEventListener(listener) }
        }
        subscriptions = emptyList()
        wakeupJob?.cancel()
        wakeupJob = null
    }

    fun triggerImmediatePresencePush() {
        scheduleWakeup("manual", force = true)
    }

    private fun subscribeSeq(
        ref: DatabaseReference,
        key: String,
        sink: MutableList<Pair<DatabaseReference, ValueEventListener>>,
    ) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val seq = snapshot.getValue(Long::class.java)
                    ?: snapshot.getValue(Int::class.java)?.toLong()
                    ?: snapshot.getValue(String::class.java)?.trim()?.toLongOrNull()
                    ?: 0L
                val previous = lastKnownSeq(key)
                if (previous == Long.MIN_VALUE) {
                    persistSeq(key, seq)
                    Log.d(TAG, "event_bus_init key=$key seq=$seq")
                    return
                }
                if (seq <= previous) return
                persistSeq(key, seq)
                Log.i(TAG, "event_bus_signal key=$key seq=$seq prev=$previous")
                scheduleWakeup(key, force = false)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "event_bus_cancelled key=$key code=${error.code} msg=${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        sink.add(ref to listener)
    }

    /**
     * Listens to `m_argos/devices/{deviceId}/commands` — a flat object like
     * `{ "comando": "reboot_device", "timestamp": 1782155158350 }`, optionally with extra
     * fields (e.g. `"url"` for install-type commands). On change, executes it directly via
     * CommandDispatcher instead of going through the HTTP poll cycle, since the full command
     * (not just a wake-up signal) is already sitting in the snapshot.
     *
     * Dedupes by `timestamp` the same way subscribeSeq() dedupes by `seq`, and treats the very
     * first read after (re)connecting as "already seen" so the agent doesn't replay a stale
     * command that was sitting there before this listener attached.
     */
    private fun subscribeLegacyCommand(
        ref: DatabaseReference,
        deviceId: String,
        sink: MutableList<Pair<DatabaseReference, ValueEventListener>>,
    ) {
        val dedupeKey = "legacy_command:$deviceId"
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val comando = snapshot.child("comando").getValue(String::class.java)?.trim().orEmpty()
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java)
                    ?: snapshot.child("timestamp").getValue(Double::class.java)?.toLong()
                    ?: 0L
                if (comando.isBlank()) return

                val previous = lastKnownSeq(dedupeKey)
                if (previous == Long.MIN_VALUE) {
                    persistSeq(dedupeKey, timestamp)
                    Log.d(TAG, "legacy_command_init comando=$comando ts=$timestamp")
                    return
                }
                if (timestamp <= previous) return
                persistSeq(dedupeKey, timestamp)
                Log.i(TAG, "legacy_command_received comando=$comando ts=$timestamp")

                val params = JSONObject()
                for (child in snapshot.children) {
                    val key = child.key ?: continue
                    if (key == "comando" || key == "timestamp") continue
                    runCatching { params.put(key, child.value) }
                }

                // m_argos/devices/{id}/commands' sibling node — the web console
                // (console.tsx) reads results from here, not from any Supabase-backed
                // table, since commands sent through this side panel never touch the
                // Supabase device_commands queue at all.
                val logsRef = ref.parent?.child("logs")

                scope.launch {
                    val result = runCatching {
                        CommandDispatcher(appContext).execute(
                            commandId = "firebase_legacy",
                            command = comando,
                            params = params,
                        )
                    }.getOrElse { t ->
                        Log.w(TAG, "legacy_command_exec_failed comando=$comando err=${t.message}")
                        logsRef?.push()?.setValue(
                            mapOf(
                                "comando" to comando,
                                "executado_em" to System.currentTimeMillis(),
                                "status" to "error",
                                "detalhe" to (t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }),
                            ),
                        )
                        return@launch
                    }
                    Log.i(TAG, "legacy_command_result comando=$comando status=${result.status} msg=${result.message}")
                    val webStatus = when (result.status.lowercase()) {
                        "success" -> "success"
                        "delegated" -> "delegated"
                        else -> "error"
                    }
                    logsRef?.push()?.setValue(
                        mapOf(
                            "comando" to comando,
                            "executado_em" to System.currentTimeMillis(),
                            "status" to webStatus,
                            "detalhe" to result.message,
                        ),
                    )
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "legacy_command_cancelled device=$deviceId code=${error.code} msg=${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        sink.add(ref to listener)
    }

    private fun scheduleWakeup(reason: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWakeupAt < WAKEUP_DEBOUNCE_MS) return
        lastWakeupAt = now
        wakeupJob?.cancel()
        wakeupJob = scope.launch {
            runCatching { PollingService(appContext).tick() }
                .onFailure { error -> Log.w(TAG, "event_bus_tick_failed reason=$reason err=${error.message}") }
        }
    }

    private fun lastKnownSeq(key: String): Long {
        return lastSeqCache[key] ?: eventPrefs.getLong(seqPrefKey(key), Long.MIN_VALUE).also {
            lastSeqCache[key] = it
        }
    }

    private fun persistSeq(key: String, value: Long) {
        lastSeqCache[key] = value
        eventPrefs.edit().putLong(seqPrefKey(key), value).apply()
    }

    private fun seqPrefKey(key: String): String = "seq_${key.replace(':', '_')}"

    /** Firebase RTDB path segments can't contain '.', '#', '$', '[', ']', or '/' — replaces
     *  each with '_' so any free-text value (group names, etc.) is safe to use as a key. */
    private fun sanitizeFirebaseKey(raw: String): String = raw.replace(Regex("[.#$\\[\\]/]"), "_")

    private fun ensureFirebaseInitialized(
        dbUrl: String,
        apiKey: String,
        appId: String,
        projectId: String,
    ): FirebaseApp {
        FirebaseApp.getApps(appContext).firstOrNull { it.name == APP_NAME }?.let { return it }
        synchronized(javaClass) {
            FirebaseApp.getApps(appContext).firstOrNull { it.name == APP_NAME }?.let { return it }
            val options = FirebaseOptions.Builder()
                .setApiKey(apiKey)
                .setApplicationId(appId)
                .setProjectId(projectId)
                .setDatabaseUrl(dbUrl)
                .build()
            return FirebaseApp.initializeApp(appContext, options, APP_NAME)!!
        }
    }

    companion object {
        private const val TAG = "RtEventBus"
        private const val APP_NAME = "argos-event-bus"
        private const val EVENT_PREFS = "argos_rtdb_event_bus"
        private const val WAKEUP_DEBOUNCE_MS = 1_500L
    }
}

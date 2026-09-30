package com.mupa.agent.argos.ui.compose.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mupa.agent.argos.admin.AdminToolsManager
import com.mupa.agent.argos.admin.DiagnosticsManager
import com.mupa.agent.argos.admin.LauncherManager
import com.mupa.agent.argos.admin.WifiManager
import com.mupa.agent.argos.R
import com.mupa.agent.argos.launcher.WhitelistManager
import com.mupa.agent.argos.managers.PlayerAppConstants
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.security.AdminAuditLogStore
import com.mupa.agent.argos.security.AdminAuthManager
import com.mupa.agent.argos.security.AdminAuthResult
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import androidx.core.content.ContextCompat
import com.mupa.agent.argos.ui.compose.GlassCard
import com.mupa.agent.argos.ui.compose.LucideIcon
import com.mupa.agent.argos.ui.compose.LocalArgosDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun AdminCenterScreen(
    onBack: () -> Unit,
    onOpenKioskApps: () -> Unit,
    onOpenSetup: () -> Unit,
    title: String = "Ferramentas Administrativas",
    unlockedSession: Boolean = false,
    recoveryMode: Boolean = false,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    val tools = remember { AdminToolsManager(context.applicationContext) }
    val launcher = remember { LauncherManager(context.applicationContext) }
    val wifi = remember { WifiManager(context.applicationContext) }
    val diagnostics = remember { DiagnosticsManager(context.applicationContext) }
    val settings = remember { SettingsManager(context.applicationContext) }
    val whitelist = remember { WhitelistManager(context.applicationContext) }
    val dpm = remember { DeviceOwnerPolicyManager(context.applicationContext) }

    var lockTaskStatus by remember { mutableStateOf("—") }
    var launcherStatus by remember { mutableStateOf("—") }
    var kioskStatus by remember { mutableStateOf("—") }
    var firebaseStatus by remember { mutableStateOf("🟡 —") }
    var internetStatus by remember { mutableStateOf("🟡 —") }
    var apiStatus by remember { mutableStateOf("🟡 —") }
    var watchdogStatus by remember { mutableStateOf("🟡 —") }
    var deviceOwnerStatus by remember { mutableStateOf("🟡 —") }
    var mplayerStatus by remember { mutableStateOf("🟡 —") }
    var messageDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun refreshStatuses() {
        val state = tools.lockTaskState()
        lockTaskStatus = when (state) {
            0 -> "🔴 Desativado"
            1 -> "🟡 Pinned"
            2 -> "🟢 Ativo"
            else -> "🟡 Estado: $state"
        }
        val homePkg = launcher.currentHomePackage().ifBlank { "-" }
        launcherStatus = if (launcher.isArgosDefaultHome()) "🟢 Argos ($homePkg)" else "🟡 $homePkg"

        val locked = settings.getMdmLockedCached() || settings.getKioskModeCached()
        val allowed = settings.getAllowedPackagesCached().size + 1
        kioskStatus = if (!dpm.isDeviceOwner(context.packageName)) {
            "🟡 Sem Device Owner"
        } else if (locked) {
            "🟢 Ativo ($allowed apps)"
        } else {
            "🔴 Desativado"
        }

        internetStatus = resolveInternet(context)
        deviceOwnerStatus = if (dpm.isDeviceOwner(context.packageName)) "🟢 Device Owner" else "🔴 Sem Device Owner"
        mplayerStatus = if (isInstalled(context, PlayerAppConstants.resolvePackage())) "🟢 OK" else "🔴 Não instalado"
        watchdogStatus = if (isServiceRunning(context, "com.mupa.agent.argos.services.ArgosForegroundService")) "🟢 Ativo" else "🔴 Inativo"
    }

    LaunchedEffect(Unit) {
        refreshStatuses()
    }

    DisposableEffect(Unit) {
        val ref = FirebaseDatabase.getInstance().getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) == true
                firebaseStatus = if (connected) "🟢 Conectado" else "🔴 Desconectado"
            }

            override fun onCancelled(error: DatabaseError) {
                firebaseStatus = "🟡 Erro"
            }
        }
        ref.addValueEventListener(listener)
        onDispose { ref.removeEventListener(listener) }
    }

    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingActionName by remember { mutableStateOf("") }

    val dimens = LocalArgosDimens.current
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(dimens.padding),
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.logo_argos_hor),
                contentDescription = "ARGOS",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            )
            Spacer(modifier = Modifier.height(dimens.gap))
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.gap), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                LucideIcon(
                    name = "lock",
                    modifier = Modifier.height(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(title, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (unlockedSession) "Sessão desbloqueada" else "Ações críticas exigem senha",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(dimens.gap))
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.gap)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onBack) { Text("Voltar") }
                Button(modifier = Modifier.weight(1f), onClick = onOpenSetup) { Text("Permissões") }
            }
        }

        Spacer(modifier = Modifier.height(dimens.gap))

        if (recoveryMode) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Internet: $internetStatus", modifier = Modifier.weight(1f))
                    Text("Firebase: $firebaseStatus", modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("API: $apiStatus", modifier = Modifier.weight(1f))
                    Text("Watchdog: $watchdogStatus", modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("LockTask: $lockTaskStatus", modifier = Modifier.weight(1f))
                    Text("DO: $deviceOwnerStatus", modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Launcher: $launcherStatus", modifier = Modifier.weight(1f))
                    Text("MPlayer: $mplayerStatus", modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        SectionCard(
            title = "Lock Task",
            status = lockTaskStatus,
            primary = "Ativar Lock Task" to {
                if (activity != null) {
                    pendingActionName = "lock_task_start"
                    pendingAction = {
                        val err = tools.startLockTask(activity)
                        messageDialog = "Lock Task" to (err ?: "OK")
                        refreshStatuses()
                    }
                } else {
                    messageDialog = "Lock Task" to "no_activity_context"
                }
            },
            secondary = "Desativar Lock Task" to {
                if (activity != null) {
                    pendingActionName = "lock_task_stop"
                    pendingAction = {
                        val err = tools.stopLockTask(activity)
                        messageDialog = "Lock Task" to (err ?: "OK")
                        refreshStatuses()
                    }
                } else {
                    messageDialog = "Lock Task" to "no_activity_context"
                }
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        SectionCard(
            title = "Modo Kiosk",
            status = kioskStatus,
            primary = "Ativar Kiosk" to {
                pendingActionName = "kiosk_on"
                pendingAction = {
                    if (!dpm.isDeviceOwner(context.packageName)) {
                        messageDialog = "Kiosk" to "Este recurso exige Device Owner"
                    } else {
                        scope.launch {
                            val required = setOf(
                                context.packageName,
                                PlayerAppConstants.resolvePackage(),
                                "com.anydesk.anydeskandroid",
                                "com.android.settings",
                            )
                            val merged = (settings.getAllowedPackagesCached() + required).map { it.trim() }.filter { it.isNotBlank() }.toSet()
                            whitelist.setAllowedPackages(merged)
                            settings.setKioskMode(true)
                            settings.setMdmLocked(true)
                            dpm.applyLocked(context.packageName, settings.getAllowedPackagesCached())
                            refreshStatuses()
                            var lockErr: String? = null
                            if (activity != null) {
                                for (i in 1..3) {
                                    lockErr = tools.startLockTask(activity)
                                    if (lockErr == null) break
                                    delay(500)
                                }
                            } else {
                                lockErr = "no_activity_context"
                            }

                            val playerPkg = PlayerAppConstants.resolvePackage()
                            val playerIntent = context.packageManager.getLaunchIntentForPackage(playerPkg)
                            if (playerIntent != null) {
                                DeviceOwnerPolicyManager(context).tryUnsuspendApp(context.packageName, playerPkg)
                                playerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                                runCatching { context.startActivity(playerIntent) }
                            }

                            refreshStatuses()
                            val state = tools.lockTaskState()
                            messageDialog = "Kiosk" to when {
                                lockErr == null -> "Ativado (LockTask: $state)"
                                else -> "Ativado, mas LockTask falhou (LockTask: $state): $lockErr"
                            }
                        }
                    }
                }
            },
            secondary = "Desativar Kiosk" to {
                pendingActionName = "kiosk_off"
                pendingAction = {
                    if (!dpm.isDeviceOwner(context.packageName)) {
                        messageDialog = "Kiosk" to "Este recurso exige Device Owner"
                    } else {
                        scope.launch {
                            settings.setMdmLocked(false)
                            settings.setKioskMode(false)
                            dpm.applyUnlocked(context.packageName)
                            runCatching { dpm.setAndroidLauncherAsHome(context.packageName) }
                            if (activity != null) tools.stopLockTask(activity)
                            refreshStatuses()
                            messageDialog = "Kiosk" to "Desativado"
                        }
                    }
                }
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        SectionCard(
            title = "Aplicativos do Kiosk",
            status = null,
            primary = "Abrir Whitelist" to {
                pendingActionName = "open_kiosk_apps"
                pendingAction = onOpenKioskApps
            },
            secondary = "Abrir MPlayer" to {
                pendingActionName = "open_player"
                pendingAction = {
                    val playerPkg = PlayerAppConstants.resolvePackage()
                    val i = context.packageManager.getLaunchIntentForPackage(playerPkg)
                    if (i == null) {
                        messageDialog = "MPlayer" to "Não instalado"
                    } else {
                        DeviceOwnerPolicyManager(context).tryUnsuspendApp(context.packageName, playerPkg)
                        val err = runCatching {
                            context.startActivity(i)
                            null
                        }.getOrElse { t ->
                            t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
                        }
                        if (err != null) {
                            messageDialog = "MPlayer" to "Bloqueado pelo modo Kiosk/LockTask: $err"
                        }
                    }
                }
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (recoveryMode) {
            val tempUntil = settings.getTemporaryUnlockUntilMsCached()
            val tempActive = tempUntil > System.currentTimeMillis()
            val tempStatus = if (tempActive) {
                val remaining = ((tempUntil - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                "🟡 Ativo (${remaining}min)"
            } else {
                "🔴 Inativo"
            }
            val maintenance = settings.getMaintenanceModeEnabledCached()
            SectionCard(
                title = "Liberação temporária",
                status = "$tempStatus • " + if (maintenance) "🟡 Manutenção" else "🔴 Normal",
                primary = "Liberar 5 min" to {
                    pendingActionName = "temporary_unlock_5m"
                    pendingAction = {
                        scope.launch {
                            if (!dpm.isDeviceOwner(context.packageName)) {
                                messageDialog = "Liberação" to "Este recurso exige Device Owner"
                                return@launch
                            }
                            val prevLocked = settings.getMdmLockedCached()
                            val prevKiosk = settings.getKioskModeCached()
                            settings.setTemporaryUnlock(System.currentTimeMillis() + 5 * 60_000L, prevLocked, prevKiosk)
                            settings.setMdmLocked(false)
                            settings.setKioskMode(false)
                            settings.setMaintenanceMode(false, 0L)
                            dpm.applyUnlocked(context.packageName)
                            runCatching { dpm.setAndroidLauncherAsHome(context.packageName) }
                            if (activity != null) tools.stopLockTask(activity)
                            refreshStatuses()
                            messageDialog = "Liberação" to "OK"
                        }
                    }
                },
                secondary = "Liberar 15 min" to {
                    pendingActionName = "temporary_unlock_15m"
                    pendingAction = {
                        scope.launch {
                            if (!dpm.isDeviceOwner(context.packageName)) {
                                messageDialog = "Liberação" to "Este recurso exige Device Owner"
                                return@launch
                            }
                            val prevLocked = settings.getMdmLockedCached()
                            val prevKiosk = settings.getKioskModeCached()
                            settings.setTemporaryUnlock(System.currentTimeMillis() + 15 * 60_000L, prevLocked, prevKiosk)
                            settings.setMdmLocked(false)
                            settings.setKioskMode(false)
                            settings.setMaintenanceMode(false, 0L)
                            dpm.applyUnlocked(context.packageName)
                            runCatching { dpm.setAndroidLauncherAsHome(context.packageName) }
                            if (activity != null) tools.stopLockTask(activity)
                            refreshStatuses()
                            messageDialog = "Liberação" to "OK"
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))

            SectionCard(
                title = "Liberação extra",
                status = tempStatus,
                primary = "Liberar 30 min" to {
                    pendingActionName = "temporary_unlock_30m"
                    pendingAction = {
                        scope.launch {
                            if (!dpm.isDeviceOwner(context.packageName)) {
                                messageDialog = "Liberação" to "Este recurso exige Device Owner"
                                return@launch
                            }
                            val prevLocked = settings.getMdmLockedCached()
                            val prevKiosk = settings.getKioskModeCached()
                            settings.setTemporaryUnlock(System.currentTimeMillis() + 30 * 60_000L, prevLocked, prevKiosk)
                            settings.setMdmLocked(false)
                            settings.setKioskMode(false)
                            settings.setMaintenanceMode(false, 0L)
                            dpm.applyUnlocked(context.packageName)
                            runCatching { dpm.setAndroidLauncherAsHome(context.packageName) }
                            if (activity != null) tools.stopLockTask(activity)
                            refreshStatuses()
                            messageDialog = "Liberação" to "OK"
                        }
                    }
                },
                secondary = "Cancelar liberação" to {
                    pendingActionName = "temporary_unlock_cancel"
                    pendingAction = {
                        scope.launch {
                            if (!dpm.isDeviceOwner(context.packageName)) {
                                messageDialog = "Liberação" to "Este recurso exige Device Owner"
                                return@launch
                            }
                            val restoreLocked = settings.getTemporaryUnlockPrevMdmLockedCached()
                            val restoreKiosk = settings.getTemporaryUnlockPrevKioskModeCached()
                            settings.clearTemporaryUnlock()
                            if (restoreLocked || restoreKiosk) {
                                settings.setMdmLocked(restoreLocked)
                                settings.setKioskMode(restoreKiosk)
                                dpm.setLauncherAsHome(context.packageName, enabled = true)
                                dpm.updateLockTaskPackages(context.packageName, settings.getAllowedPackagesCached())
                                dpm.applyLocked(context.packageName, settings.getAllowedPackagesCached())
                                if (activity != null) tools.startLockTask(activity)
                            }
                            refreshStatuses()
                            messageDialog = "Liberação" to "OK"
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))

            SectionCard(
                title = "Modo manutenção",
                status = if (maintenance) "🟡 Ativo" else "🔴 Inativo",
                primary = "Ativar" to {
                    pendingActionName = "maintenance_on"
                    pendingAction = {
                        scope.launch {
                            if (!dpm.isDeviceOwner(context.packageName)) {
                                messageDialog = "Manutenção" to "Este recurso exige Device Owner"
                                return@launch
                            }
                            settings.clearTemporaryUnlock()
                            settings.setMaintenanceMode(true, 0L)
                            settings.setMdmLocked(false)
                            settings.setKioskMode(false)
                            dpm.applyUnlocked(context.packageName)
                            runCatching { dpm.setAndroidLauncherAsHome(context.packageName) }
                            if (activity != null) tools.stopLockTask(activity)
                            refreshStatuses()
                            messageDialog = "Manutenção" to "Ativado"
                        }
                    }
                },
                secondary = "Desativar" to {
                    pendingActionName = "maintenance_off"
                    pendingAction = {
                        scope.launch {
                            settings.setMaintenanceMode(false, 0L)
                            refreshStatuses()
                            messageDialog = "Manutenção" to "Desativado"
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        SectionCard(
            title = "Launcher Argos",
            status = launcherStatus,
            primary = "Ativar Launcher Argos" to {
                pendingActionName = "set_launcher_argos"
                pendingAction = {
                    val err = launcher.setArgosAsDefaultHomeIfDeviceOwner()
                    messageDialog = "Launcher" to (err ?: "OK")
                    refreshStatuses()
                }
            },
            secondary = "Restaurar Android" to {
                pendingActionName = "restore_launcher_android"
                pendingAction = {
                    val err = launcher.restoreAndroidHomeIfDeviceOwner()
                    messageDialog = "Launcher" to (err ?: "OK")
                    refreshStatuses()
                }
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (recoveryMode) {
            SectionCard(
                title = "Aplicativos",
                status = null,
                primary = "Reiniciar Agent Argos" to {
                    pendingActionName = "restart_argos"
                    pendingAction = {
                        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            ContextCompat.startForegroundService(context, Intent(context, com.mupa.agent.argos.services.ArgosForegroundService::class.java))
                            context.startActivity(launch)
                            android.os.Process.killProcess(android.os.Process.myPid())
                        } else {
                            messageDialog = "Agent" to "launch_intent_null"
                        }
                    }
                },
                secondary = "Reiniciar MPlayer" to {
                    pendingActionName = "restart_mplayer"
                    pendingAction = {
                        scope.launch {
                            val pkg = PlayerAppConstants.resolvePackage()
                            runCatching { tools.closeAppIfDeviceOwner(pkg) }
                            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                            if (intent == null) {
                                messageDialog = "MPlayer" to "Não instalado"
                            } else {
                                DeviceOwnerPolicyManager(context).tryUnsuspendApp(context.packageName, pkg)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                                runCatching { context.startActivity(intent) }
                                messageDialog = "MPlayer" to "OK"
                            }
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        SectionCard(
            title = "Wi‑Fi",
            status = null,
            primary = "Abrir Wi‑Fi" to {
                pendingActionName = "open_wifi_settings"
                pendingAction = {
                    if (activity != null) {
                        tools.stopLockTask(activity)
                    }
                    runCatching { dpm.updateLockTaskPackages(context.packageName, settings.getAllowedPackagesCached() + "com.android.settings") }
                    runCatching { context.startActivity(wifi.openWifiSettings()) }
                }
            },
            secondary = "Configurações" to {
                pendingActionName = "open_settings"
                pendingAction = {
                    if (activity != null) {
                        tools.stopLockTask(activity)
                    }
                    runCatching { dpm.updateLockTaskPackages(context.packageName, settings.getAllowedPackagesCached() + "com.android.settings") }
                    val err = runCatching {
                        context.startActivity(tools.openAndroidSettings())
                        null
                    }.getOrElse { it.message?.trim().orEmpty().ifBlank { it.javaClass.simpleName } }
                    if (err != null) messageDialog = "Configurações" to err
                }
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (recoveryMode) {
            val overlayAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context.applicationContext)
            } else {
                true
            }
            SectionCard(
                title = "Botão flutuante",
                status = if (overlayAllowed) "🟢 Permitido" else "🔴 Bloqueado",
                primary = "Ativar permissão" to {
                    pendingActionName = "overlay_permission"
                    pendingAction = {
                        val uri = Uri.parse("package:${context.packageName}")
                        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (activity != null) tools.stopLockTask(activity)
                        runCatching { context.startActivity(i) }
                    }
                },
                secondary = "Abrir Recovery Gate" to {
                    pendingActionName = "open_recovery_gate"
                    pendingAction = {
                        val i = Intent(context, com.mupa.agent.argos.ui.ArgosLauncherActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra(com.mupa.agent.argos.ui.ArgosLauncherActivity.EXTRA_NAV_ROUTE, com.mupa.agent.argos.ui.compose.Routes.RecoveryGate)
                        runCatching { context.startActivity(i) }
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        SectionCard(
            title = "Dispositivo",
            status = null,
            primary = "Desligar dispositivo" to {
                pendingActionName = "shutdown_device"
                pendingAction = {
                    val err = tools.requestShutdown()
                    messageDialog = "Desligar" to (err ?: "OK")
                }
            },
            secondary = "Reiniciar dispositivo" to {
                pendingActionName = "reboot_device"
                pendingAction = {
                    val err = tools.rebootIfDeviceOwner()
                    messageDialog = "Reiniciar" to (err ?: "OK")
                }
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        SectionCard(
            title = "Diagnóstico avançado",
            status = null,
            primary = "Mostrar diagnóstico" to {
                pendingActionName = "show_diagnostics"
                pendingAction = {
                    val auditText = buildString {
                        val list = AdminAuditLogStore(context).listRecent(30)
                        for (e in list) append("${e.ts} ${e.action} ${if (e.success) "ok" else "fail"}\n")
                    }.trim()
                    val payload = diagnostics.snapshotText() + if (auditText.isNotBlank()) "\n\nAudit:\n$auditText" else ""
                    messageDialog = "Diagnóstico" to payload
                }
            },
            secondary = "Apps" to {
                pendingActionName = "open_app_settings"
                pendingAction = { context.startActivity(tools.openAppSettings()) }
            },
        )

        if (recoveryMode) {
            Spacer(modifier = Modifier.height(12.dp))
            SectionCard(
                title = "Internet / Sync",
                status = null,
                primary = "Testar internet" to {
                    pendingActionName = "test_internet"
                    pendingAction = {
                        scope.launch {
                            val (ok, latency) = testUrl("https://clients3.google.com/generate_204", 2000)
                            val base = settings.getArgosApiUrlCached().trim().trimEnd('/')
                            val apiUrl = if (base.isBlank()) "" else (if (base.endsWith("/api", true)) base else "$base/api")
                            val (apiOk, apiLatency) = if (apiUrl.isBlank()) false to -1L else testUrl(apiUrl, 2500)
                            apiStatus = if (apiOk) "🟢 OK (${apiLatency}ms)" else "🔴 Falha"
                            internetStatus = if (ok) "🟢 OK (${latency}ms)" else "🔴 Offline"
                            messageDialog = "Internet" to "Internet: ${if (ok) "OK" else "Falha"} (${latency}ms)\nAPI: ${if (apiOk) "OK" else "Falha"} (${apiLatency}ms)"
                        }
                    }
                },
                secondary = "Sincronizar agora" to {
                    pendingActionName = "sync_now"
                    pendingAction = {
                        com.mupa.agent.argos.commands.ArgosCommandScheduler.triggerOnce(context.applicationContext)
                        messageDialog = "Sync" to "Solicitado"
                    }
                },
            )
        }
    }

    val action = pendingAction
    if (action != null) {
        if (unlockedSession) {
            LaunchedEffect(action) {
                pendingAction = null
                action()
            }
        } else {
            AdminAuthDialog(
                actionName = pendingActionName,
                onDismiss = { pendingAction = null },
                onSuccess = {
                    pendingAction = null
                    action()
                },
            )
        }
    }

    val msg = messageDialog
    if (msg != null) {
        AlertDialog(
            onDismissRequest = { messageDialog = null },
            title = { Text(msg.first) },
            text = { Text(msg.second) },
            confirmButton = {
                Button(onClick = { messageDialog = null }) { Text("OK") }
            },
        )
    }
}

private fun isInstalled(context: Context, pkg: String): Boolean {
    return runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
}

private fun isServiceRunning(context: Context, className: String): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    val list = am.getRunningServices(Int.MAX_VALUE)
    return list.any { it.service.className == className }
}

private fun resolveInternet(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val network = cm.activeNetwork ?: return "🔴 Offline"
    val caps = cm.getNetworkCapabilities(network) ?: return "🔴 Offline"
    val hasInternet = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    return if (hasInternet) "🟢 Online" else "🔴 Offline"
}

private fun testUrl(url: String, timeoutMs: Int): Pair<Boolean, Long> {
    val t0 = System.currentTimeMillis()
    val ok = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "ArgosAgent/1.0")
        }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..399
    }.getOrDefault(false)
    val t1 = System.currentTimeMillis()
    return ok to (t1 - t0)
}

@Composable
private fun SectionCard(
    title: String,
    status: String?,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>,
) {
    val dimens = LocalArgosDimens.current
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (status != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(dimens.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.gap)) {
            Button(modifier = Modifier.weight(1f), onClick = primary.second) { Text(primary.first) }
            OutlinedButton(modifier = Modifier.weight(1f), onClick = secondary.second) { Text(secondary.first) }
        }
    }
}

@Composable
private fun AdminAuthDialog(
    actionName: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { AdminAuthManager(context) }
    val audit = remember { AdminAuditLogStore(context) }

    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var enableDemoMode by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }

    fun vibrateError() {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) {
            vib.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(120)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Área protegida") },
        text = {
            Column {
                Text("Digite a senha administrativa")
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(shake.value.roundToInt(), 0) },
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        val e = error
                        if (e != null) Text(e, color = MaterialTheme.colorScheme.error)
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Ativar modo demonstração", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(checked = enableDemoMode, onCheckedChange = { enableDemoMode = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val result = auth.authenticate(password)
                        when (result) {
                            is AdminAuthResult.Success -> {
                                audit.add(
                                    action = "unlock_admin",
                                    success = true,
                                    payload = JSONObject()
                                        .put("action", "unlock_admin")
                                        .put("success", true)
                                        .put("target_action", actionName)
                                        .put("ts", System.currentTimeMillis()),
                                )
                                if (enableDemoMode) {
                                    // Dev-mode broadcast is only understood by the enterprise player's
                                    // own receiver; the X96 build doesn't expose an equivalent, so skip
                                    // rather than fire a broadcast at a component that doesn't exist there.
                                    if (!PlayerAppConstants.isX96()) {
                                        val i =
                                            Intent("com.mupa.player.enterprise.ACTION_SET_DEV_MODE")
                                                .setClassName(
                                                    "com.mupa.player.enterprise",
                                                    "com.mupa.player.enterprise.ui.DevModeReceiver",
                                                )
                                                .putExtra("enabled", true)
                                        runCatching { context.sendBroadcast(i) }
                                    }
                                }
                                onSuccess()
                            }

                            is AdminAuthResult.LockedOut -> {
                                audit.add(
                                    action = "unlock_admin",
                                    success = false,
                                    payload = JSONObject()
                                        .put("action", "unlock_admin")
                                        .put("success", false)
                                        .put("target_action", actionName)
                                        .put("reason", "locked_out")
                                        .put("seconds_remaining", result.secondsRemaining)
                                        .put("ts", System.currentTimeMillis()),
                                )
                                error = "Bloqueado por ${result.secondsRemaining}s"
                                vibrateError()
                                shake.animateTo(18f, tween(durationMillis = 60))
                                shake.animateTo(-18f, tween(durationMillis = 60))
                                shake.animateTo(12f, tween(durationMillis = 60))
                                shake.animateTo(-12f, tween(durationMillis = 60))
                                shake.animateTo(0f, tween(durationMillis = 60))
                            }

                            is AdminAuthResult.Failure -> {
                                audit.add(
                                    action = "unlock_admin",
                                    success = false,
                                    payload = JSONObject()
                                        .put("action", "unlock_admin")
                                        .put("success", false)
                                        .put("target_action", actionName)
                                        .put("reason", "invalid_password")
                                        .put("failed_attempts", result.failedAttempts)
                                        .put("remaining_before_lock", result.remainingBeforeLock)
                                        .put("ts", System.currentTimeMillis()),
                                )
                                error = "Senha incorreta (${result.failedAttempts}/3)"
                                vibrateError()
                                shake.animateTo(18f, tween(durationMillis = 60))
                                shake.animateTo(-18f, tween(durationMillis = 60))
                                shake.animateTo(12f, tween(durationMillis = 60))
                                shake.animateTo(-12f, tween(durationMillis = 60))
                                shake.animateTo(0f, tween(durationMillis = 60))
                            }
                        }
                    }
                },
            ) { Text("OK") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

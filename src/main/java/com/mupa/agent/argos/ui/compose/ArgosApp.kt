package com.mupa.agent.argos.ui.compose

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mupa.agent.argos.permissions.PermissionManager
import com.mupa.agent.argos.services.ArgosForegroundService
import com.mupa.agent.argos.ui.compose.screens.AdminCenterScreen
import com.mupa.agent.argos.ui.compose.screens.DeviceSettingsScreen
import com.mupa.agent.argos.ui.compose.screens.GroupLinkWizard
import com.mupa.agent.argos.ui.compose.screens.KioskAppsScreen
import com.mupa.agent.argos.ui.compose.screens.LauncherScreen
import com.mupa.agent.argos.ui.compose.screens.SetupWizardScreen
import com.mupa.agent.argos.geofence.GeofenceLockOverlay
import com.mupa.agent.argos.security.SecurityAccessManager
import com.mupa.agent.argos.security.SecurityAccessResult
import com.mupa.agent.argos.managers.DeviceIdentityManager
import com.mupa.agent.argos.managers.SettingsManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.mupa.agent.argos.R
import com.mupa.agent.argos.ui.SyncStateHolder
import com.mupa.agent.argos.ui.compose.LucideIcon
import kotlinx.coroutines.launch

object Routes {
    const val Launcher = "launcher"
    const val Setup = "setup"
    const val SetupFromSettings = "setup_from_settings"
    const val Admin = "admin"
    const val Recovery = "recovery"
    const val RecoveryGate = "recovery_gate"
    const val KioskApps = "kiosk_apps"
    const val DeviceSettings = "device_settings"
    /** Lightweight popup shown automatically on startup when the device has never been
     *  enrolled into a group/company — distinct from the full multi-step Setup wizard,
     *  which only triggers when Android-level critical permissions are missing. */
    const val GroupLink = "group_link"
}

@Composable
fun ArgosApp(
    initialRoute: String = Routes.Launcher,
    pendingRoute: String?,
    onRouteHandled: () -> Unit,
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val permissionManager = remember { PermissionManager(context) }

    var criticalReady by remember { mutableStateOf(permissionManager.criticalReady()) }
    val settings = remember { SettingsManager(context.applicationContext) }
    val isSyncing by SyncStateHolder.isSyncing.collectAsState()

    LaunchedEffect(Unit) {
        ContextCompat.startForegroundService(context, Intent(context, ArgosForegroundService::class.java))
        val ready = permissionManager.criticalReady()
        criticalReady = ready
        val allowRecovery = initialRoute == Routes.RecoveryGate || initialRoute == Routes.Recovery
        if (!ready && !allowRecovery) {
            navController.navigate(Routes.Setup) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                launchSingleTop = true
            }
        } else if (ready && !allowRecovery && settings.getBoundCompanyIdCached().isBlank()) {
            // Android-side setup is done (Device Owner, accessibility, etc.) but the
            // device was never enrolled into an ARGOS group — without this it never
            // shows up in the web console and can't receive commands. Surface the
            // group-code popup immediately instead of leaving it silently unmanaged.
            // (Device naming/apelido_interno is handled exclusively by
            // ArgosForegroundService.ensureRegistrationOverlay() — a system-level overlay,
            // not in-app navigation, since kiosk/lock-task devices never let this Activity
            // stay in the foreground long enough for a Compose screen to be useful.)
            navController.navigate(Routes.GroupLink) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    ArgosTheme {
        ArgosBackground {
            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = initialRoute,
                ) {
                    composable(Routes.Launcher) {
                        LauncherScreen(
                            onOpenSetup = { navController.navigate(Routes.Setup) },
                            onOpenAdmin = { navController.navigate(Routes.Admin) },
                            onOpenRecovery = { navController.navigate(Routes.Recovery) },
                        )
                    }
                    composable(Routes.Setup) {
                        SetupWizardScreen(
                            onBack = {
                                criticalReady = permissionManager.criticalReady()
                                if (criticalReady) {
                                    navController.navigate(Routes.Launcher) {
                                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else {
                                    navController.popBackStack()
                                }
                            },
                            onStateChanged = {
                                criticalReady = permissionManager.criticalReady()
                            },
                        )
                    }
                    composable(Routes.SetupFromSettings) {
                        SetupWizardScreen(
                            onBack = { navController.popBackStack() },
                            onStateChanged = {
                                criticalReady = permissionManager.criticalReady()
                            },
                            onComplete = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.Admin) {
                        AdminCenterScreen(
                            onBack = { navController.popBackStack() },
                            onOpenKioskApps = { navController.navigate(Routes.KioskApps) },
                            onOpenSetup = { navController.navigate(Routes.Setup) },
                        )
                    }
                    composable(Routes.RecoveryGate) {
                        RecoveryGateScreen(
                            onCancel = { navController.popBackStack() },
                            onSuccess = { navController.navigate(Routes.Recovery) { popUpTo(Routes.RecoveryGate) { inclusive = true } } },
                        )
                    }
                    composable(Routes.Recovery) {
                        AdminCenterScreen(
                            onBack = { navController.popBackStack() },
                            onOpenKioskApps = { navController.navigate(Routes.KioskApps) },
                            onOpenSetup = { navController.navigate(Routes.Setup) },
                            title = "Central de Recuperação Agent Argos",
                            unlockedSession = true,
                            recoveryMode = true,
                        )
                    }
                    composable(Routes.KioskApps) {
                        KioskAppsScreen(
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.DeviceSettings) {
                        DeviceSettingsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenSetupWizard = { navController.navigate(Routes.SetupFromSettings) },
                        )
                    }
                    composable(Routes.GroupLink) {
                        GroupLinkWizard(
                            onBack = {
                                navController.navigate(Routes.Launcher) {
                                    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            onDone = {
                                navController.navigate(Routes.Launcher) {
                                    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            launchLauncherOnSuccess = false,
                        )
                    }
                }

                // Geofence lock overlay — shown on top of everything when device is outside fence
                GeofenceLockOverlay()

                // Sync indicator — bottom-right corner, shown while heartbeat or config pull is running
                AnimatedVisibility(
                    visible = isSyncing,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                }

                val currentRoute = navBackStackEntry?.destination?.route
                val showSettingsButton =
                    currentRoute != Routes.Setup &&
                        currentRoute != Routes.SetupFromSettings &&
                        currentRoute != Routes.DeviceSettings &&
                        currentRoute != Routes.GroupLink
                if (showSettingsButton) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .clickable {
                                navigateSafe(navController, Routes.DeviceSettings)
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.46f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                        content = {
                            Box(modifier = Modifier.padding(PaddingValues(10.dp))) {
                                LucideIcon(
                                    name = "settings",
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    contentDescription = "Configurações",
                                )
                            }
                        }
                    )
                }
            }

            LaunchedEffect(pendingRoute) {
                val target = pendingRoute?.trim()?.ifBlank { null } ?: return@LaunchedEffect
                if (target == navController.currentDestination?.route) {
                    onRouteHandled()
                    return@LaunchedEffect
                }
                navigateSafe(navController, target)
                onRouteHandled()
            }
        }
    }
}

private fun navigateSafe(navController: NavHostController, route: String) {
    runCatching {
        navController.navigate(route) {
            launchSingleTop = true
        }
    }
}

@Composable
private fun RecoveryGateScreen(
    onCancel: () -> Unit,
    onSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val auth = remember { SecurityAccessManager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Área Administrativa Agent Argos") },
        text = {
            Column {
                Image(
                    painter = painterResource(id = R.drawable.logo_argos_hor),
                    contentDescription = "ARGOS",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("Digite sua senha")
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    modifier = Modifier.fillMaxWidth(),
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val serial = runCatching { DeviceIdentityManager(context).getPersistentId().trim() }.getOrDefault("")
                        val result = auth.authenticate(password = password, deviceSerial = serial, nowMs = System.currentTimeMillis())
                        when (result) {
                            is SecurityAccessResult.Success -> onSuccess()
                            is SecurityAccessResult.LockedOut -> error = "Bloqueado por ${result.secondsRemaining}s"
                            is SecurityAccessResult.Failure -> error = "Senha incorreta (${result.failedAttempts}/3)"
                        }
                    }
                },
            ) { Text("Desbloquear") }
        },
        dismissButton = { OutlinedButton(onClick = onCancel) { Text("Cancelar") } },
    )
}

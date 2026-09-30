@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.mupa.agent.argos.ui.compose.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.StatFs
import android.provider.Settings
import android.graphics.Shader
import android.graphics.RenderEffect as AndroidRenderEffect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.FirebaseOptions
import com.mupa.agent.argos.R
import com.mupa.agent.argos.BuildConfig
import com.mupa.agent.argos.commands.ArgosCommandScheduler
import com.mupa.agent.argos.commands.LocalCommandStore
import com.mupa.agent.argos.launcher.AllowedApp
import com.mupa.agent.argos.launcher.KioskPolicyManager
import com.mupa.agent.argos.launcher.LauncherAppRepository
import com.mupa.agent.argos.launcher.LauncherViewModel
import com.mupa.agent.argos.launcher.WhitelistManager
import com.mupa.agent.argos.launcher.WatchdogManager
import com.mupa.agent.argos.managers.ArgosCompanyInfo
import com.mupa.agent.argos.managers.DeviceIdentityManager
import com.mupa.agent.argos.managers.PlayerAppConstants
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.managers.SupabaseDirectoryClient
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.permissions.PermissionManager
import com.mupa.agent.argos.security.AdminAuthManager
import com.mupa.agent.argos.security.AdminAuthResult
import com.mupa.agent.argos.services.ArgosForegroundService
import com.mupa.agent.argos.ui.compose.ArgosTokens
import com.mupa.agent.argos.ui.compose.GlassCard
import com.mupa.agent.argos.ui.compose.LucideIcon
import com.mupa.agent.argos.ui.compose.LocalArgosDimens
import com.mupa.agent.argos.ui.compose.LocalArgosWindowClass
import com.mupa.agent.argos.ui.compose.ArgosWindowClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import java.io.File
import java.io.FileOutputStream

private val PLAYER_PACKAGE: String
    get() = PlayerAppConstants.resolvePackage()
private val BLOCKED_PACKAGES = setOf(
    "com.android.vending",
    "com.google.android.youtube",
    "com.netflix.mediaclient",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
)

@Composable
fun LauncherScreen(
    onOpenSetup: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenRecovery: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LauncherHomeScreen(
            onOpenSetup = onOpenSetup,
            onOpenAdmin = onOpenAdmin,
            onOpenRecovery = onOpenRecovery,
        )
        // Botão vermelho de manutenção quando o ARGOS está inacessível (offline).
        com.mupa.agent.argos.ui.compose.MaintenanceOverlay()
    }
}

@Composable
fun LauncherHomeScreen(
    onOpenSetup: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenRecovery: () -> Unit,
) {
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val permissionManager = remember { PermissionManager(context) }
    val whitelist = remember { WhitelistManager(context.applicationContext) }
    val repo = remember { LauncherAppRepository(context.applicationContext, whitelist) }
    val vm = remember { LauncherViewModel(repo, scope) }
    val kioskPolicy = remember { KioskPolicyManager(context.applicationContext) }
    val watchdog = remember { WatchdogManager(context.applicationContext) }
    val settings = remember { SettingsManager(context.applicationContext) }
    var mplayerDemoMode by remember { mutableStateOf(settings.getMplayerDemoModeCached()) }

    var deviceId by remember { mutableStateOf("-") }
    var deviceName by remember {
        mutableStateOf(
            settings.getDeviceApelidoInternoCached().trim().ifBlank { resolveDeviceName(context) }
        )
    }
    var criticalReady by remember { mutableStateOf(permissionManager.criticalReady()) }

    var wallpaperBlurPx by remember { mutableStateOf(settings.getLauncherWallpaperBlurPxCached()) }
    var wallpaperOverlay by remember { mutableStateOf(settings.getLauncherWallpaperOverlayCached()) }

    var apps by remember { mutableStateOf<List<LauncherApp>>(emptyList()) }
    var runningByPkg by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val allowedApps by vm.allowedApps.collectAsState()

    var onlineStatus by remember { mutableStateOf(resolveOnline(context)) }
    var bgPortraitUrl by remember { mutableStateOf(settings.getLauncherBgPortraitUrlCached()) }
    var bgLandscapeUrl by remember { mutableStateOf(settings.getLauncherBgLandscapeUrlCached()) }

    var sheetApp by remember { mutableStateOf<LauncherApp?>(null) }
    var showSheet by remember { mutableStateOf(false) }

    var messageDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    var pendingProtectedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingProtectedActionName by remember { mutableStateOf("") }

    val pickApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val copied = runCatching { copyApkToInternalFiles(context, uri) }.getOrNull()
                if (copied == null) {
                    messageDialog = "Instalação" to "Falha ao ler o APK"
                    return@launch
                }
                requestLockTaskStopFromContext(context)
                val apkUri = runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        copied,
                    )
                }.getOrNull()
                if (apkUri == null) {
                    messageDialog = "Instalação" to "Falha ao gerar URI"
                    return@launch
                }
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apkUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val err = runCatching {
                    context.startActivity(intent)
                    null
                }.getOrElse { it.message?.trim().orEmpty().ifBlank { it.javaClass.simpleName } }
                if (err != null) messageDialog = "Instalação" to err
            }
        },
    )

    val recoveryDetector = remember { HiddenRecoveryGestureDetector(tapsRequired = 5, windowMs = 3_000L) }
    val recoveryAuth = remember { com.mupa.agent.argos.security.SecurityAccessManager(context.applicationContext) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var recoveryPassword by remember { mutableStateOf("") }
    var recoveryError by remember { mutableStateOf<String?>(null) }

    val hiddenOfflineAdminDetector = remember {
        HiddenTapHoldGestureDetector(
            tapsRequired = 3,
            tapWindowMs = 1_500L,
            holdMs = 3_000L,
            armWindowMs = 6_000L,
        )
    }
    var showOfflineAdminPanel by remember { mutableStateOf(false) }

    val metrics = remember(cfg.smallestScreenWidthDp, cfg.orientation) {
        val smallest = cfg.smallestScreenWidthDp
        val compact = smallest in 1..411
        UiMetrics(
            outerPadding = if (compact) 10.dp else 14.dp,
            contentSpacing = if (compact) 8.dp else 10.dp,
            headerHeight = if (compact) 60.dp else 68.dp,
            footerHeight = if (compact) 56.dp else 64.dp,
        )
    }

    fun refreshApps(allowedPackages: Set<String>) {
        val pm = context.packageManager
        val allowed = allowedPackages.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val list = allowed
            .asSequence()
            .filter { !BLOCKED_PACKAGES.contains(it) }
            .mapNotNull { pkg ->
                val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                if (pkg != context.packageName) {
                    pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                }
                val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
                val icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull()
                LauncherApp(
                    label = label,
                    packageName = pkg,
                    icon = icon,
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .toList()

        apps = list
    }

    fun refreshRunning() {
        val map = LinkedHashMap<String, Boolean>()
        for (a in apps) {
            map[a.packageName] = watchdog.isRunning(a.packageName)
        }
        runningByPkg = map
    }

    fun applyKioskPackages(newAllowed: Set<String>) {
        val locked = settings.getMdmLockedCached() || settings.getKioskModeCached()
        if (!locked) return
        kioskPolicy.applyNow()
    }

    LaunchedEffect(Unit) {
        deviceId = runCatching { DeviceIdentityManager(context).getPersistentId().trim() }.getOrDefault("-").ifBlank { "-" }
        deviceName = settings.getDeviceApelidoInternoCached().trim().ifBlank { resolveDeviceName(context) }
        criticalReady = permissionManager.criticalReady()
        if (!criticalReady) onOpenSetup()
        val fallbackAllowed = settings.getAllowedPackagesCached().ifEmpty {
            setOf(PLAYER_PACKAGE, "com.anydesk.anydeskandroid", "com.android.settings")
        } + context.packageName
        refreshApps(fallbackAllowed)
        refreshRunning()
        vm.refresh()
    }

    LaunchedEffect(allowedApps) {
        if (allowedApps.isEmpty()) return@LaunchedEffect
        val allowed = allowedApps.map { it.packageName }.toSet() + context.packageName
        refreshApps(allowed)
        refreshRunning()
    }

    LaunchedEffect(Unit) {
        while (true) {
            onlineStatus = resolveOnline(context)
            bgPortraitUrl = settings.getLauncherBgPortraitUrlCached()
            bgLandscapeUrl = settings.getLauncherBgLandscapeUrlCached()
            wallpaperBlurPx = settings.getLauncherWallpaperBlurPxCached()
            wallpaperOverlay = settings.getLauncherWallpaperOverlayCached()
            refreshRunning()
            // Re-read the whitelist from SQLite so changes applied by ConfigurationEngine
            // (which runs in a separate instance of WhitelistManager) are reflected here
            // without needing a restart. Reads are fast — the table has a handful of rows.
            vm.refresh()
            delay(4_000L)
        }
    }

    val allowedByPkg = remember(allowedApps) { allowedApps.associateBy { it.packageName } }

    val visible = remember(apps, allowedByPkg, runningByPkg) {
        apps.asSequence()
            .map { app ->
                val p = allowedByPkg[app.packageName] ?: AllowedApp(packageName = app.packageName)
                val running = runningByPkg[app.packageName] ?: false
                UiApp(app = app, allowed = p, running = running)
            }
            .filter { it.allowed.hidden.not() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.app.label })
            .toList()
    }

    val isLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val bgUrl = (if (isLandscape) bgLandscapeUrl else bgPortraitUrl).trim()
    val fallbackBgRes = if (isLandscape) R.drawable.bg_argos_hor else R.drawable.bg_argos_vert
    val dimens = LocalArgosDimens.current

    Box(modifier = Modifier.fillMaxSize()) {
        LauncherWallpaper(
            modifier = Modifier.fillMaxSize(),
            bgSpec = bgUrl,
            fallbackResId = fallbackBgRes,
            blurPx = wallpaperBlurPx,
            overlaySpec = wallpaperOverlay,
        )

        Box(
            modifier = Modifier
                .size(80.dp)
                .align(Alignment.TopStart)
                .combinedClickable(
                    onClick = {
                        val shouldOpen = recoveryDetector.registerTap(System.currentTimeMillis())
                        if (shouldOpen) {
                            recoveryPassword = ""
                            recoveryError = null
                            showRecoveryDialog = true
                        }
                    },
                    onLongClick = {},
                ),
        )

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .align(Alignment.TopEnd)
                    .pointerInput(hiddenOfflineAdminDetector) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = down.uptimeMillis
                            if (hiddenOfflineAdminDetector.isArmed(start)) {
                                var triggered = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id }
                                            ?: event.changes.firstOrNull()
                                            ?: break
                                    if (!change.pressed) break
                                    val elapsed = change.uptimeMillis - start
                                    if (elapsed >= hiddenOfflineAdminDetector.holdMs) {
                                        triggered = true
                                        hiddenOfflineAdminDetector.disarm()
                                        if (pendingProtectedAction == null) {
                                            pendingProtectedActionName = "offline_admin_panel"
                                            pendingProtectedAction = { showOfflineAdminPanel = true }
                                        }
                                        break
                                    }
                                }
                                if (triggered) {
                                    waitForUpOrCancellation()
                                }
                            } else {
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    hiddenOfflineAdminDetector.registerTap(start)
                                }
                            }
                        }
                    },
            )

        Column(modifier = Modifier.fillMaxSize()) {
            LauncherHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimens.headerHeight),
                deviceName = deviceName,
                onAdminHold = {
                    pendingProtectedActionName = "open_admin"
                    pendingProtectedAction = { onOpenAdmin() }
                },
            )

            Box(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth()
                    .padding(start = dimens.padding, end = dimens.padding, top = 0.dp, bottom = dimens.padding),
            ) {
                LauncherAppGrid(
                    items = visible,
                    onOpenApp = { ui ->
                        val intent = context.packageManager.getLaunchIntentForPackage(ui.app.packageName)
                        if (intent == null) {
                            messageDialog = ui.app.label to "Não é possível abrir"
                        } else {
                            DeviceOwnerPolicyManager(context).tryUnsuspendApp(context.packageName, ui.app.packageName)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            val err = runCatching {
                                context.startActivity(intent)
                                null
                            }.getOrElse { t ->
                                t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
                            }
                            if (err != null) {
                                val locked = settings.getMdmLockedCached() || settings.getKioskModeCached()
                                if (locked) {
                                    runCatching {
                                        context.startActivity(
                                            Intent("com.mupa.agent.argos.action.LOCKTASK_STOP")
                                                .setClassName(context.packageName, "com.mupa.agent.argos.ui.LockTaskBridgeActivity")
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                        )
                                    }
                                    val err2 = runCatching {
                                        context.startActivity(intent)
                                        null
                                    }.getOrElse { t ->
                                        t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
                                    }
                                    if (err2 != null) messageDialog = ui.app.label to "Não foi possível abrir"
                                } else {
                                    messageDialog = ui.app.label to "Não foi possível abrir"
                                }
                            }
                        }
                    },
                    onAdminManageApp = { ui ->
                        pendingProtectedActionName = "app_management"
                        pendingProtectedAction = {
                            sheetApp = ui.app
                            showSheet = true
                        }
                    },
                )
            }

            LauncherFooter(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimens.footerHeight),
            )
        }

        if (onlineStatus.equals("Offline", ignoreCase = true)) {
            OfflineIndicator(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = dimens.footerHeight + dimens.margin),
            )
        }

        PresenceSyncFab(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = dimens.footerHeight + 10.dp),
        )
    }

    val selected = sheetApp
    if (showSheet && selected != null) {
        val policy = allowedByPkg[selected.packageName] ?: AllowedApp(packageName = selected.packageName)
        val running = runningByPkg[selected.packageName] ?: false
        AppManagementBottomSheet(
            app = selected,
            policy = policy,
            running = running,
            mplayerDemoMode = mplayerDemoMode,
            onDismiss = { showSheet = false },
            onOpen = {
                DeviceOwnerPolicyManager(context).tryUnsuspendApp(context.packageName, selected.packageName)
                val intent = context.packageManager.getLaunchIntentForPackage(selected.packageName)
                if (intent == null) {
                    messageDialog = selected.label to "Não é possível abrir"
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    val err = runCatching {
                        context.startActivity(intent)
                        null
                    }.getOrElse { t ->
                        t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
                    }
                    if (err != null) {
                        val locked = settings.getMdmLockedCached() || settings.getKioskModeCached()
                        if (locked) {
                            runCatching {
                                context.startActivity(
                                    Intent("com.mupa.agent.argos.action.LOCKTASK_STOP")
                                        .setClassName(context.packageName, "com.mupa.agent.argos.ui.LockTaskBridgeActivity")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                )
                            }
                            runCatching { context.startActivity(intent) }
                        } else {
                            messageDialog = selected.label to err
                        }
                    }
                }
            },
            onToggleFavorite = {
                pendingProtectedActionName = "toggle_favorite"
                pendingProtectedAction = {
                    scope.launch {
                        whitelist.update(selected.packageName) { it.copy(favorite = !it.favorite) }
                    }
                }
            },
            onToggleAutostart = {
                pendingProtectedActionName = "toggle_autostart"
                pendingProtectedAction = {
                    scope.launch {
                        whitelist.update(selected.packageName) { it.copy(autostart = !it.autostart) }
                    }
                }
            },
            onTogglePersistence = {
                pendingProtectedActionName = "toggle_persistence"
                pendingProtectedAction = {
                    scope.launch {
                        whitelist.update(selected.packageName) { it.copy(persistence = !it.persistence) }
                    }
                }
            },
            onTogglePinned = {
                pendingProtectedActionName = "toggle_pinned"
                pendingProtectedAction = {
                    scope.launch {
                        whitelist.update(selected.packageName) { it.copy(pinned = !it.pinned) }
                    }
                }
            },
            onToggleHidden = {
                pendingProtectedActionName = "toggle_hidden"
                pendingProtectedAction = {
                    scope.launch {
                        whitelist.update(selected.packageName) { it.copy(hidden = !it.hidden) }
                    }
                }
            },
            onToggleKiosk = {
                pendingProtectedActionName = "toggle_kiosk"
                pendingProtectedAction = {
                    scope.launch {
                        val current = allowedApps.map { it.packageName }.toSet()
                        val newAllowed = if (current.contains(selected.packageName)) current - selected.packageName else current + selected.packageName
                        whitelist.setAllowedPackages(newAllowed)
                        applyKioskPackages(newAllowed)
                    }
                }
            },
            onForceStop = {
                pendingProtectedActionName = "force_stop"
                pendingProtectedAction = {
                    val err = DeviceOwnerPolicyManager(context).tryStopApp(context.packageName, selected.packageName)
                    messageDialog = "Forçar parada" to (err ?: "OK")
                    refreshRunning()
                }
            },
            onOpenInfo = {
                val uri = android.net.Uri.parse("package:${selected.packageName}")
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
            onToggleMplayerDemoMode = {
                pendingProtectedActionName = "mplayer_demo_mode"
                pendingProtectedAction = {
                    val next = !mplayerDemoMode
                    // Dev-mode broadcast is only understood by the enterprise player's own
                    // receiver; the X96 build doesn't expose an equivalent.
                    if (!PlayerAppConstants.isX96()) {
                        val i =
                            Intent("com.mupa.player.enterprise.ACTION_SET_DEV_MODE")
                                .setClassName(
                                    "com.mupa.player.enterprise",
                                    "com.mupa.player.enterprise.ui.DevModeReceiver",
                                )
                                .putExtra("enabled", next)
                        runCatching { context.sendBroadcast(i) }
                    }
                    scope.launch {
                        settings.setMplayerDemoMode(next)
                        mplayerDemoMode = next
                    }
                }
            },
            onUninstall = {
                pendingProtectedActionName = "uninstall"
                pendingProtectedAction = {
                    val uri = android.net.Uri.parse("package:${selected.packageName}")
                    val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri)
                        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }
            },
        )
    }

    val protectedAction = pendingProtectedAction
    if (protectedAction != null) {
        AdminAuthDialog(
            actionName = pendingProtectedActionName,
            onDismiss = { pendingProtectedAction = null },
            onSuccess = {
                pendingProtectedAction = null
                protectedAction()
            },
        )
    }

    val msg = messageDialog
    if (msg != null) {
        AlertDialog(
            onDismissRequest = { messageDialog = null },
            title = { Text(msg.first) },
            text = { Text(msg.second) },
            confirmButton = { Button(onClick = { messageDialog = null }) { Text("OK") } },
        )
    }

    if (showRecoveryDialog) {
        AlertDialog(
            onDismissRequest = { showRecoveryDialog = false },
            title = { Text("Área Administrativa Agent Argos") },
            text = {
                Column {
                    Text("Digite sua senha")
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = recoveryPassword,
                        onValueChange = { recoveryPassword = it },
                        singleLine = true,
                        isError = recoveryError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            val e = recoveryError
                            if (e != null) Text(e, color = MaterialTheme.colorScheme.error)
                        },
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val serial = deviceId.trim().ifBlank { DeviceIdentityManager(context).getPersistentId().trim() }
                            val result = recoveryAuth.authenticate(
                                password = recoveryPassword,
                                deviceSerial = serial,
                                nowMs = System.currentTimeMillis(),
                            )
                            when (result) {
                                is com.mupa.agent.argos.security.SecurityAccessResult.Success -> {
                                    showRecoveryDialog = false
                                    onOpenRecovery()
                                }
                                is com.mupa.agent.argos.security.SecurityAccessResult.LockedOut -> {
                                    recoveryError = "Bloqueado por ${result.secondsRemaining}s"
                                }
                                is com.mupa.agent.argos.security.SecurityAccessResult.Failure -> {
                                    recoveryError = "Senha incorreta (${result.failedAttempts}/3)"
                                }
                            }
                        }
                    },
                ) { Text("Desbloquear") }
            },
            dismissButton = { OutlinedButton(onClick = { showRecoveryDialog = false }) { Text("Cancelar") } },
        )
    }

    if (showOfflineAdminPanel) {
        OfflineAdminPanelDialog(
            onDismiss = { showOfflineAdminPanel = false },
            onOpenAdmin = onOpenAdmin,
            onOpenSetup = onOpenSetup,
        )
    }
}

private class HiddenRecoveryGestureDetector(
    private val tapsRequired: Int,
    private val windowMs: Long,
) {
    private val taps = ArrayDeque<Long>()

    fun registerTap(nowMs: Long): Boolean {
        taps.addLast(nowMs)
        while (taps.isNotEmpty() && nowMs - taps.first() > windowMs) {
            taps.removeFirst()
        }
        if (taps.size >= tapsRequired) {
            taps.clear()
            return true
        }
        return false
    }
}

private class HiddenTapHoldGestureDetector(
    private val tapsRequired: Int,
    private val tapWindowMs: Long,
    val holdMs: Long,
    private val armWindowMs: Long,
) {
    private val taps = ArrayDeque<Long>()
    private var armedUntilMs: Long = 0L

    fun registerTap(nowMs: Long) {
        taps.addLast(nowMs)
        while (taps.isNotEmpty() && nowMs - taps.first() > tapWindowMs) {
            taps.removeFirst()
        }
        if (taps.size >= tapsRequired) {
            taps.clear()
            armedUntilMs = nowMs + armWindowMs
        }
    }

    fun isArmed(nowMs: Long): Boolean = nowMs in 1..armedUntilMs

    fun disarm() {
        armedUntilMs = 0L
        taps.clear()
    }
}

@Composable
private fun OfflineAdminPanelDialog(
    onDismiss: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context.applicationContext) }
    val dpm = remember { DeviceOwnerPolicyManager(context.applicationContext) }
    val identity = remember { DeviceIdentityManager(context.applicationContext) }

    var message by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun formatTime(ms: Long): String {
        if (ms <= 0L) return "—"
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return runCatching { df.format(Date(ms)) }.getOrDefault(ms.toString())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin (Offline)") },
        text = {
            LazyColumn(
                contentPadding = PaddingValues(0.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("Ações", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onDismiss()
                                onOpenAdmin()
                            },
                        ) { Text("Ferramentas") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onDismiss()
                                onOpenSetup()
                            },
                        ) { Text("Permissões") }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val err = dpm.rebootDeviceError(context.packageName)
                                message = "Reiniciar" to (err ?: "OK")
                            },
                        ) { Text("Reiniciar") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                requestLockTaskStopFromContext(context)
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        ) { Text("Wi‑Fi") }
                    }
                }

                item { HorizontalDivider() }

                item {
                    Text("Identificação", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    val deviceId = identity.getCachedId().ifBlank { "—" }
                    val source = identity.getCachedSource().ifBlank { "—" }
                    val stable = identity.getCachedStable()
                    val resolvedAt = identity.getCachedResolvedAtMs()
                    SettingLine("Device ID", deviceId)
                    SettingLine("Fonte", source)
                    SettingLine("Estável", if (stable) "Sim" else "Não")
                    SettingLine("Resolvido em", formatTime(resolvedAt))
                }

                item { HorizontalDivider() }

                item {
                    Text("Modo", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingLine("Kiosk", if (settings.getKioskModeCached()) "Ativo" else "Inativo")
                    SettingLine("Device Locked", if (settings.getMdmLockedCached()) "Ativo" else "Inativo")
                    SettingLine("Maintenance", if (settings.getMaintenanceModeEnabledCached()) "Ativo" else "Inativo")
                    val tu = settings.getTemporaryUnlockUntilMsCached()
                    SettingLine("Liberação até", if (tu > 0L) formatTime(tu) else "—")
                    SettingLine("Launcher blur", settings.getLauncherWallpaperBlurPxCached().toString())
                    SettingLine("Launcher overlay", settings.getLauncherWallpaperOverlayCached().ifBlank { "—" })
                }

                item { HorizontalDivider() }

                item {
                    Text("Energia", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingLine("Keep awake", if (settings.getKeepAwakeEnabledCached()) "Ativo" else "Inativo")
                    SettingLine("Agenda", if (settings.getAwakeScheduleEnabledCached()) "Ativa" else "Inativa")
                    SettingLine("Início (min)", settings.getAwakeScheduleStartMinCached().toString())
                    SettingLine("Fim (min)", settings.getAwakeScheduleEndMinCached().toString())
                    SettingLine("Rest until", formatTime(settings.getRestModeUntilMsCached()))
                    SettingLine("Rest timeout", settings.getRestScreenTimeoutMsCached().toString())
                }

                item { HorizontalDivider() }

                item {
                    Text("Apps / Distribuição", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingLine("Distribuição", settings.getAppDistributionModeCached().ifBlank { "—" })
                    SettingLine("AnyDesk URL", settings.getAnyDeskApkUrlCached().ifBlank { "—" })
                    SettingLine("MPlayer URL", settings.getMplayerApkUrlCached().ifBlank { "—" })
                    SettingLine("Plugins AnyDesk", settings.getAnyDeskPluginSpecsCached().size.toString())
                    SettingLine("Whitelist (pacotes)", settings.getAllowedPackagesCached().size.toString())
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    requestLockTaskStopFromContext(context)
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            ) { Text("Configurações") }
        },
    )

    val msg = message
    if (msg != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(msg.first) },
            text = { Text(msg.second) },
            confirmButton = { Button(onClick = { message = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun SettingLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

private data class LauncherApp(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val isSystem: Boolean,
)

private data class UiApp(
    val app: LauncherApp,
    val allowed: AllowedApp,
    val running: Boolean,
)

private enum class SortMode(val comparator: Comparator<UiApp>) {
    FavoritesFirst(
        compareByDescending<UiApp> { it.allowed.pinned }
            .thenByDescending<UiApp> { it.allowed.favorite }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.app.label }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.app.packageName },
    ),
    NameAsc(
        compareBy<UiApp, String>(String.CASE_INSENSITIVE_ORDER) { it.app.label }
            .thenByDescending<UiApp> { it.allowed.favorite }
            .thenByDescending<UiApp> { it.allowed.pinned },
    ),
    PackageAsc(compareBy<UiApp, String>(String.CASE_INSENSITIVE_ORDER) { it.app.packageName }),
}

private data class UiMetrics(
    val outerPadding: androidx.compose.ui.unit.Dp,
    val contentSpacing: androidx.compose.ui.unit.Dp,
    val headerHeight: androidx.compose.ui.unit.Dp,
    val footerHeight: androidx.compose.ui.unit.Dp,
)

@Composable
private fun EnterpriseTopBar(
    modifier: Modifier,
    envName: String,
    online: String,
    wifi: String,
    time: String,
    onOpenSetup: () -> Unit,
    onOpenAdmin: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSort: () -> Unit,
    onInstallApk: () -> Unit,
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        elevation = 6.dp,
        containerAlpha = 0.45f,
        borderAlpha = 0.13f,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(name = "shield-check", modifier = Modifier.height(20.dp), tint = MaterialTheme.colorScheme.primary)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(envName.ifBlank { "ARGOS" }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("Device Management Agent", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }

            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(text = online)
                    StatusPill(text = wifi)
                    StatusPill(text = time)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onToggleSearch) {
                        LucideIcon(name = "search", modifier = Modifier.height(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = onOpenSort) {
                        LucideIcon(name = "arrow-up-down", modifier = Modifier.height(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = onInstallApk) {
                        LucideIcon(name = "download", modifier = Modifier.height(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = onOpenSetup) {
                        LucideIcon(name = "shield-check", modifier = Modifier.height(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = onOpenAdmin) {
                        LucideIcon(name = "lock", modifier = Modifier.height(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArgosHeroSection(
    modifier: Modifier,
    companyInfo: ArgosCompanyInfo?,
    companyLoading: Boolean,
    companyError: String?,
    deviceSerial: String,
    online: String,
    wifi: String,
    battery: String,
    lastSync: String,
    agentVersion: String,
    pending: String,
    onRefreshCompany: () -> Unit,
) {
    val companyText =
        when {
            companyLoading -> "Carregando…"
            companyInfo != null -> companyInfo.companyName
            companyError != null -> companyError
            else -> "—"
        }
    GlassCard(
        modifier = modifier,
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(16.dp),
        elevation = 8.dp,
        containerAlpha = 0.40f,
        borderAlpha = 0.12f,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo_argos_hor),
                contentDescription = "ARGOS",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(56.dp)
                    .fillMaxWidth(0.78f),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ARGOS", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Device Management Agent",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ArgosInfoTile(
                        modifier = Modifier.weight(1f),
                        icon = "activity",
                        label = "Status",
                        value = online,
                        indicatorColor = onlineIndicatorColor(online),
                    )
                    ArgosInfoTile(
                        modifier = Modifier.weight(1f),
                        icon = "wifi",
                        label = "Rede",
                        value = wifi,
                    )
                    ArgosInfoTile(
                        modifier = Modifier.weight(1f),
                        icon = "battery",
                        label = "Bateria",
                        value = battery,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ArgosInfoTile(
                        modifier = Modifier.weight(1f),
                        icon = "building-2",
                        label = "Empresa",
                        value = companyText,
                        onClick = onRefreshCompany,
                    )
                    ArgosInfoTile(
                        modifier = Modifier.weight(1f),
                        icon = "refresh-cw",
                        label = "Última sync",
                        value = lastSync,
                    )
                    ArgosInfoTile(
                        modifier = Modifier.weight(1f),
                        icon = "badge-check",
                        label = "Agente",
                        value = agentVersion,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ArgosInfoTile(
                        modifier = Modifier.weight(1f),
                        icon = "hash",
                        label = "Serial",
                        value = deviceSerial.trim().ifBlank { "—" },
                    )
                    ArgosInfoTile(
                        modifier = Modifier.weight(1f),
                        icon = "list-checks",
                        label = "Pendentes",
                        value = pending,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ArgosInfoTile(
    modifier: Modifier = Modifier,
    icon: String,
    label: String,
    value: String,
    indicatorColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickable = onClick != null
    GlassCard(
        modifier = modifier.then(
            if (clickable) {
                Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = {})
            } else {
                Modifier
            },
        ),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        elevation = 0.dp,
        containerAlpha = 0.35f,
        borderAlpha = 0.12f,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(name = icon, modifier = Modifier.height(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(value.ifBlank { "—" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
            val dot = indicatorColor
            if (dot != null) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(dot),
                )
            }
        }
    }
}

private fun onlineIndicatorColor(online: String): Color {
    val t = online.trim().lowercase(Locale.ROOT)
    return when {
        t.contains("online") -> ArgosTokens.Success
        t.contains("inst") -> ArgosTokens.Warning
        t.contains("offline") -> ArgosTokens.Offline
        else -> ArgosTokens.Offline
    }
}

@Composable
private fun StatusPill(text: String) {
    val t = text
        .ifBlank { "—" }
    GlassCard(
        cornerRadius = 999.dp,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        elevation = 0.dp,
        containerAlpha = 0.35f,
        borderAlpha = 0.12f,
    ) {
        Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun EnterpriseFooter(
    modifier: Modifier,
    left: String,
    right: String,
    onSyncNow: () -> Unit,
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        elevation = 4.dp,
        containerAlpha = 0.40f,
        borderAlpha = 0.12f,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(left, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(right, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            IconButton(onClick = onSyncNow) {
                LucideIcon(name = "refresh-cw", modifier = Modifier.height(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun EnterpriseAppList(
    modifier: Modifier,
    items: List<UiApp>,
    onClick: (UiApp) -> Unit,
    onLongPress: (UiApp) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.app.packageName }) { ui ->
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onClick(ui) },
                        onLongClick = { onLongPress(ui) },
                    ),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                elevation = 0.dp,
                containerAlpha = 0.30f,
                borderAlpha = 0.10f,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val icon = ui.app.icon
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (icon != null) {
                            Image(
                                bitmap = icon.toBitmap(width = 192, height = 192).asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                            )
                        } else {
                            LucideIcon(name = "app-window", modifier = Modifier.height(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(ui.app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(
                            ui.app.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (ui.allowed.pinned) {
                            LucideIcon(name = "pin", modifier = Modifier.height(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (ui.allowed.favorite) {
                            LucideIcon(name = "star", modifier = Modifier.height(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (ui.running) ArgosTokens.Success else ArgosTokens.Offline),
                        )
                    }
                }
            }
        }
    }
}

private class UiAppRowAdapter(
    private val onClick: (UiApp) -> Unit,
    private val onLongPress: (UiApp) -> Unit,
) : ListAdapter<UiApp, UiAppRowVH>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UiAppRowVH {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val m = dp(ctx, 6)
                setMargins(0, m, 0, m)
            }
            minimumHeight = dp(ctx, 64)
            val ph = dp(ctx, 12)
            val pv = dp(ctx, 10)
            setPadding(ph, pv, ph, pv)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 18).toFloat()
                setColor(0x66060A12)
                setStroke(dp(ctx, 1), 0x2200D0FF)
            }
        }

        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32)).apply { rightMargin = dp(ctx, 12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            imageTintList = null
        }

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
        }

        val sub = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTextColor(0xFFB6C2DA.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            isSingleLine = true
        }

        texts.addView(title)
        texts.addView(sub)

        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val dot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10)).apply { rightMargin = dp(ctx, 8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFEF4444.toInt())
            }
        }

        val status = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTextColor(0xFFB6C2DA.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            isSingleLine = true
        }

        right.addView(dot)
        right.addView(status)

        row.addView(icon)
        row.addView(texts)
        row.addView(right)

        return UiAppRowVH(row, icon, title, sub, dot, status)
    }

    override fun onBindViewHolder(holder: UiAppRowVH, position: Int) {
        val item = getItem(position)
        holder.title.text = item.app.label
        holder.sub.text = item.app.packageName
        holder.icon.setImageDrawable(item.app.icon)
        val running = item.running
        (holder.dot.background as? GradientDrawable)?.setColor(if (running) 0xFF39D98A.toInt() else 0xFFEF4444.toInt())
        holder.status.text = if (running) "Online" else "Offline"
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongPress(item)
            true
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<UiApp>() {
            override fun areItemsTheSame(oldItem: UiApp, newItem: UiApp): Boolean {
                return oldItem.app.packageName == newItem.app.packageName
            }

            override fun areContentsTheSame(oldItem: UiApp, newItem: UiApp): Boolean {
                return oldItem == newItem
            }
        }

        private fun dp(context: Context, dp: Int): Int {
            return (dp * context.resources.displayMetrics.density).roundToInt()
        }
    }
}

private class UiAppRowVH(
    itemView: View,
    val icon: ImageView,
    val title: TextView,
    val sub: TextView,
    val dot: View,
    val status: TextView,
) : RecyclerView.ViewHolder(itemView)

private fun formatEnvName(url: String): String {
    val raw = url.trim()
    if (raw.isBlank()) return "Operação"
    val noProto = raw.removePrefix("https://").removePrefix("http://")
    return noProto.substringBefore('/').ifBlank { "Operação" }
}

@Composable
private fun ProcessDot(running: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(99.dp)),
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = if (running) Color(0xFF39D98A) else Color(0xFFEF4444))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppManagementBottomSheet(
    app: LauncherApp,
    policy: AllowedApp,
    running: Boolean,
    mplayerDemoMode: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleAutostart: () -> Unit,
    onTogglePersistence: () -> Unit,
    onToggleKiosk: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleHidden: () -> Unit,
    onOpen: () -> Unit,
    onForceStop: () -> Unit,
    onOpenInfo: () -> Unit,
    onToggleMplayerDemoMode: () -> Unit,
    onUninstall: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Gerenciar aplicativo", style = MaterialTheme.typography.titleLarge)
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val icon = app.icon
                    if (icon != null) {
                        androidx.compose.foundation.Image(
                            bitmap = icon.toBitmap(width = 192, height = 192).asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)),
                        )
                    } else {
                        Spacer(modifier = Modifier.size(44.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                        Text(app.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ProcessDot(running = running)
                }
            }

            ActionRow(
                icon = "star",
                title = if (policy.favorite) "Remover favorito" else "Definir favorito",
                onClick = onToggleFavorite,
            )
            ActionRow(
                icon = "rocket",
                title = if (policy.autostart) "Desativar Auto Start" else "Ativar Auto Start",
                onClick = onToggleAutostart,
            )
            ActionRow(
                icon = "refresh-cw",
                title = if (policy.persistence) "Desativar Persistência" else "Ativar Persistência",
                onClick = onTogglePersistence,
            )
            ActionRow(
                icon = "lock",
                title = "Adicionar/Remover do Kiosk",
                onClick = onToggleKiosk,
            )
            ActionRow(
                icon = "pin",
                title = if (policy.pinned) "Desafixar do topo" else "Fixar no topo",
                onClick = onTogglePinned,
            )
            ActionRow(
                icon = "eye-off",
                title = if (policy.hidden) "Mostrar" else "Ocultar",
                onClick = onToggleHidden,
            )
            if (app.packageName == PLAYER_PACKAGE && !PlayerAppConstants.isX96()) {
                ActionRow(
                    icon = "flask-conical",
                    title = if (mplayerDemoMode) "Modo DEMO (consulta remota): Ativo" else "Modo DEMO (consulta remota): Desativado",
                    onClick = onToggleMplayerDemoMode,
                )
            }
            ActionRow(icon = "play", title = "Abrir aplicativo", onClick = onOpen)
            ActionRow(icon = "ban", title = "Forçar parada", onClick = onForceStop)
            ActionRow(icon = "info", title = "Informações", onClick = onOpenInfo)
            ActionRow(icon = "trash-2", title = "Desinstalar", onClick = onUninstall)
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ActionRow(
    icon: String,
    title: String,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LucideIcon(name = icon, modifier = Modifier.height(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun FooterBar(
    ram: String,
    storage: String,
    lastSync: String,
    pendingCommands: String,
    appsCount: Int,
    whitelistCount: Int,
    onSyncNow: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("RAM: $ram", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Armazenamento: $storage", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Última sincronização: $lastSync", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Comandos pendentes: $pendingCommands", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Whitelist: $whitelistCount", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Apps: $appsCount", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onSyncNow) { Text("Sincronizar") }
        }
    }
}

private fun resolveDeviceName(context: Context): String {
    val fromGlobal = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }
        .getOrNull()
        ?.trim()
        .orEmpty()
    if (fromGlobal.isNotBlank()) return fromGlobal
    return Build.MODEL?.trim().orEmpty().ifBlank { "Android" }
}

private sealed interface WallpaperSpec {
    data class Solid(val color: Color) : WallpaperSpec
    data class Gradient(val colors: List<Color>) : WallpaperSpec
    data class Image(val data: Any) : WallpaperSpec
}

@Composable
private fun LauncherWallpaper(
    modifier: Modifier,
    bgSpec: String,
    fallbackResId: Int,
    blurPx: Int,
    overlaySpec: String,
) {
    val context = LocalContext.current
    val spec = remember(bgSpec, fallbackResId) { parseWallpaperSpec(bgSpec, fallbackResId) }
    val overlay = remember(overlaySpec) { parseOverlayColor(overlaySpec) }
    val radiusPx = blurPx.coerceIn(0, 60).let { if (it in 1..19) 20 else if (it in 41..60) 40 else it }
    val blurModifier =
        if (Build.VERSION.SDK_INT >= 31 && radiusPx > 0 && spec is WallpaperSpec.Image) {
            Modifier.graphicsLayer {
                renderEffect =
                    AndroidRenderEffect.createBlurEffect(radiusPx.toFloat(), radiusPx.toFloat(), Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
            }
        } else {
            Modifier
        }
    when (spec) {
        is WallpaperSpec.Solid -> Box(modifier = modifier.background(spec.color)) { Box(Modifier.fillMaxSize().background(overlay)) }
        is WallpaperSpec.Gradient -> Box(modifier = modifier.background(Brush.linearGradient(colors = spec.colors))) { Box(Modifier.fillMaxSize().background(overlay)) }
        is WallpaperSpec.Image -> Box(modifier = modifier) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(spec.data)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(blurModifier),
            )
            Box(Modifier.fillMaxSize().background(overlay))
        }
    }
}

@Composable
private fun LauncherWallpaperBlurOverlay(
    modifier: Modifier,
    bgSpec: String,
    fallbackResId: Int,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val spec = remember(bgSpec, fallbackResId) { parseWallpaperSpec(bgSpec, fallbackResId) }
    val blur = remember(density) { with(density) { 18.dp.toPx() } }

    when (spec) {
        is WallpaperSpec.Solid -> Box(
            modifier = modifier.background(spec.color.copy(alpha = 0.08f)),
        )

        is WallpaperSpec.Gradient -> Box(
            modifier = modifier.background(Brush.linearGradient(colors = spec.colors)).background(ArgosTokens.Dark.copy(alpha = 0.10f)),
        )

        is WallpaperSpec.Image -> {
            val blurModifier =
                if (Build.VERSION.SDK_INT >= 31) {
                    Modifier.graphicsLayer {
                        renderEffect =
                            AndroidRenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                                .asComposeRenderEffect()
                    }
                } else {
                    Modifier
                }
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(spec.data)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .then(blurModifier)
                    .background(ArgosTokens.Dark.copy(alpha = 0.10f)),
            )
        }
    }
}

private fun parseWallpaperSpec(bgSpec: String, fallbackResId: Int): WallpaperSpec {
    val s = bgSpec.trim()
    if (s.isBlank()) return WallpaperSpec.Image(fallbackResId)

    if (s.startsWith("color:", ignoreCase = true)) {
        val c = s.substringAfter("color:", "").trim()
        val parsed = parseHexColorOrNull(c)
        if (parsed != null) return WallpaperSpec.Solid(parsed)
    }

    if (s.startsWith("gradient:", ignoreCase = true)) {
        val parts = s.substringAfter("gradient:", "")
            .split(',', '|', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val colors = parts.mapNotNull { parseHexColorOrNull(it) }
        if (colors.size >= 2) return WallpaperSpec.Gradient(colors)
    }

    return WallpaperSpec.Image(s)
}

private fun parseHexColorOrNull(raw: String): Color? {
    val s = raw.trim()
    if (s.isBlank()) return null
    val hex = if (s.startsWith("#")) s else "#$s"
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}

private fun parseOverlayColor(raw: String): Color {
    val s = raw.trim()
    if (s.isBlank()) return Color.Black.copy(alpha = 0.25f)
    val lower = s.lowercase(Locale.ROOT)
    if (lower == "black25") return Color.Black.copy(alpha = 0.25f)
    if (lower == "navy35") return Color(0xFF041B4D).copy(alpha = 0.35f)
    if (lower.startsWith("rgba(") && lower.endsWith(")")) {
        val inner = lower.removePrefix("rgba(").removeSuffix(")")
        val parts = inner.split(',').map { it.trim() }
        if (parts.size == 4) {
            val r = parts[0].toIntOrNull()?.coerceIn(0, 255)
            val g = parts[1].toIntOrNull()?.coerceIn(0, 255)
            val b = parts[2].toIntOrNull()?.coerceIn(0, 255)
            val a = parts[3].toFloatOrNull()?.coerceIn(0f, 1f)
            if (r != null && g != null && b != null && a != null) {
                return Color(r / 255f, g / 255f, b / 255f, a)
            }
        }
    }
    val hex = parseHexColorOrNull(s)
    if (hex != null) return hex
    return Color.Black.copy(alpha = 0.25f)
}

@Composable
private fun LauncherHeader(
    modifier: Modifier,
    deviceName: String,
    onAdminHold: () -> Unit,
) {
    val windowClass = LocalArgosWindowClass.current
    val dimens = LocalArgosDimens.current
    val compact = windowClass == ArgosWindowClass.Compact
    Box(
        modifier = modifier
            .background(ArgosTokens.Navy.copy(alpha = 0.22f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = dimens.padding, vertical = if (compact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
        ) {
            val logoH = if (compact) 22.dp else 28.dp
            val logoW = if (compact) 78.dp else 92.dp
            Image(
                painter = painterResource(id = R.drawable.logo_argos_hor),
                contentDescription = "ARGOS",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(logoH)
                    .width(logoW)
                    .tenSecondHold(onAdminHold),
            )
            Text(
                text = deviceName,
                style = MaterialTheme.typography.titleSmall,
                color = androidx.compose.ui.graphics.Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LauncherFooter(
    modifier: Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identity = remember { DeviceIdentityManager(context.applicationContext) }
    var sending by remember { mutableStateOf(false) }

    var serial by remember { mutableStateOf(identity.getCachedId().trim().ifBlank { "-" }) }
    val source = identity.getCachedSource().trim().ifBlank { "-" }
    val stable = identity.getCachedStable()
    val ver = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    LaunchedEffect(Unit) {
        val raw = withContext(Dispatchers.IO) {
            runCatching { identity.getDeviceFingerprint().serial?.trim().orEmpty() }.getOrElse { "" }
        }
        if (raw.isNotBlank()) serial = raw
    }

    Box(
        modifier = modifier
            .background(ArgosTokens.Navy.copy(alpha = 0.22f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "SN: $serial",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "v$ver • $source • ${if (stable) "stable" else "volatile"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = {
                    if (sending) return@IconButton
                    sending = true
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val now = System.currentTimeMillis()
                            val deviceId = DeviceIdentityManager.getPersistentDeviceId(context.applicationContext).trim()
                            val fp = DeviceIdentityManager.getDeviceFingerprint(context.applicationContext)

                            val dbUrl = BuildConfig.FIREBASE_DATABASE_URL.trim()
                            val apiKey = BuildConfig.FIREBASE_API_KEY.trim()
                            val appId = BuildConfig.FIREBASE_APP_ID.trim()
                            val projectId = BuildConfig.FIREBASE_PROJECT_ID.trim()
                            val existing = FirebaseApp.getApps(context.applicationContext).firstOrNull()
                            val app = existing ?: FirebaseApp.initializeApp(
                                context.applicationContext,
                                FirebaseOptions.Builder()
                                    .setApiKey(apiKey)
                                    .setApplicationId(appId)
                                    .setProjectId(projectId)
                                    .setDatabaseUrl(dbUrl)
                                    .build(),
                            )

                            val db = if (dbUrl.isNotBlank() && app != null) {
                                FirebaseDatabase.getInstance(app, dbUrl)
                            } else {
                                FirebaseDatabase.getInstance()
                            }

                            val rootRef = db.getReference("m_argos/devices").child(deviceId)
                            val fingerprint = hashMapOf<String, Any>(
                                "primary_id" to fp.primaryId,
                                "manufacturer" to fp.manufacturer,
                                "model" to fp.model,
                                "android_version" to fp.androidVersion,
                            ).also { m ->
                                fp.imei?.trim()?.takeIf { it.isNotBlank() }?.let { m["imei"] = it }
                                fp.serial?.trim()?.takeIf { it.isNotBlank() }?.let { m["serial"] = it }
                                fp.manufacturerSerial?.trim()?.takeIf { it.isNotBlank() }?.let { m["manufacturer_serial"] = it }
                                fp.fallbackId?.trim()?.takeIf { it.isNotBlank() }?.let { m["fallback_id"] = it }
                            }

                            val entry = hashMapOf<String, Any>(
                                "timestamp" to now,
                                "level" to "INFO",
                                "category" to "agent_info",
                                "message" to "manual_send",
                                "details" to "user_triggered",
                                "app_version_name" to BuildConfig.VERSION_NAME,
                                "app_version_code" to BuildConfig.VERSION_CODE,
                                "android_sdk" to Build.VERSION.SDK_INT,
                                "device_model" to (Build.MODEL?.trim().orEmpty()),
                                "device_manufacturer" to (Build.MANUFACTURER?.trim().orEmpty()),
                                "device_fingerprint" to fingerprint,
                            )

                            runCatching { rootRef.child("info").child("manual_report_at").setValue(now) }
                            runCatching { rootRef.child("logs").push().setValue(entry) }
                        }
                        withContext(Dispatchers.Main) { sending = false }
                    }
                },
                enabled = !sending,
            ) {
                LucideIcon(
                    name = "send",
                    modifier = Modifier.height(18.dp),
                    tint = if (sending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PresenceSyncFab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }

    val rotationDeg by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (syncing) 360f else 0f,
        animationSpec = if (syncing) {
            androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(800),
                repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
            )
        } else {
            androidx.compose.animation.core.tween(0)
        },
        label = "sync_rotation",
    )

    GlassCard(
        modifier = modifier,
        cornerRadius = 999.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        elevation = 6.dp,
        containerAlpha = if (showDone) 0.55f else 0.35f,
        borderAlpha = 0.18f,
    ) {
        IconButton(
            onClick = {
                if (syncing) return@IconButton
                syncing = true
                showDone = false
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ArgosForegroundService::class.java)
                            .setAction(ArgosForegroundService.ACTION_FORCE_PRESENCE_SYNC),
                    )
                }
                scope.launch {
                    delay(2_500L)
                    syncing = false
                    showDone = true
                    delay(2_000L)
                    showDone = false
                }
            },
            modifier = Modifier.size(44.dp),
        ) {
            val tint = when {
                showDone -> ArgosTokens.Success
                syncing  -> MaterialTheme.colorScheme.primary
                else     -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            }
            LucideIcon(
                name = if (showDone) "check" else "refresh-cw",
                modifier = Modifier
                    .height(18.dp)
                    .graphicsLayer { rotationZ = if (syncing) rotationDeg else 0f },
                tint = tint,
            )
        }
    }
}

@Composable
private fun OfflineIndicator(
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ArgosTokens.Navy.copy(alpha = 0.28f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "⚠ Sem conexão com o servidor",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun LauncherAppGrid(
    items: List<UiApp>,
    onOpenApp: (UiApp) -> Unit,
    onAdminManageApp: (UiApp) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val cfg = LocalConfiguration.current
        val windowClass = LocalArgosWindowClass.current
        val dimens = LocalArgosDimens.current
        val cols = remember(maxWidth, cfg.orientation, windowClass) { computeGridColumns(cfg, windowClass, maxWidth) }
        val rows = remember(items.size, cols) { ((items.size + cols - 1) / cols).coerceAtLeast(1) }
        val density = LocalDensity.current
        val targetCellHeightPx = remember(density, dimens.appIconSize) { with(density) { (dimens.appIconSize + 48.dp).roundToPx() } }
        val maxHeightPx = remember(density, maxHeight) { with(density) { maxHeight.roundToPx() } }
        val iconSize = dimens.appIconSize
        val cellHeight = remember(dimens.appIconSize) { dimens.appIconSize + 48.dp }
        val needsScroll = remember(rows, targetCellHeightPx, maxHeightPx) { (rows * targetCellHeightPx) > maxHeightPx }

        if (needsScroll) {
            LazyVerticalGrid(
                modifier = Modifier.fillMaxSize(),
                columns = GridCells.Fixed(cols.coerceAtLeast(1)),
                verticalArrangement = Arrangement.Top,
            ) {
                gridItems(items) { ui ->
                    LauncherAppIcon(
                        modifier = Modifier.height(cellHeight),
                        ui = ui,
                        iconSize = iconSize,
                        maxLabelLines = dimens.appLabelMaxLines,
                        onClick = { onOpenApp(ui) },
                        onLongPress = { onAdminManageApp(ui) },
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Layout(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        items.forEach { ui ->
                            LauncherAppIcon(
                                ui = ui,
                                iconSize = iconSize,
                                maxLabelLines = dimens.appLabelMaxLines,
                                onClick = { onOpenApp(ui) },
                                onLongPress = { onAdminManageApp(ui) },
                            )
                        }
                    },
                ) { measurables, constraints ->
                    val c = cols.coerceAtLeast(1)
                    val r = ((measurables.size + c - 1) / c).coerceAtLeast(1)
                    val cellW = constraints.maxWidth / c
                    val maxCellH = (constraints.maxHeight / r).coerceAtLeast(1)
                    val cellH = minOf(targetCellHeightPx, maxCellH)
                    val placeables = measurables.map { it.measure(Constraints.fixed(cellW, cellH)) }
                    val height = (cellH * r).coerceAtMost(constraints.maxHeight)
                    layout(constraints.maxWidth, height) {
                        placeables.forEachIndexed { idx, p ->
                            val row = idx / c
                            val col = idx % c
                            p.placeRelative(x = col * cellW, y = row * cellH)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LauncherAppIcon(
    modifier: Modifier = Modifier,
    ui: UiApp,
    iconSize: androidx.compose.ui.unit.Dp,
    maxLabelLines: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 90),
        label = "app_press_scale",
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(top = 6.dp, bottom = 6.dp, start = 6.dp, end = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        val icon = ui.app.icon
        if (icon != null) {
            Image(
                bitmap = icon.toBitmap(width = 192, height = 192).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(iconSize)
                    .clip(RoundedCornerShape(18.dp))
                    .background(ArgosTokens.Navy.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(name = "app-window", modifier = Modifier.height(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            ui.app.label,
            style = MaterialTheme.typography.labelMedium.copy(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.5f),
                    offset = Offset(0f, 1f),
                    blurRadius = 3f,
                ),
            ),
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = maxLabelLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun computeGridColumns(
    cfg: Configuration,
    windowClass: ArgosWindowClass,
    maxWidth: androidx.compose.ui.unit.Dp,
): Int {
    val isLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val w = maxWidth.value
    val manufacturer = Build.MANUFACTURER?.trim().orEmpty().lowercase()
    val model = Build.MODEL?.trim().orEmpty().lowercase()
    val zebraTc2x = manufacturer.contains("zebra") && (model.contains("tc21") || model.contains("tc22") || model.contains("tc26"))

    if (zebraTc2x && windowClass == ArgosWindowClass.Compact) {
        return if (isLandscape) 4 else 3
    }

    return when (windowClass) {
        ArgosWindowClass.Compact -> if (w >= 360f) 4 else 3
        ArgosWindowClass.Medium -> if (isLandscape) 6 else 5
        ArgosWindowClass.Expanded -> if (isLandscape) 6 else 5
    }
}

private fun Modifier.tenSecondHold(onTriggered: () -> Unit): Modifier {
    return pointerInput(onTriggered) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.uptimeMillis
            var fired = false
            while (true) {
                val event = awaitPointerEvent()
                val change =
                    event.changes.firstOrNull { it.id == down.id }
                        ?: event.changes.firstOrNull()
                        ?: break
                if (!change.pressed) break
                val elapsed = change.uptimeMillis - start
                if (elapsed >= 10_000L) {
                    fired = true
                    onTriggered()
                    break
                }
            }
            if (fired) {
                waitForUpOrCancellation()
            }
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

    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var shake by remember { mutableStateOf(0f) }

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
                        .offset { IntOffset(shake.roundToInt(), 0) },
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
                        val result = auth.authenticate(password)
                        when (result) {
                            is AdminAuthResult.Success -> onSuccess()
                            is AdminAuthResult.LockedOut -> {
                                error = "Bloqueado por ${result.secondsRemaining}s"
                                vibrateError()
                                shake = 18f
                                delay(60)
                                shake = -18f
                                delay(60)
                                shake = 0f
                            }
                            is AdminAuthResult.Failure -> {
                                error = "Senha incorreta (${result.failedAttempts}/3)"
                                vibrateError()
                                shake = 18f
                                delay(60)
                                shake = -18f
                                delay(60)
                                shake = 0f
                            }
                        }
                    }
                },
            ) { Text("OK") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun resolveOnline(context: Context): String {
    val settings = SettingsManager(context.applicationContext)
    return when (settings.getOfflineStateCached().trim().lowercase(Locale.ROOT)) {
        "online" -> "Online"
        "unstable" -> "Instável"
        "offline" -> "Offline"
        else -> {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "Offline"
            val caps = cm.getNetworkCapabilities(network) ?: return "Offline"
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (hasInternet) "Online" else "Offline"
        }
    }
}

private fun resolveWifi(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return "—"
    val caps = cm.getNetworkCapabilities(network) ?: return "—"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Celular"
        else -> "Rede"
    }
}

private fun resolveBattery(context: Context): String {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val level = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1)
    return if (level in 0..100) "$level%" else "—"
}

private fun formatRamPercent(context: Context): String {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    val total = info.totalMem.toDouble().coerceAtLeast(1.0)
    val used = total - info.availMem.toDouble()
    val pct = ((used / total) * 100.0).coerceIn(0.0, 100.0)
    return "${pct.roundToInt()}%"
}

private fun formatStoragePercent(context: Context): String {
    val stat = StatFs(context.filesDir.absolutePath)
    val total = stat.totalBytes.toDouble().coerceAtLeast(1.0)
    val used = total - stat.availableBytes.toDouble()
    val pct = ((used / total) * 100.0).coerceIn(0.0, 100.0)
    return "${pct.roundToInt()}%"
}

private fun formatLastSync(ms: Long): String {
    if (ms <= 0L) return "—"
    val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0L)
    val min = diff / 60_000L
    return when {
        min < 1 -> "agora"
        min == 1L -> "há 1 minuto"
        min < 60 -> "há $min minutos"
        else -> {
            val h = min / 60
            if (h == 1L) "há 1 hora" else "há $h horas"
        }
    }
}

private fun requestLockTaskStopFromContext(context: Context) {
    runCatching {
        context.startActivity(
            Intent("com.mupa.agent.argos.action.LOCKTASK_STOP")
                .setClassName(context.packageName, "com.mupa.agent.argos.ui.LockTaskBridgeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}

private fun copyApkToInternalFiles(context: Context, uri: android.net.Uri): File? {
    val dir = File(context.filesDir, "apk_import").apply { mkdirs() }
    val file = File(dir, "apk_${System.currentTimeMillis()}.apk")
    val cr = context.contentResolver
    cr.openInputStream(uri)?.use { input ->
        FileOutputStream(file).use { out -> input.copyTo(out) }
    } ?: return null
    return file
}

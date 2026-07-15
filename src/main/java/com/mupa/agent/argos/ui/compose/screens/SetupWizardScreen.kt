package com.mupa.agent.argos.ui.compose.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mupa.agent.argos.R
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.permissions.PermissionId
import com.mupa.agent.argos.permissions.PermissionManager
import com.mupa.agent.argos.permissions.PermissionState
import com.mupa.agent.argos.permissions.PermissionStatus
import com.mupa.agent.argos.ui.compose.ArgosTokens
import com.mupa.agent.argos.ui.compose.GlassCard
import com.mupa.agent.argos.ui.compose.LocalArgosDimens
import com.mupa.agent.argos.ui.ArgosLauncherActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SetupWizardPhase {
    Permissions,
    WiFiSetup,
    BindDevice,
}

@Composable
fun SetupWizardScreen(
    onBack: () -> Unit,
    onStateChanged: () -> Unit,
    onComplete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val manager = remember { PermissionManager(context) }
    val settings = remember { SettingsManager(context.applicationContext) }

    var states by remember { mutableStateOf<List<PermissionState>>(emptyList()) }
    var showDeviceOwnerHelp by remember { mutableStateOf(false) }
    var stepIndex by remember { mutableStateOf(0) }
    var phase by remember { mutableStateOf(SetupWizardPhase.Permissions) }

    fun refresh() {
        scope.launch {
            states = withContext(Dispatchers.Default) { manager.checkAllPermissions() }
            onStateChanged()
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val ordered = remember {
        listOf(
            PermissionId.DeviceOwner,
            PermissionId.DeviceAdmin,
            PermissionId.BatteryOptimizations,
            PermissionId.Overlay,
            PermissionId.UsageStats,
            PermissionId.Accessibility,
            PermissionId.Notifications,
            PermissionId.UnknownAppSources,
        )
    }
    val byId = states.associateBy { it.id }

    LaunchedEffect(states) {
        if (ordered.isEmpty()) return@LaunchedEffect
        stepIndex = stepIndex.coerceIn(0, ordered.lastIndex)
    }

    val completed = states.count { it.status == PermissionStatus.Completed }
    val total = states.size.coerceAtLeast(1)
    val criticalReady = states.filter { it.critical }.all { it.status == PermissionStatus.Completed }

    val dimens = LocalArgosDimens.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(dimens.padding),
    ) {
        if (phase == SetupWizardPhase.Permissions) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                containerAlpha = 0.40f,
                borderAlpha = 0.12f,
                elevation = 6.dp,
            ) {
                Column {
                    Image(
                        painter = painterResource(id = R.drawable.logo_argos_hor),
                        contentDescription = "ARGOS",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Configuração do dispositivo", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("Permissões e provisionamento", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val currentId = ordered.getOrNull(stepIndex)
            val current = currentId?.let { byId[it] }
            val currentStatus = current?.status ?: PermissionStatus.Checking
            val currentCritical = current?.critical == true
            val currentReady = currentStatus == PermissionStatus.Completed || currentStatus == PermissionStatus.NotSupported
            val wizardProgress =
                if (ordered.isNotEmpty()) ((stepIndex + 1).toFloat() / ordered.size.toFloat()).coerceIn(0f, 1f) else 0f

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val scrollState = rememberScrollState()
                val screenWidthDp = LocalConfiguration.current.screenWidthDp
                val compact = screenWidthDp < 420

                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(scrollState),
                    ) {
                        Text("Passo ${stepIndex + 1} de ${ordered.size}", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("$completed de $total concluídas", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = { wizardProgress },
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        if (!criticalReady) {
                            Text(
                                "MDM bloqueado: faltam permissões críticas",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        if (current != null) {
                            PermissionStepCard(
                                state = current,
                                onOpen = {
                                    val intent = current.intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    if (current.id == PermissionId.DeviceOwner) {
                                        showDeviceOwnerHelp = true
                                    } else {
                                        intent?.let { context.startActivity(it) }
                                    }
                                },
                            )
                        } else {
                            Text("Carregando…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val atFirst = stepIndex <= 0
                    val atLast = stepIndex >= ordered.lastIndex
                    val canNext = when {
                        current == null -> false
                        currentReady -> true
                        currentCritical -> false
                        else -> true
                    }
                    val nextLabel = if (atLast && currentReady) "Concluir" else "Próximo"
                    val openEnabled = current != null && (current.status == PermissionStatus.Pending || current.status == PermissionStatus.Error)

                    val onNext: () -> Unit = onNext@{
                        if (current == null) return@onNext
                        if (atLast) {
                            val dpm = DeviceOwnerPolicyManager(context)
                            if (dpm.isDeviceOwner(context.packageName)) {
                                dpm.setLauncherAsHome(context.packageName, enabled = true)
                            }
                            val reallyBound = settings.isDeviceBoundCached() && settings.getBoundCompanyIdCached() != "local-bypass"
                            if (reallyBound) {
                                if (onComplete != null) {
                                    onComplete()
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            Intent(context, ArgosLauncherActivity::class.java)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                                        )
                                    }
                                    onBack()
                                }
                                return@onNext
                            }
                            phase = SetupWizardPhase.WiFiSetup
                            return@onNext
                        }
                        stepIndex = (stepIndex + 1).coerceAtMost(ordered.lastIndex)
                    }

                    if (compact) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (atFirst) onBack() else stepIndex = (stepIndex - 1).coerceAtLeast(0)
                                },
                            ) { Text("Voltar") }

                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = openEnabled,
                                onClick = {
                                    if (current == null) return@Button
                                    when (current.id) {
                                        PermissionId.DeviceOwner -> showDeviceOwnerHelp = true
                                        else -> current.intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
                                    }
                                },
                            ) { Text(current?.actionLabel ?: "Abrir") }

                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = canNext,
                                onClick = onNext,
                            ) { Text(nextLabel) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (atFirst) onBack() else stepIndex = (stepIndex - 1).coerceAtLeast(0)
                                },
                            ) { Text("Voltar") }

                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = openEnabled,
                                onClick = {
                                    if (current == null) return@Button
                                    when (current.id) {
                                        PermissionId.DeviceOwner -> showDeviceOwnerHelp = true
                                        else -> current.intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
                                    }
                                },
                            ) { Text(current?.actionLabel ?: "Abrir") }

                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = canNext,
                                onClick = onNext,
                            ) { Text(nextLabel) }
                        }
                    }
                }
            }
        } else if (phase == SetupWizardPhase.WiFiSetup) {
            WiFiSetupWizard(
                onBack = { phase = SetupWizardPhase.Permissions },
                onDone = { phase = SetupWizardPhase.BindDevice },
            )
        } else {
            GroupLinkWizard(
                onBack = { phase = SetupWizardPhase.WiFiSetup },
                onDone = {
                    if (onComplete != null) {
                        onComplete()
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(context, ArgosLauncherActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                            )
                        }
                        onBack()
                    }
                },
            )
        }
    }

    if (showDeviceOwnerHelp) {
        DeviceOwnerHelpDialog(
            onDismiss = {
                showDeviceOwnerHelp = false
                refresh()
            },
        )
    }
}

@Composable
private fun PermissionStepCard(
    state: PermissionState,
    onOpen: () -> Unit,
) {
    val dot = when (state.status) {
        PermissionStatus.Completed -> ArgosTokens.Success
        PermissionStatus.Pending -> ArgosTokens.Warning
        PermissionStatus.Checking -> ArgosTokens.PrimaryBlue
        PermissionStatus.Error -> ArgosTokens.Danger
        PermissionStatus.NotSupported -> ArgosTokens.Offline
    }
    val status = when (state.status) {
        PermissionStatus.Completed -> "Concluído"
        PermissionStatus.Pending -> "Pendente"
        PermissionStatus.Checking -> "Em análise"
        PermissionStatus.Error -> "Erro"
        PermissionStatus.NotSupported -> "Não suportado pela ROM"
    }
    val critical = if (state.critical) " (crítico)" else ""

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(modifier = Modifier.padding(top = 6.dp)) {
                drawCircle(color = dot, radius = 10f)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(state.label + critical, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(2.dp))
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val info = state.info?.trim().orEmpty()
                if (info.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(info, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val actionable = state.status == PermissionStatus.Pending || state.status == PermissionStatus.Error
            if (actionable) OutlinedButton(onClick = onOpen) { Text(state.actionLabel ?: "Abrir") }
        }
    }
}

@Composable
private fun DeviceOwnerHelpDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val cmd = "adb shell dpm set-device-owner com.mupa.agent.argos/.mdm.ArgosDeviceAdminReceiver"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device Owner (crítico)") },
        text = {
            Text(
                "Para ativar o Device Owner, execute no computador com o device conectado:\n\n$cmd\n\nDepois volte e toque em \"Conceder (guiado)\".",
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("argos_device_owner_adb", cmd))
                    onDismiss()
                },
            ) { Text("Copiar") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Fechar") }
        },
    )
}

@Composable
private fun WiFiSetupWizard(
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val wifiConfig = remember { com.mupa.agent.argos.wifi.WifiConfig(context) }
    var ssid by remember { mutableStateOf(wifiConfig.getSsid()) }
    var password by remember { mutableStateOf(wifiConfig.getPassword()) }
    var showPassword by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }

    val dimens = LocalArgosDimens.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(dimens.padding),
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            containerAlpha = 0.40f,
            borderAlpha = 0.12f,
            elevation = 6.dp,
        ) {
            Column {
                Image(
                    painter = painterResource(id = R.drawable.logo_argos_hor),
                    contentDescription = "ARGOS",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("Configuração de WiFi", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(2.dp))
                Text("Rede WiFi da loja (opcional)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                Text("Configurar WiFi", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Salve o SSID e senha da WiFi da loja. Quando a player chegar no cliente, " +
                        "conectará automaticamente.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text("SSID (Nome da Rede)", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ex: Loja_WiFi") },
                    enabled = !isConnecting,
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text("Senha (WPA2)", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Digite a senha") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Ocultar" else "Mostrar", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    enabled = !isConnecting,
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (message.isNotEmpty()) {
                    Text(
                        message,
                        color = if ("Erro" in message) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (isConnecting) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                Spacer(modifier = Modifier.height(16.dp).weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onBack,
                        enabled = !isConnecting,
                    ) {
                        Text("Voltar")
                    }

                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (ssid.trim().isEmpty()) {
                                message = "SSID não pode estar vazio"
                                return@Button
                            }
                            if (password.isEmpty()) {
                                message = "Senha não pode estar vazia"
                                return@Button
                            }
                            isConnecting = true
                            message = "Conectando..."
                            wifiConfig.setCredentials(ssid.trim(), password)
                            // Simula um pequeno delay antes de passar para a próxima fase
                            Thread {
                                Thread.sleep(1500)
                                message = "Credenciais salvas!"
                                Thread.sleep(1000)
                                onDone()
                            }.start()
                        },
                        enabled = !isConnecting,
                    ) {
                        Text("Salvar e Continuar")
                    }
                }
            }
        }
    }
}


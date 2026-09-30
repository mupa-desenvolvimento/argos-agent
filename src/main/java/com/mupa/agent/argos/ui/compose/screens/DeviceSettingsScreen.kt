package com.mupa.agent.argos.ui.compose.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mupa.agent.argos.BuildConfig
import com.mupa.agent.argos.R
import com.mupa.agent.argos.managers.ArgosCompanyDetails
import com.mupa.agent.argos.managers.DeviceIdentityManager
import com.mupa.agent.argos.managers.PlayerAppConstants
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.managers.SupabaseDirectoryClient
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.permissions.PermissionManager
import com.mupa.agent.argos.permissions.PermissionStatus
import com.mupa.agent.argos.ui.compose.ArgosTokens
import com.mupa.agent.argos.ui.compose.GlassCard
import com.mupa.agent.argos.ui.compose.LocalArgosDimens
import com.mupa.agent.argos.ui.compose.LucideIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

private enum class DeviceSettingsState {
    Menu,
    DeviceInfo,
    EditCompanyCode,
    EditCompanyConfirm,
    EditDeviceName,
    EditBranch,
    EditDeviceType,
    EditGroup,
    LinkGroupCode,
    Network,
    Diagnostics,
    Provisioning,
    Advanced,
    About,
    Technical,
}

private data class SettingsDeviceType(
    val id: String,
    val title: String,
)

@Composable
fun DeviceSettingsScreen(
    onBack: () -> Unit,
    onOpenSetupWizard: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dimens = LocalArgosDimens.current
    val settings = remember { SettingsManager(context.applicationContext) }
    val directory = remember { SupabaseDirectoryClient() }
    val permissions = remember { PermissionManager(context.applicationContext) }

    val deviceId = remember { DeviceIdentityManager.getPersistentDeviceId(context.applicationContext) }
    val fingerprint = remember { DeviceIdentityManager.getDeviceFingerprint(context.applicationContext) }

    var state by remember { mutableStateOf(DeviceSettingsState.Menu) }
    var companyCode by remember { mutableStateOf(settings.getBoundCompanyCodeCached().trim()) }
    var companyDetails by remember { mutableStateOf<ArgosCompanyDetails?>(null) }
    var wizardError by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    var deviceName by remember { mutableStateOf(settings.getBoundDeviceNicknameCached().ifBlank { "" }) }
    var branchNumber by remember { mutableStateOf(settings.getBoundDeviceBranchCached().ifBlank { "" }) }
    var selectedGroup by remember { mutableStateOf(settings.getBoundDeviceGroupCached().ifBlank { "" }) }
    var selectedType by remember { mutableStateOf(settings.getBoundDeviceTypeCached().ifBlank { "" }) }

    val types = remember {
        listOf(
            SettingsDeviceType(id = "terminal_consulta", title = "Terminal de Consulta"),
            SettingsDeviceType(id = "player_tv", title = "Player TV"),
            SettingsDeviceType(id = "totem", title = "Totem"),
            SettingsDeviceType(id = "tablet", title = "Tablet"),
            SettingsDeviceType(id = "coletor", title = "Coletor"),
            SettingsDeviceType(id = "smartphone", title = "Smartphone"),
            SettingsDeviceType(id = "android_box", title = "Android Box"),
        )
    }

    fun ensureSupabaseConfigured(): Boolean {
        val urlOk = BuildConfig.SUPABASE_URL.trim().isNotBlank()
        val keyOk = BuildConfig.SUPABASE_COMPANY_KEY.trim().isNotBlank() || BuildConfig.SUPABASE_ANON_KEY.trim().isNotBlank()
        return urlOk && keyOk
    }

    fun normalizeCompanyCodeInput(raw: String): String {
        val next = raw.trim().filter { it.isLetterOrDigit() }
        return next.lowercase().take(12)
    }

    fun normalizeDeviceNameInput(raw: String): String {
        return raw.trimStart().take(32)
    }

    fun normalizeBranchInput(raw: String): String {
        return raw.filter { it.isDigit() }.take(8)
    }

    fun startEditCompany() {
        wizardError = null
        companyDetails = null
        companyCode = settings.getBoundCompanyCodeCached().trim()
        state = DeviceSettingsState.EditCompanyCode
    }

    fun syncToServer(
        company: ArgosCompanyDetails,
        nickname: String,
        branch: String,
        group: String,
        type: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        if (isBusy) return
        if (!ensureSupabaseConfigured()) {
            wizardError = "Não foi possível sincronizar agora."
            onFailure()
            return
        }
        isBusy = true
        wizardError = null
        scope.launch(Dispatchers.IO) {
            val apps = runCatching { readInstalledPackages(context.applicationContext) }.getOrElse { emptyList() }
            val payload =
                JSONObject()
                    .put("apelido_interno", nickname.trim())
                    .put("apps_instalados", JSONArray(apps))
                    .put("pin", company.code)
                    .put("serial", deviceId.trim())
                    .put("tipo_da_licenca", "enterprise")
                    .put("empresa", company.id.ifBlank { company.code })
                    .put("grupo_dispositivos", group.trim().ifBlank { JSONObject.NULL })
                    .put("campanhas", JSONArray())
                    .put("ip_dispositivo", "")
                    .put("num_filial", branch.trim())
                    .put("online", true)
                    .put("type", type.trim())

            val result = runCatching { directory.registerDevice(payload) }.getOrNull()
            withContext(Dispatchers.Main) {
                isBusy = false
                if (result == null) {
                    wizardError = "Não foi possível sincronizar agora."
                    onFailure()
                    return@withContext
                }
                scope.launch {
                    settings.setBoundCompany(companyId = company.id, companyName = company.name, companyCode = company.code)
                    settings.setBoundDevice(deviceId = result.deviceId.ifBlank { fingerprint.primaryId }, group = result.group.ifBlank { group }, token = result.token)
                    settings.setBoundDeviceDetails(nickname = nickname, branch = branch, type = type)
                }
                onSuccess()
            }
        }
    }

    fun loadCompanyByCode() {
        if (isBusy) return
        val code = companyCode.trim()
        if (code.isBlank()) return
        if (!ensureSupabaseConfigured()) {
            wizardError = "Não foi possível iniciar agora."
            return
        }
        isBusy = true
        wizardError = null
        companyDetails = null
        scope.launch {
            val details = runCatching { directory.fetchCompanyDetailsByCode(code) }.getOrNull()
            isBusy = false
            if (details == null) {
                wizardError = "Código não encontrado"
                return@launch
            }
            companyDetails = details
            state = DeviceSettingsState.EditCompanyConfirm
        }
    }

    fun backFromWizard() {
        if (isBusy) return
        state =
            when (state) {
                DeviceSettingsState.Menu -> return onBack()
                DeviceSettingsState.DeviceInfo,
                DeviceSettingsState.Network,
                DeviceSettingsState.Diagnostics,
                DeviceSettingsState.Provisioning,
                DeviceSettingsState.Advanced,
                DeviceSettingsState.About,
                DeviceSettingsState.Technical,
                -> DeviceSettingsState.Menu

                DeviceSettingsState.EditCompanyCode -> DeviceSettingsState.Menu
                DeviceSettingsState.EditCompanyConfirm -> DeviceSettingsState.EditCompanyCode
                DeviceSettingsState.EditDeviceName,
                DeviceSettingsState.EditBranch,
                DeviceSettingsState.EditDeviceType,
                DeviceSettingsState.EditGroup,
                DeviceSettingsState.LinkGroupCode,
                -> DeviceSettingsState.Menu
            }
    }

    var technicalEnabled by remember { mutableStateOf(settings.getTechnicalModeEnabledCached()) }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logo_argos_hor),
                        contentDescription = "ARGOS",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.height(44.dp),
                    )
                    GlassSmallChip(text = "Configurações")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Configurações do dispositivo", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(2.dp))
                Text("Organizado, simples e seguro", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(dimens.gap))

        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    val enter = slideInHorizontally(animationSpec = tween(260)) { it / 2 } + fadeIn(animationSpec = tween(260))
                    val exit = slideOutHorizontally(animationSpec = tween(220)) { -it / 3 } + fadeOut(animationSpec = tween(220))
                    enter togetherWith exit
                },
                label = "device_settings_state",
            ) { s ->
                when (s) {
                    DeviceSettingsState.Menu -> {
                        val baseItems = mutableListOf(
                            MenuItemSpec("Informações do Dispositivo", "device-info", onClick = { state = DeviceSettingsState.DeviceInfo }),
                            MenuItemSpec("Empresa", "building-2", onClick = { startEditCompany() }),
                            MenuItemSpec("Dispositivo", "smartphone", onClick = { state = DeviceSettingsState.EditDeviceName }),
                            MenuItemSpec("Filial", "store", onClick = { state = DeviceSettingsState.EditBranch }),
                            MenuItemSpec("Grupo", "layers", onClick = { state = DeviceSettingsState.EditGroup }),
                            MenuItemSpec("Vincular por código / QR", "qr-code", onClick = { state = DeviceSettingsState.LinkGroupCode }),
                            MenuItemSpec("Tipo do Dispositivo", "layout-grid", onClick = { state = DeviceSettingsState.EditDeviceType }),
                            MenuItemSpec("Rede", "wifi", onClick = { state = DeviceSettingsState.Network }),
                            MenuItemSpec("Diagnóstico", "shield-check", onClick = { state = DeviceSettingsState.Diagnostics }),
                            MenuItemSpec("Provisionamento", "sparkles", onClick = { state = DeviceSettingsState.Provisioning }),
                            MenuItemSpec("Avançado", "wrench", onClick = { state = DeviceSettingsState.Advanced }),
                            MenuItemSpec("Sobre o ARGOS", "info", onClick = { state = DeviceSettingsState.About }),
                        )
                        if (technicalEnabled) baseItems.add(MenuItemSpec("Modo técnico", "terminal", onClick = { state = DeviceSettingsState.Technical }))
                        MenuList(baseItems)
                    }

                    DeviceSettingsState.DeviceInfo -> {
                        DeviceInfoCards(
                            deviceId = deviceId,
                            fingerprintManufacturer = fingerprint.manufacturer,
                            fingerprintModel = fingerprint.model,
                            androidSdk = fingerprint.androidVersion,
                            settings = settings,
                        )
                    }

                    DeviceSettingsState.EditCompanyCode -> {
                        WizardScaffold(
                            title = "Empresa",
                            step = 1,
                            totalSteps = 2,
                            error = wizardError,
                            primaryLabel = if (isBusy) "Buscando…" else "Confirmar",
                            primaryEnabled = !isBusy && companyCode.trim().length >= 3,
                            onPrimary = { loadCompanyByCode() },
                        ) {
                            Text("Digite o código da empresa.", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(10.dp))
                            GlassValueBox(value = companyCode.uppercase().ifBlank { "—" })
                            Spacer(modifier = Modifier.height(12.dp))
                            ArgosAlphaNumericKeyboard(
                                enabled = !isBusy,
                                onKey = { key ->
                                    val next =
                                        when (key) {
                                            "DEL" -> companyCode.dropLast(1)
                                            "LIMPAR" -> ""
                                            else -> companyCode + key.lowercase()
                                        }
                                    companyCode = normalizeCompanyCodeInput(next)
                                },
                            )
                        }
                    }

                    DeviceSettingsState.EditCompanyConfirm -> {
                        val company = companyDetails
                        WizardScaffold(
                            title = "Empresa",
                            step = 2,
                            totalSteps = 2,
                            error = wizardError,
                            primaryLabel = if (isBusy) "Sincronizando…" else "Confirmar vínculo",
                            primaryEnabled = !isBusy && company != null,
                            onPrimary = {
                                val c = company ?: return@WizardScaffold
                                val nickname = settings.getBoundDeviceNicknameCached().ifBlank { deviceName.ifBlank { "DEVICE" } }
                                val branch = settings.getBoundDeviceBranchCached().ifBlank { branchNumber.ifBlank { "0" } }
                                val type = settings.getBoundDeviceTypeCached().ifBlank { selectedType.ifBlank { "terminal_consulta" } }
                                val group = settings.getBoundDeviceGroupCached().ifBlank { selectedGroup }
                                syncToServer(
                                    company = c,
                                    nickname = nickname,
                                    branch = branch,
                                    group = group,
                                    type = type,
                                    onSuccess = {
                                        companyCode = c.code
                                        companyDetails = c
                                        state = DeviceSettingsState.Menu
                                    },
                                    onFailure = {},
                                )
                            },
                        ) {
                            CompanyConfirmCard(company = company)
                        }
                    }

                    DeviceSettingsState.EditDeviceName -> {
                        val current = settings.getBoundDeviceNicknameCached().ifBlank { deviceName }
                        WizardScaffold(
                            title = "Nome do dispositivo",
                            step = 1,
                            totalSteps = 1,
                            error = wizardError,
                            primaryLabel = if (isBusy) "Salvando…" else "Salvar",
                            primaryEnabled = !isBusy && deviceName.trim().isNotBlank(),
                            onPrimary = {
                                val company =
                                    ArgosCompanyDetails(
                                        id = settings.getBoundCompanyIdCached(),
                                        code = settings.getBoundCompanyCodeCached(),
                                        name = settings.getBoundCompanyNameCached(),
                                        city = "",
                                        deviceCount = 0,
                                        logoUrl = "",
                                    )
                                if (company.code.isBlank()) {
                                    wizardError = "Vincule uma empresa primeiro."
                                    return@WizardScaffold
                                }
                                val branch = settings.getBoundDeviceBranchCached().ifBlank { branchNumber.ifBlank { "0" } }
                                val type = settings.getBoundDeviceTypeCached().ifBlank { selectedType.ifBlank { "terminal_consulta" } }
                                val group = settings.getBoundDeviceGroupCached().ifBlank { selectedGroup }
                                syncToServer(
                                    company = company,
                                    nickname = deviceName.trim(),
                                    branch = branch,
                                    group = group,
                                    type = type,
                                    onSuccess = { state = DeviceSettingsState.Menu },
                                    onFailure = {},
                                )
                            },
                        ) {
                            Text("Como deseja identificar este dispositivo?", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(10.dp))
                            GlassValueBox(value = deviceName.ifBlank { "—" })
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Atual: ${current.ifBlank { "—" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(12.dp))
                            ArgosAlphaNumericKeyboard(
                                enabled = !isBusy,
                                onKey = { key ->
                                    val next =
                                        when (key) {
                                            "DEL" -> deviceName.dropLast(1)
                                            "LIMPAR" -> ""
                                            "ESP" -> deviceName + " "
                                            else -> deviceName + key
                                        }
                                    deviceName = normalizeDeviceNameInput(next)
                                },
                            )
                        }
                    }

                    DeviceSettingsState.EditBranch -> {
                        val current = settings.getBoundDeviceBranchCached().ifBlank { branchNumber }
                        WizardScaffold(
                            title = "Filial",
                            step = 1,
                            totalSteps = 1,
                            error = wizardError,
                            primaryLabel = if (isBusy) "Salvando…" else "Salvar",
                            primaryEnabled = !isBusy && branchNumber.trim().isNotBlank(),
                            onPrimary = {
                                val company =
                                    ArgosCompanyDetails(
                                        id = settings.getBoundCompanyIdCached(),
                                        code = settings.getBoundCompanyCodeCached(),
                                        name = settings.getBoundCompanyNameCached(),
                                        city = "",
                                        deviceCount = 0,
                                        logoUrl = "",
                                    )
                                if (company.code.isBlank()) {
                                    wizardError = "Vincule uma empresa primeiro."
                                    return@WizardScaffold
                                }
                                val nickname = settings.getBoundDeviceNicknameCached().ifBlank { deviceName.ifBlank { "DEVICE" } }
                                val type = settings.getBoundDeviceTypeCached().ifBlank { selectedType.ifBlank { "terminal_consulta" } }
                                val group = settings.getBoundDeviceGroupCached().ifBlank { selectedGroup }
                                syncToServer(
                                    company = company,
                                    nickname = nickname,
                                    branch = branchNumber.trim(),
                                    group = group,
                                    type = type,
                                    onSuccess = { state = DeviceSettingsState.Menu },
                                    onFailure = {},
                                )
                            },
                        ) {
                            Text("Qual o número da filial?", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(10.dp))
                            GlassValueBox(value = branchNumber.ifBlank { "—" })
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Atual: ${current.ifBlank { "—" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(12.dp))
                            ArgosNumericKeyboard(
                                enabled = !isBusy,
                                onKey = { key ->
                                    val next =
                                        when (key) {
                                            "DEL" -> branchNumber.dropLast(1)
                                            "LIMPAR" -> ""
                                            else -> branchNumber + key
                                        }
                                    branchNumber = normalizeBranchInput(next)
                                },
                            )
                        }
                    }

                    DeviceSettingsState.EditDeviceType -> {
                        WizardScaffold(
                            title = "Tipo do dispositivo",
                            step = 1,
                            totalSteps = 1,
                            error = wizardError,
                            primaryLabel = if (isBusy) "Salvando…" else "Salvar",
                            primaryEnabled = !isBusy && selectedType.trim().isNotBlank(),
                            onPrimary = {
                                val company =
                                    ArgosCompanyDetails(
                                        id = settings.getBoundCompanyIdCached(),
                                        code = settings.getBoundCompanyCodeCached(),
                                        name = settings.getBoundCompanyNameCached(),
                                        city = "",
                                        deviceCount = 0,
                                        logoUrl = "",
                                    )
                                if (company.code.isBlank()) {
                                    wizardError = "Vincule uma empresa primeiro."
                                    return@WizardScaffold
                                }
                                val nickname = settings.getBoundDeviceNicknameCached().ifBlank { deviceName.ifBlank { "DEVICE" } }
                                val branch = settings.getBoundDeviceBranchCached().ifBlank { branchNumber.ifBlank { "0" } }
                                val group = settings.getBoundDeviceGroupCached().ifBlank { selectedGroup }
                                syncToServer(
                                    company = company,
                                    nickname = nickname,
                                    branch = branch,
                                    group = group,
                                    type = selectedType.trim(),
                                    onSuccess = { state = DeviceSettingsState.Menu },
                                    onFailure = {},
                                )
                            },
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                                Text("Selecione o tipo.", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(2.dp))
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                                    items(types) { t ->
                                        val selected = selectedType == t.id
                                        val bg = MaterialTheme.colorScheme.surface.copy(alpha = if (selected) 0.42f else 0.18f)
                                        val border = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 68.dp)
                                                .background(color = bg, shape = RoundedCornerShape(16.dp))
                                                .clickable { selectedType = t.id }
                                                .padding(14.dp),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                val dotColor = if (selected) MaterialTheme.colorScheme.primary else border
                                                Box(
                                                    modifier = Modifier
                                                        .size(10.dp)
                                                        .background(dotColor, shape = RoundedCornerShape(20.dp)),
                                                )
                                                Text(t.title, style = MaterialTheme.typography.titleMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    DeviceSettingsState.EditGroup -> {
                        val groups = listOf("Açougue", "Padaria", "Hortifrúti", "TVs", "Totens", "Coletores", "Administrativo")
                        WizardScaffold(
                            title = "Grupo",
                            step = 1,
                            totalSteps = 1,
                            error = wizardError,
                            primaryLabel = if (isBusy) "Salvando…" else "Salvar",
                            primaryEnabled = !isBusy && selectedGroup.trim().isNotBlank(),
                            onPrimary = {
                                val company =
                                    ArgosCompanyDetails(
                                        id = settings.getBoundCompanyIdCached(),
                                        code = settings.getBoundCompanyCodeCached(),
                                        name = settings.getBoundCompanyNameCached(),
                                        city = "",
                                        deviceCount = 0,
                                        logoUrl = "",
                                    )
                                if (company.code.isBlank()) {
                                    wizardError = "Vincule uma empresa primeiro."
                                    return@WizardScaffold
                                }
                                val nickname = settings.getBoundDeviceNicknameCached().ifBlank { deviceName.ifBlank { "DEVICE" } }
                                val branch = settings.getBoundDeviceBranchCached().ifBlank { branchNumber.ifBlank { "0" } }
                                val type = settings.getBoundDeviceTypeCached().ifBlank { selectedType.ifBlank { "terminal_consulta" } }
                                syncToServer(
                                    company = company,
                                    nickname = nickname,
                                    branch = branch,
                                    group = selectedGroup.trim(),
                                    type = type,
                                    onSuccess = { state = DeviceSettingsState.Menu },
                                    onFailure = {},
                                )
                            },
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                                Text("Selecione o grupo.", style = MaterialTheme.typography.titleMedium)
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                                    items(groups) { g ->
                                        val selected = selectedGroup.equals(g, ignoreCase = true)
                                        val bg = MaterialTheme.colorScheme.surface.copy(alpha = if (selected) 0.42f else 0.18f)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 62.dp)
                                                .background(color = bg, shape = RoundedCornerShape(16.dp))
                                                .clickable { selectedGroup = g }
                                                .padding(14.dp),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            Text(g, style = MaterialTheme.typography.titleMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    DeviceSettingsState.LinkGroupCode -> {
                        GroupLinkWizard(
                            onBack = { state = DeviceSettingsState.Menu },
                            onDone = { state = DeviceSettingsState.Menu },
                            launchLauncherOnSuccess = false,
                        )
                    }

                    DeviceSettingsState.Network -> {
                        NetworkCards()
                    }

                    DeviceSettingsState.Diagnostics -> {
                        DiagnosticsCards(permissionManager = permissions)
                    }

                    DeviceSettingsState.Provisioning -> {
                        ProvisioningCards(
                            onOpenSetupWizard = onOpenSetupWizard,
                            onEditCompany = { startEditCompany() },
                            onEditGroup = { state = DeviceSettingsState.EditGroup },
                            onEditBranch = { state = DeviceSettingsState.EditBranch },
                            onEditType = { state = DeviceSettingsState.EditDeviceType },
                        )
                    }

                    DeviceSettingsState.Advanced -> {
                        AdvancedCards(deviceId = deviceId, settings = settings)
                    }

                    DeviceSettingsState.About -> {
                        AboutCards(
                            technicalEnabled = technicalEnabled,
                            onToggleTechnical = { enabled ->
                                technicalEnabled = enabled
                                scope.launch { settings.setTechnicalModeEnabled(enabled) }
                            },
                        )
                    }

                    DeviceSettingsState.Technical -> {
                        TechnicalCards()
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy,
                onClick = { backFromWizard() },
            ) { Text("Voltar") }
        }
    }
}

private data class MenuItemSpec(
    val title: String,
    val icon: String,
    val onClick: () -> Unit,
)

@Composable
private fun MenuList(items: List<MenuItemSpec>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(items) { item ->
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { item.onClick() },
                containerAlpha = 0.20f,
                borderAlpha = 0.10f,
                elevation = 0.dp,
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.26f)),
                        elevation = CardDefaults.cardElevation(0.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                            LucideIcon(name = item.icon, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface, contentDescription = item.title)
                        }
                    }
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun WizardScaffold(
    title: String,
    step: Int,
    totalSteps: Int,
    error: String?,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val progress = (step.toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f)
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text("Passo $step de $totalSteps", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = { progress })
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            }
            content()
        }
        Button(modifier = Modifier.fillMaxWidth(), enabled = primaryEnabled, onClick = onPrimary) { Text(primaryLabel) }
    }
}

@Composable
private fun GlassValueBox(value: String) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        containerAlpha = 0.22f,
        borderAlpha = 0.10f,
        elevation = 0.dp,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(14.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CompanyConfirmCard(company: ArgosCompanyDetails?) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        containerAlpha = 0.22f,
        borderAlpha = 0.10f,
        elevation = 0.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(company?.name.orEmpty().ifBlank { "—" }, style = MaterialTheme.typography.titleLarge)
            if (company != null && company.city.isNotBlank()) Text(company.city, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (company != null && company.deviceCount > 0) Text("${company.deviceCount} dispositivos", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))
            Text("Deseja vincular este dispositivo a esta empresa?", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun DeviceInfoCards(
    deviceId: String,
    fingerprintManufacturer: String,
    fingerprintModel: String,
    androidSdk: Int,
    settings: SettingsManager,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val agentVersion = remember {
        runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()?.versionName.orEmpty()
    }
    val agentCode = remember {
        runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()?.longVersionCode?.toString().orEmpty()
    }
    val mplayerVersion = remember {
        runCatching { pm.getPackageInfo(PlayerAppConstants.resolvePackage(), 0) }.getOrNull()?.versionName.orEmpty()
    }
    val dpmOwner = remember { DeviceOwnerPolicyManager(context.applicationContext).isDeviceOwner(context.packageName) }
    val lastSyncMs = remember { settings.getLastCommandSyncMsCached() }
    val ram = remember { readRam(context.applicationContext) }
    val storage = remember { readStorage(context.applicationContext) }
    val batteryPct = remember { readBatteryPercent(context.applicationContext) }

    val cards =
        listOf(
            InfoCard("Nome do dispositivo", settings.getBoundDeviceNicknameCached().ifBlank { "—" }, "tag"),
            InfoCard("Fabricante", fingerprintManufacturer.ifBlank { "—" }, "cpu"),
            InfoCard("Modelo", fingerprintModel.ifBlank { "—" }, "cpu"),
            InfoCard("Serial", DeviceIdentityManager.getDeviceFingerprint(context.applicationContext).serial.orEmpty().ifBlank { "—" }, "hash"),
            InfoCard("Android", "SDK $androidSdk", "smartphone"),
            InfoCard("Versão do Agent", agentVersion.ifBlank { "—" }, "shield"),
            InfoCard("Build do Agent", agentCode.ifBlank { "—" }, "binary"),
            InfoCard("Versão do MPlayer", mplayerVersion.ifBlank { "—" }, "play"),
            InfoCard("RAM", ram, "memory-stick"),
            InfoCard("Armazenamento", storage, "hard-drive"),
            InfoCard("Bateria", batteryPct, "battery"),
            InfoCard("Status Device Owner", if (dpmOwner) "Ativo" else "Inativo", "shield-check"),
            InfoCard("Última sincronização", if (lastSyncMs > 0L) android.text.format.DateFormat.format("dd/MM/yyyy HH:mm", lastSyncMs).toString() else "—", "clock"),
            InfoCard("Device ID", deviceId, "fingerprint"),
            InfoCard("Firebase ID", BuildConfig.FIREBASE_APP_ID.ifBlank { "—" }, "cloud"),
            InfoCard("Empresa vinculada", settings.getBoundCompanyNameCached().ifBlank { "—" }, "building-2"),
            InfoCard("Grupo", settings.getBoundDeviceGroupCached().ifBlank { "—" }, "layers"),
            InfoCard("Filial", settings.getBoundDeviceBranchCached().ifBlank { "—" }, "store"),
        )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(cards) { c ->
            GlassCard(modifier = Modifier.fillMaxWidth(), containerAlpha = 0.20f, borderAlpha = 0.10f, elevation = 0.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.26f)),
                        elevation = CardDefaults.cardElevation(0.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                            LucideIcon(name = c.icon, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface, contentDescription = c.title)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(c.value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private data class InfoCard(val title: String, val value: String, val icon: String)

@Composable
private fun NetworkCards() {
    val context = LocalContext.current
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    var pingMs by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        while (true) {
            val ms = runCatching { ping204() }.getOrDefault(-1)
            pingMs = ms
            delay(3500)
        }
    }

    val active = cm.activeNetwork
    val caps = active?.let { cm.getNetworkCapabilities(it) }
    val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    val lp = active?.let { cm.getLinkProperties(it) }
    val dns = lp?.dnsServers?.joinToString { it.hostAddress ?: "" }.orEmpty()
    val gateway = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress.orEmpty()
    val ip = lp?.linkAddresses?.firstOrNull()?.address?.hostAddress.orEmpty()
    val ssid = runCatching { wifi.connectionInfo?.ssid?.trim()?.removePrefix("\"")?.removeSuffix("\"") }.getOrNull().orEmpty()
    val rssi = runCatching { wifi.connectionInfo?.rssi ?: -127 }.getOrNull() ?: -127
    val level = WifiManager.calculateSignalLevel(rssi, 5)

    val cards =
        listOf(
            InfoCard("IP", ip.ifBlank { "—" }, "globe"),
            InfoCard("Gateway", gateway.ifBlank { "—" }, "route"),
            InfoCard("DNS", dns.ifBlank { "—" }, "server"),
            InfoCard("Wi‑Fi atual", ssid.ifBlank { "—" }, "wifi"),
            InfoCard("Intensidade sinal", if (ssid.isBlank()) "—" else "${level}/4", "signal"),
            InfoCard("Internet", if (internet && validated) "OK" else if (internet) "Conectado" else "Sem conexão", "shield"),
            InfoCard("Último ping", if (pingMs >= 0) "${pingMs}ms" else "—", "activity"),
        )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(cards) { c ->
            GlassCard(modifier = Modifier.fillMaxWidth(), containerAlpha = 0.20f, borderAlpha = 0.10f, elevation = 0.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.26f)),
                        elevation = CardDefaults.cardElevation(0.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                            LucideIcon(name = c.icon, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface, contentDescription = c.title)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(c.value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCards(permissionManager: PermissionManager) {
    val context = LocalContext.current
    var states by remember { mutableStateOf(emptyList<com.mupa.agent.argos.permissions.PermissionState>()) }
    LaunchedEffect(Unit) {
        states = withContext(Dispatchers.Default) { permissionManager.checkAllPermissions() }
    }
    val dpmOwner = remember { DeviceOwnerPolicyManager(context.applicationContext).isDeviceOwner(context.packageName) }
    val cards =
        states.map { s ->
            val status =
                when (s.status) {
                    PermissionStatus.Completed -> "OK"
                    PermissionStatus.Pending -> "Pendente"
                    PermissionStatus.Checking -> "Em análise"
                    PermissionStatus.Error -> "Erro"
                    PermissionStatus.NotSupported -> "Não suportado"
                }
            val color =
                when (s.status) {
                    PermissionStatus.Completed -> ArgosTokens.Success
                    PermissionStatus.Pending -> ArgosTokens.Warning
                    PermissionStatus.Checking -> ArgosTokens.PrimaryBlue
                    PermissionStatus.Error -> ArgosTokens.Danger
                    PermissionStatus.NotSupported -> ArgosTokens.Offline
                }
            Triple(s.label, status, color)
        } + listOf(Triple("Device Owner", if (dpmOwner) "OK" else "Pendente", if (dpmOwner) ArgosTokens.Success else ArgosTokens.Warning))

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(cards) { (label, status, color) ->
            GlassCard(modifier = Modifier.fillMaxWidth(), containerAlpha = 0.20f, borderAlpha = 0.10f, elevation = 0.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).background(color, shape = RoundedCornerShape(20.dp)))
                    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ProvisioningCards(
    onOpenSetupWizard: () -> Unit,
    onEditCompany: () -> Unit,
    onEditGroup: () -> Unit,
    onEditBranch: () -> Unit,
    onEditType: () -> Unit,
) {
    val items =
        listOf(
            MenuItemSpec("Refazer Wizard", "sparkles", onClick = onOpenSetupWizard),
            MenuItemSpec("Trocar empresa", "building-2", onClick = onEditCompany),
            MenuItemSpec("Trocar grupo", "layers", onClick = onEditGroup),
            MenuItemSpec("Trocar filial", "store", onClick = onEditBranch),
            MenuItemSpec("Trocar tipo", "layout-grid", onClick = onEditType),
        )
    MenuList(items)
}

@Composable
private fun AdvancedCards(deviceId: String, settings: SettingsManager) {
    val context = LocalContext.current
    val androidId = remember { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty() }
    val kernel = remember { System.getProperty("os.version").orEmpty() }
    val sdk = remember { Build.VERSION.SDK_INT.toString() }
    val serial = remember { DeviceIdentityManager.getDeviceFingerprint(context.applicationContext).serial.orEmpty() }
    val src = remember { DeviceIdentityManager(context.applicationContext).getCachedSource() }

    val cards =
        listOf(
            InfoCard("Android ID", androidId.ifBlank { "—" }, "hash"),
            InfoCard("Serial", serial.ifBlank { "—" }, "fingerprint"),
            InfoCard("UUID/Device ID", deviceId, "fingerprint"),
            InfoCard("Firebase Token", settings.getArgosDeviceTokenCached().ifBlank { "—" }, "cloud"),
            InfoCard("Versão SDK", sdk, "binary"),
            InfoCard("Fabricante", Build.MANUFACTURER.orEmpty().trim().ifBlank { "—" }, "cpu"),
            InfoCard("Modelo", Build.MODEL.orEmpty().trim().ifBlank { "—" }, "cpu"),
            InfoCard("Kernel", kernel.ifBlank { "—" }, "terminal"),
            InfoCard("Fonte do ID", src.ifBlank { "—" }, "route"),
        )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(cards) { c ->
            GlassCard(modifier = Modifier.fillMaxWidth(), containerAlpha = 0.20f, borderAlpha = 0.10f, elevation = 0.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.26f)),
                        elevation = CardDefaults.cardElevation(0.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                            LucideIcon(name = c.icon, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface, contentDescription = c.title)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(c.value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCards(
    technicalEnabled: Boolean,
    onToggleTechnical: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val agentVersion = remember { runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()?.versionName.orEmpty() }
    val buildType = remember { BuildConfig.BUILD_TYPE }
    val versionCode = remember { runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()?.longVersionCode?.toString().orEmpty() }

    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(taps) {
        if (taps <= 0) return@LaunchedEffect
        delay(1200)
        taps = 0
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            containerAlpha = 0.20f,
            borderAlpha = 0.10f,
            elevation = 0.dp,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    painter = painterResource(id = R.drawable.logo_argos_hor),
                    contentDescription = "ARGOS",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clickable {
                            taps += 1
                            if (taps >= 5) {
                                taps = 0
                                onToggleTechnical(!technicalEnabled)
                            }
                        },
                )
                Text("Versão $agentVersion", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text("Build $versionCode • $buildType", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (technicalEnabled) "Modo técnico ativado" else "Toque 5x no logo para modo técnico", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth(), containerAlpha = 0.20f, borderAlpha = 0.10f, elevation = 0.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine("Site", "argos.mupa.com.br")
                InfoLine("Suporte", "suporte@mupa.com.br")
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TechnicalCards() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        GlassCard(modifier = Modifier.fillMaxWidth(), containerAlpha = 0.20f, borderAlpha = 0.10f, elevation = 0.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Modo técnico", style = MaterialTheme.typography.titleLarge)
                Text("Logs, OTA e telemetria.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
private fun GlassSmallChip(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)),
        elevation = CardDefaults.cardElevation(0.dp),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ArgosAlphaNumericKeyboard(
    enabled: Boolean,
    onKey: (String) -> Unit,
) {
    val rows =
        listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
            listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
            listOf("Z", "X", "C", "V", "B", "N", "M", "-"),
            listOf("ESP", "DEL", "LIMPAR"),
        )
    ArgosKeyboard(rows = rows, enabled = enabled, onKey = onKey)
}

@Composable
private fun ArgosNumericKeyboard(
    enabled: Boolean,
    onKey: (String) -> Unit,
) {
    val rows =
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("0", "DEL", "LIMPAR"),
        )
    ArgosKeyboard(rows = rows, enabled = enabled, onKey = onKey)
}

@Composable
private fun ArgosKeyboard(
    rows: List<List<String>>,
    enabled: Boolean,
    onKey: (String) -> Unit,
) {
    val dimens = LocalArgosDimens.current
    val compactHeight = LocalConfiguration.current.screenHeightDp <= 720
    val keyMinHeight = if (compactHeight) 40.dp else dimens.minTouchTarget
    val rowGap = if (compactHeight) 6.dp else 8.dp
    Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(rowGap), modifier = Modifier.fillMaxWidth()) {
                row.forEach { label ->
                    val weight = when (label) {
                        "ESP" -> 2f
                        "LIMPAR" -> 1.6f
                        else -> 1f
                    }
                    Button(
                        modifier = Modifier
                            .weight(weight)
                            .heightIn(min = keyMinHeight),
                        enabled = enabled,
                        onClick = { onKey(label) },
                    ) {
                        Text(
                            label,
                            textAlign = TextAlign.Center,
                            style = if (compactHeight) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

private fun readInstalledPackages(context: Context): List<String> {
    val pm = context.packageManager
    val pkgs = pm.getInstalledPackages(0)
    return pkgs.mapNotNull { it.packageName?.trim()?.takeIf { p -> p.isNotBlank() } }.distinct().sorted()
}

private fun readRam(context: Context): String {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    val total = mi.totalMem
    val avail = mi.availMem
    val totalMb = (total / (1024.0 * 1024.0)).roundToInt()
    val availMb = (avail / (1024.0 * 1024.0)).roundToInt()
    return "${availMb}MB livres / ${totalMb}MB"
}

private fun readStorage(context: Context): String {
    val dir = context.filesDir
    val stat = android.os.StatFs(dir.absolutePath)
    val free = stat.availableBytes
    val total = stat.totalBytes
    return "${Formatter.formatFileSize(context, free)} livres / ${Formatter.formatFileSize(context, total)}"
}

private fun readBatteryPercent(context: Context): String {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return if (pct in 0..100) "$pct%" else "—"
}

private fun ping204(): Int {
    val started = SystemClock.elapsedRealtime()
    val url = URL("https://www.google.com/generate_204")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 1500
        readTimeout = 1500
        instanceFollowRedirects = false
    }
    return runCatching {
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..399) return@runCatching -1
        (SystemClock.elapsedRealtime() - started).toInt().coerceAtLeast(0)
    }.getOrDefault(-1).also {
        conn.disconnect()
    }
}

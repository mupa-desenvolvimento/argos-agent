package com.mupa.agent.argos.ui.compose.screens

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mupa.agent.argos.managers.DeviceIdentityManager
import com.mupa.agent.argos.managers.GroupEnrollClient
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.ui.ArgosLauncherActivity
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class LinkState { Idle, Working, Success }

@Composable
fun GroupLinkWizard(
    onBack: () -> Unit,
    onDone: () -> Unit,
    launchLauncherOnSuccess: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsManager(context.applicationContext) }
    val deviceId = remember { DeviceIdentityManager.getPersistentDeviceId(context.applicationContext).trim() }

    val defaultNickname = remember {
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }.joinToString(" ")
            .ifBlank { "Terminal ${deviceId.takeLast(6)}" }
    }

    var nickname by remember { mutableStateOf(defaultNickname) }
    var part1 by remember { mutableStateOf("") }
    var part2 by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(LinkState.Idle) }
    var error by remember { mutableStateOf<String?>(null) }
    var boundGroupName by remember { mutableStateOf("") }

    val focusRight = remember { FocusRequester() }

    val code = "${part1.trim()}-${part2.trim()}"
    val isValid = part1.trim().length == 4 && part2.trim().length == 4 && nickname.isNotBlank()

    fun deviceInfoJson(): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER ?: "")
        put("model", Build.MODEL ?: "")
        put("android_version", Build.VERSION.RELEASE ?: "")
        put("sdk_int", Build.VERSION.SDK_INT)
        put("serial", deviceId)
        put("nickname", nickname.trim())
    }

    fun submit() {
        if (state == LinkState.Working || state == LinkState.Success) return
        if (!isValid) { error = "Preencha o apelido e o código do grupo"; return }
        error = null
        state = LinkState.Working
        scope.launch {
            val client = GroupEnrollClient(null)
            val result = client.enroll(code, deviceId, deviceInfoJson())
            if (result == null) {
                state = LinkState.Idle
                error = client.lastErrorMessage ?: "Grupo não encontrado. Verifique o código e a conexão."
                return@launch
            }
            val companyId = result.companyId.ifBlank { result.groupId }
            settings.setBoundCompany(companyId = companyId, companyName = result.groupName, companyCode = result.linkingCode)
            settings.setBoundDevice(deviceId = deviceId, group = result.groupName, token = "")
            settings.setBoundDeviceDetails(nickname = nickname.trim(), branch = "", type = result.type)
            settings.setDeviceApelidoInterno(nickname.trim())

            boundGroupName = result.groupName
            state = LinkState.Success
        }
    }

    LaunchedEffect(state) {
        if (state == LinkState.Success) {
            kotlinx.coroutines.delay(1400)
            if (launchLauncherOnSuccess) {
                runCatching {
                    context.startActivity(
                        Intent(context, ArgosLauncherActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                    )
                }
            }
            onDone()
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Cadastrar Dispositivo",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Defina um apelido e informe o código do grupo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            // ── Nickname field ──
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                singleLine = true,
                label = { Text("Apelido do dispositivo") },
                placeholder = { Text(defaultNickname) },
                enabled = state == LinkState.Idle,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(24.dp))

            // ── Group code label ──
            Text(
                "Código do Grupo",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(10.dp))

            // ── Two code fields side by side ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = part1,
                    onValueChange = { v ->
                        val filtered = v.uppercase().filter { it.isLetterOrDigit() }.take(4)
                        part1 = filtered
                        if (filtered.length == 4) focusRight.requestFocus()
                    },
                    singleLine = true,
                    placeholder = { Text("ABCD", fontSize = 20.sp) },
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        letterSpacing = 4.sp,
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    enabled = state == LinkState.Idle,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                )

                Text(
                    "—",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                OutlinedTextField(
                    value = part2,
                    onValueChange = { v ->
                        part2 = v.uppercase().filter { it.isLetterOrDigit() }.take(4)
                    },
                    singleLine = true,
                    placeholder = { Text("1234", fontSize = 20.sp) },
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        letterSpacing = 4.sp,
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    enabled = state == LinkState.Idle,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRight),
                    shape = RoundedCornerShape(12.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            AnimatedVisibility(visible = error != null) {
                Text(
                    error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { submit() },
                enabled = state == LinkState.Idle && isValid,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (state == LinkState.Working) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Vinculando…")
                } else {
                    Text("Vincular dispositivo", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            TextButton(onClick = onBack, enabled = state == LinkState.Idle) {
                Text("Voltar")
            }

            AnimatedVisibility(visible = state == LinkState.Success) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    Text(
                        "✓ Vinculado a \"$boundGroupName\"",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Aplicando configurações do grupo…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "ID: ${deviceId.takeLast(12)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

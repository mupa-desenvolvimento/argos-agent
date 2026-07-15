package com.mupa.agent.argos.ui.compose

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.mupa.agent.argos.managers.MaintenanceUnlock
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun hasNetwork(ctx: Context): Boolean {
    return try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) { false }
}

/**
 * Botão vermelho de Manutenção exibido quando o dispositivo está sem rede OU
 * conectado mas sem comunicação com o ARGOS (Firebase .info/connected = false).
 * Permite sair do kiosk e abrir as Configurações após senha mestre OU código
 * de 4 dígitos gerado pela Web.
 *
 * Renderize por cima do launcher/kiosk: MaintenanceOverlay()
 */
@Composable
fun MaintenanceOverlay() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var online by remember { mutableStateOf(hasNetwork(context)) }
    var fbConnected by remember { mutableStateOf(true) }

    // Poll connectivity
    LaunchedEffect(Unit) {
        while (true) {
            online = hasNetwork(context)
            delay(5_000)
        }
    }
    // Observe Firebase backend connection
    DisposableEffect(Unit) {
        //region debug-point argos-agent-startup-crash/firebase-default-app
        val ref = runCatching { FirebaseDatabase.getInstance().getReference(".info/connected") }
            .getOrElse { t ->
                Log.w("ArgosMaintenance", "Firebase default app not initialized; skipping .info/connected", t)
                fbConnected = true
                null
            }
        //endregion
        if (ref == null) {
            onDispose { }
        } else {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) { fbConnected = snapshot.getValue(Boolean::class.java) == true }
                override fun onCancelled(error: DatabaseError) { fbConnected = false }
            }
            ref.addValueEventListener(listener)
            onDispose { ref.removeEventListener(listener) }
        }
    }

    // Unreachable = no validated internet OR not connected to ARGOS backend.
    val unreachable = !online || !fbConnected
    // small grace so we don't flash on brief blips
    var stableUnreachable by remember { mutableStateOf(false) }
    LaunchedEffect(unreachable) {
        if (unreachable) { delay(8_000); stableUnreachable = unreachable } else stableUnreachable = false
    }

    var showDialog by remember { mutableStateOf(false) }

    if (!stableUnreachable) return

    // Floating red maintenance button (bottom-start corner)
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        FilledTonalButton(
            onClick = { showDialog = true },
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = Color(0xFFB91C1C), contentColor = Color.White
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.White)); Spacer(Modifier.width(8.dp))
            Text("Manutenção", fontWeight = FontWeight.Bold)
        }
    }

    if (showDialog) {
        MaintenanceDialog(
            onDismiss = { showDialog = false },
            onUnlock = {
                scope.launch {
                    val settings = SettingsManager(context.applicationContext)
                    val dpm = DeviceOwnerPolicyManager(context.applicationContext)
                    // Exit kiosk / MDM lock
                    runCatching { settings.setKioskMode(false) }
                    runCatching { settings.setMdmLocked(false) }
                    runCatching { dpm.applyUnlocked(context.packageName) }
                    runCatching { dpm.setAndroidLauncherAsHome(context.packageName) }
                    // Stop lock task via bridge
                    runCatching {
                        context.startActivity(
                            Intent("com.mupa.agent.argos.action.LOCKTASK_STOP")
                                .setClassName(context.packageName, "com.mupa.agent.argos.ui.LockTaskBridgeActivity")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                    }
                    // Open Android settings
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                showDialog = false
            },
        )
    }
}

@Composable
private fun MaintenanceDialog(onDismiss: () -> Unit, onUnlock: () -> Unit) {
    val now = remember { java.util.Calendar.getInstance() }
    val human = remember { MaintenanceUnlock.humanNow(now) }
    var pwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modo Manutenção", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Dispositivo sem comunicação com o ARGOS. Informe a senha mestre ou o código de 4 dígitos gerado pela central.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Data/hora deste dispositivo:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(human, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("Informe esta data/hora na central para gerar o código.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it; error = null },
                    label = { Text("Senha ou código") },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (MaintenanceUnlock.validate(pwd, now.timeInMillis)) onUnlock()
                else error = "Senha ou código inválido."
            }) { Text("Destravar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

package com.mupa.agent.argos.ui.compose.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.mupa.agent.argos.R
import com.mupa.agent.argos.launcher.KioskPolicyManager
import com.mupa.agent.argos.launcher.WhitelistManager
import com.mupa.agent.argos.managers.PlayerAppConstants
import com.mupa.agent.argos.security.AdminAuthManager
import com.mupa.agent.argos.security.AdminAuthResult
import com.mupa.agent.argos.ui.compose.GlassCard
import com.mupa.agent.argos.ui.compose.LocalArgosDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val PLAYER_PACKAGE: String
    get() = PlayerAppConstants.resolvePackage()

data class KioskAppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val isSystem: Boolean,
)

@Composable
fun KioskAppsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val whitelist = remember { WhitelistManager(context.applicationContext) }
    val kioskPolicy = remember { KioskPolicyManager(context.applicationContext) }
    val allowedApps by whitelist.allowedApps.collectAsState()

    var query by remember { mutableStateOf("") }
    var allowed by remember { mutableStateOf(setOf<String>()) }
    var items by remember { mutableStateOf<List<KioskAppItem>>(emptyList()) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }

    var showAuth by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        whitelist.refresh()
        items = withContext(Dispatchers.Default) { loadApps(context) }
    }

    LaunchedEffect(allowedApps) {
        allowed = allowedApps.map { it.packageName }.toSet()
    }

    val filtered = remember(items, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) items
        else items.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    val dimens = LocalArgosDimens.current
    Column(modifier = Modifier.padding(dimens.padding)) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.logo_argos_hor),
                contentDescription = "ARGOS",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text("Aplicativos do Kiosk", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = { query = it },
                label = { Text("Pesquisar") },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onBack) { Text("Voltar") }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { showAuth = true },
                ) { Text("Salvar whitelist") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Recomendado manter: $PLAYER_PACKAGE",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val icon = app.icon
                        if (icon != null) {
                            Image(
                                bitmap = icon.toBitmap(width = 96, height = 96).asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                            )
                        } else {
                            Spacer(modifier = Modifier.size(40.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(app.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val checked = allowed.contains(app.packageName) || app.packageName == context.packageName || app.packageName == PLAYER_PACKAGE
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { isChecked ->
                                allowed = if (isChecked) allowed + app.packageName else allowed - app.packageName
                            },
                        )
                    }
                }
            }
        }
    }

    if (showAuth) {
        AdminPasswordDialog(
            onDismiss = { showAuth = false },
            onAuthed = {
                showAuth = false
                scope.launch {
                    val selected = (allowed + setOf(context.packageName, PLAYER_PACKAGE)).map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    whitelist.setAllowedPackages(selected)
                    val err = kioskPolicy.applyNow()
                    message = "Kiosk" to (if (err == null) "Whitelist salva e aplicada" else "Whitelist salva, mas não aplicada: $err")
                }
            },
        )
    }

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

private fun loadApps(context: Context): List<KioskAppItem> {
    val pm = context.packageManager
    val installed = pm.getInstalledApplications(0)
    val out = installed.mapNotNull { ai: ApplicationInfo ->
        val pkg = ai.packageName.orEmpty().trim()
        if (pkg.isBlank()) return@mapNotNull null
        val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
        val icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull()
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        KioskAppItem(
            label = label,
            packageName = pkg,
            icon = icon,
            isSystem = isSystem,
        )
    }
    return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

@Composable
private fun AdminPasswordDialog(
    onDismiss: () -> Unit,
    onAuthed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { AdminAuthManager(context) }

    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val result = auth.authenticate(password)
                        when (result) {
                            is AdminAuthResult.Success -> onAuthed()
                            is AdminAuthResult.LockedOut -> {
                                error = "Bloqueado por ${result.secondsRemaining}s"
                                vibrateError()
                                shake.animateTo(18f, tween(durationMillis = 60))
                                shake.animateTo(-18f, tween(durationMillis = 60))
                                shake.animateTo(0f, tween(durationMillis = 60))
                            }
                            is AdminAuthResult.Failure -> {
                                error = "Senha incorreta (${result.failedAttempts}/3)"
                                vibrateError()
                                shake.animateTo(18f, tween(durationMillis = 60))
                                shake.animateTo(-18f, tween(durationMillis = 60))
                                shake.animateTo(0f, tween(durationMillis = 60))
                            }
                        }
                    }
                },
            ) { Text("OK") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

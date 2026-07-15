package com.mupa.agent.argos.geofence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mupa.agent.argos.managers.MaintenanceUnlock
import com.mupa.agent.argos.managers.SettingsManager
import kotlinx.coroutines.launch

/**
 * Full-screen overlay shown when the device is outside all active geofences.
 * Dismissed only by the fence's unlock_password OR the master maintenance password.
 */
@Composable
fun GeofenceLockOverlay() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsManager(context.applicationContext) }

    var locked by remember { mutableStateOf(settings.isGeofenceLockedCached()) }
    val fenceName = remember(locked) { settings.getGeofenceLockedFenceNameCached() }

    // Re-check every 10 s in case the engine cleared the lock (device moved back in)
    LaunchedEffect(Unit) {
        while (true) {
            locked = settings.isGeofenceLockedCached()
            kotlinx.coroutines.delay(10_000L)
        }
    }

    if (!locked) return

    var pwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE8000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(Color(0xFF1A1A2E), RoundedCornerShape(20.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Warning icon (📍 unicode as text — no emoji dependency)
            Text("⛔", fontSize = 48.sp)

            Text(
                "Dispositivo fora da área autorizada",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            if (fenceName.isNotBlank()) {
                Surface(
                    color = Color(0xFF2D2D4E),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        fenceName,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = Color(0xFFAAAAAA),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Text(
                "Este dispositivo saiu do perímetro configurado. Para continuar a operação, retorne à área autorizada ou insira a senha de desbloqueio.",
                color = Color(0xFFAAAAAA),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )

            OutlinedTextField(
                value = pwd,
                onValueChange = { pwd = it; error = null },
                label = { Text("Senha de desbloqueio", color = Color(0xFF888888)) },
                singleLine = true,
                isError = error != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF4444AA),
                    unfocusedBorderColor = Color(0xFF444444),
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Text(
                    error!!,
                    color = Color(0xFFEF4444),
                    fontSize = 12.sp,
                )
            }

            Button(
                onClick = {
                    val input = pwd.trim()
                    val storedPwd = settings.getGeofenceUnlockPasswordCached()
                    val validFencePwd = storedPwd.isNotBlank() && input == storedPwd
                    val validMasterPwd = MaintenanceUnlock.validate(input)
                    if (validFencePwd || validMasterPwd) {
                        scope.launch {
                            settings.setGeofenceLocked(false)
                            locked = false
                        }
                    } else {
                        error = "Senha incorreta."
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4444AA)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Desbloquear", fontWeight = FontWeight.Bold)
            }
        }
    }
}

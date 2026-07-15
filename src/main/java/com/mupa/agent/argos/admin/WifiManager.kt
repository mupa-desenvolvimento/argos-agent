package com.mupa.agent.argos.admin

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager as AndroidWifiManager
import android.provider.Settings
import android.util.Log

class WifiManager(private val context: Context) {
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? AndroidWifiManager
    private val tag = "ArgosWifiManager"

    fun openWifiSettings(): Intent {
        return Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Conecta à uma rede WiFi usando SSID e senha WPA2-PSK.
     * Retorna true se a conexão foi enfileirada; false se WiFi desativado ou erro.
     */
    fun connectToWifi(ssid: String, password: String): Boolean {
        return try {
            val wm = wifiManager ?: return false.also { Log.e(tag, "WifiManager não disponível") }

            // Ativa WiFi se estiver desativado
            if (!wm.isWifiEnabled) {
                Log.d(tag, "WiFi desativado, ativando...")
                wm.isWifiEnabled = true
            }

            // Configura a rede WPA2-PSK
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                hiddenSSID = false
            }

            // Remover qualquer conexão anterior com esse SSID
            val netId = wm.addNetwork(config)
            if (netId != -1) {
                wm.disconnect()
                val connected = wm.enableNetwork(netId, true)
                wm.reconnect()
                Log.d(tag, "WiFi conectando: $ssid (netId=$netId, connected=$connected)")
                true
            } else {
                Log.e(tag, "Falha ao adicionar rede WiFi: $ssid")
                false
            }
        } catch (e: Exception) {
            Log.e(tag, "Erro ao conectar WiFi: ${e.message}", e)
            false
        }
    }

    fun isWifiEnabled(): Boolean = wifiManager?.isWifiEnabled ?: false

    fun getCurrentSsid(): String? = try {
        @Suppress("DEPRECATION")
        wifiManager?.connectionInfo?.ssid?.removeSurrounding("\"")
    } catch (e: Exception) {
        null
    }
}


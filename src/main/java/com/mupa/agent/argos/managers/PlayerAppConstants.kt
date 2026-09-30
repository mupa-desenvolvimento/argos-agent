package com.mupa.agent.argos.managers

import android.content.Context
import com.mupa.agent.argos.models.AgentDeviceFamily

/**
 * Single source of truth for which player package this device should run.
 * X96/Amlogic TV boxes ship a dedicated lighter build; every other device
 * family runs the default enterprise player.
 */
object PlayerAppConstants {
    const val ENTERPRISE_PKG = "com.mupa.player.enterprise"
    const val X96_PKG = "com.mupa.player.x96"

    fun resolvePackage(): String =
        if (DeviceProfileManager().resolve().family == AgentDeviceFamily.x96_tv_box) X96_PKG else ENTERPRISE_PKG

    fun isX96(): Boolean = resolvePackage() == X96_PKG

    /**
     * Como [resolvePackage], mas nunca aponta pra um pacote que não está de fato instalado.
     * A detecção de família inclui heurísticas amplas (fabricante/fingerprint "droidlogic",
     * não só o modelo "X96") — um device já rodando `ENTERPRISE_PKG` pode ser reclassificado
     * como x96_tv_box só por isso. Usar isso em qualquer ponto que AGE sobre o pacote
     * (reabrir, destravar suspensão) evita que uma reclassificação errada quebre
     * silenciosamente o player que já está instalado. Chamadas puramente informativas
     * (status na UI, nome de versão) podem seguir usando [resolvePackage] direto.
     */
    fun resolveInstalledPackage(context: Context): String {
        val resolved = resolvePackage()
        if (isInstalled(context, resolved)) return resolved
        val fallback = if (resolved == X96_PKG) ENTERPRISE_PKG else X96_PKG
        return if (isInstalled(context, fallback)) fallback else resolved
    }

    private fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
}

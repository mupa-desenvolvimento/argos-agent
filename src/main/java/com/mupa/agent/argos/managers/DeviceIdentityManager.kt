package com.mupa.agent.argos.managers

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import com.mupa.agent.argos.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.UUID

// ── Modelos públicos ──────────────────────────────────────────────────────────

data class DeviceFingerprint(
    val primaryId: String,
    val imei: String?,
    val serial: String?,
    val manufacturerSerial: String?,
    val fallbackId: String?,
    val manufacturer: String,
    val model: String,
    val androidVersion: Int,
)

// ── Manager principal ─────────────────────────────────────────────────────────

class DeviceIdentityManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── API pública ───────────────────────────────────────────────────────────

    suspend fun getPersistentId(): String = generateIfMissing()

    fun generateIfMissing(): String {
        val cached = prefs.getString(KEY_ID, null)?.trim().orEmpty()
        if (cached.isNotBlank()) {
            // Se o ID salvo era instável (GEN:), tenta promover para ID de hardware
            val wasUnstable = !getCachedStable()
            if (wasUnstable) {
                val better = HardwareIdResolver(context).resolve()
                if (better.stable) {
                    save(better)
                    return better.id
                }
            }
            // Requisito: o device ID deve SEMPRE ser o S/N do hardware quando legível.
            // Um ID cacheado como IMEI:/GEN: (resolvido antes do Device Owner conceder
            // READ_PHONE_STATE) OU como MFR:<uuid-do-vendor> (Zebra/Honeywell, resolvido quando
            // a marca vinha antes do serial) deve ser promovido ao S/N real assim que ele ficar
            // legível. Só promovemos para SN: (o número da etiqueta) — nunca de SN: para outra
            // coisa — e uma vez por processo.
            if (!cached.startsWith("SN:") && !serialPromotionAttempted) {
                serialPromotionAttempted = true
                val better = HardwareIdResolver(context).resolve()
                if (better.stable && better.id.startsWith("SN:") && !better.id.equals(cached, ignoreCase = true)) {
                    save(better)
                    return better.id
                }
            }
            // Se o ID salvo contém um serial genérico conhecido (ex: MFR:123456789
            // gravado antes desta correção), descarta e re-resolve com MAC.
            val cachedBare = cached.removePrefix("MFR:").removePrefix("SN:").removePrefix("IMEI:").removePrefix("GEN:").removePrefix("N:")
            if (HardwareIdResolver.KNOWN_GENERIC_SERIALS.any { cachedBare.equals(it, ignoreCase = true) }) {
                val better = HardwareIdResolver(context).resolve()
                save(better)
                return better.id
            }
            return cached
        }
        val res = HardwareIdResolver(context).resolve()
        save(res)
        return res.id
    }

    fun getCachedId(): String = prefs.getString(KEY_ID, "").orEmpty()
    fun getCachedSource(): String = prefs.getString(KEY_SOURCE, "").orEmpty()
    fun getCachedStable(): Boolean {
        // Compatibilidade: versão anterior salvava como String ("true"/"false")
        return runCatching { prefs.getBoolean(KEY_STABLE, false) }.getOrElse {
            prefs.getString(KEY_STABLE, "false").equals("true", ignoreCase = true)
        }
    }
    fun getCachedResolvedAtMs(): Long {
        return runCatching { prefs.getLong(KEY_RESOLVED_AT, 0L) }.getOrElse {
            val raw = prefs.getString(KEY_RESOLVED_AT, null)?.trim()
            val parsed = raw?.toLongOrNull() ?: 0L
            prefs.edit().putLong(KEY_RESOLVED_AT, parsed).apply()
            parsed
        }
    }
    fun getCachedAttemptsJson(): String = prefs.getString(KEY_ATTEMPTS, "").orEmpty()

    fun getDeviceFingerprint(): DeviceFingerprint {
        val primary = getCachedId().ifBlank { generateIfMissing() }
        val resolver = HardwareIdResolver(context)
        val rawSerial = resolver.tryBuildSerial().value
        val effectiveSerial = rawSerial?.takeUnless { s ->
            HardwareIdResolver.KNOWN_GENERIC_SERIALS.any { g -> s.equals(g, ignoreCase = true) }
        }
        return DeviceFingerprint(
            primaryId    = primary,
            imei         = resolver.tryImei().value,
            serial       = effectiveSerial,
            manufacturerSerial = resolver.tryManufacturerSerial().value,
            fallbackId   = if (!getCachedStable()) primary.removePrefix("GEN:").ifBlank { null } else null,
            manufacturer = Build.MANUFACTURER.orEmpty().trim(),
            model        = Build.MODEL.orEmpty().trim(),
            androidVersion = Build.VERSION.SDK_INT,
        )
    }

    fun save(res: Resolution) {
        if (!validateId(res.id)) return
        prefs.edit()
            .putString(KEY_ID, res.id.trim())
            .putString(KEY_SOURCE, res.source)
            .remove(PERSISTENT_DEVICE_ID_STABLE_TAG) // remove chave String legada
            .putBoolean(KEY_STABLE, res.stable)
            .putLong(KEY_RESOLVED_AT, res.resolvedAtMs)
            .putString(KEY_ATTEMPTS, res.attemptsJson)
            .apply()
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        // Evita re-executar a cadeia de resolução (getprop/telefonia) a cada chamada quando o
        // dispositivo realmente não expõe serial — uma tentativa de promoção por processo basta.
        @Volatile
        private var serialPromotionAttempted = false

        fun getPersistentDeviceId(context: Context): String =
            DeviceIdentityManager(context.applicationContext).generateIfMissing()

        fun getDeviceFingerprint(context: Context): DeviceFingerprint =
            DeviceIdentityManager(context.applicationContext).getDeviceFingerprint()

        const val KEY_ID           = "persistent_device_id"
        const val KEY_SOURCE       = "persistent_device_id_source"
        const val KEY_STABLE       = "persistent_device_id_stable"
        const val KEY_RESOLVED_AT  = "persistent_device_id_resolved_at"
        const val KEY_ATTEMPTS     = "persistent_device_id_attempts"

        // Mantém retrocompatibilidade com chave antiga
        const val PERSISTENT_DEVICE_ID_TAG          = KEY_ID
        const val PERSISTENT_DEVICE_ID_SOURCE_TAG   = KEY_SOURCE
        const val PERSISTENT_DEVICE_ID_STABLE_TAG   = "persistent_device_id_stable_str"
        const val PERSISTENT_DEVICE_ID_RESOLVED_AT_TAG = KEY_RESOLVED_AT
        const val PERSISTENT_DEVICE_ID_ATTEMPTS_TAG = KEY_ATTEMPTS
        const val PERSISTENT_FALLBACK_ID_TAG        = "persistent_id"

        private const val PREFS_NAME = "mupa_device_identity_legacy"

        fun validateId(id: String): Boolean {
            val v = id.trim()
            return v.startsWith("IMEI:") || v.startsWith("SN:") ||
                   v.startsWith("MFR:") || v.startsWith("GEN:")
        }
    }
}

// ── Modelo de resolução ───────────────────────────────────────────────────────

data class Resolution(
    val id: String,
    val source: String,
    val stable: Boolean,
    val resolvedAtMs: Long,
    val attemptsJson: String,
)

data class Attempt(val value: String?, val reason: String?)

// ── Resolver de hardware ──────────────────────────────────────────────────────

/**
 * Resolve o melhor ID de hardware disponível para o dispositivo atual.
 *
 * Estratégia por prioridade (S/N do hardware SEMPRE vence — é o ID visível na
 * etiqueta do equipamento, no adb devices e no painel web):
 *  1. Serial da marca       — propriedades específicas que sobrevivem ao reset
 *  2. Build.getSerial()     — Android 6-9 direto; 10-14 requer Device Owner + READ_PHONE_STATE
 *  3. ro.serialno / getprop — leitura direta do bootloader via shell
 *  4. /proc/cmdline e sysfs — fontes alternativas do serial do bootloader
 *  5. IMEI                  — fallback quando nenhum serial é legível
 *  6. ANDROID_ID            — muda no factory reset, mas melhor que UUID
 *  7. UUID gerado           — último recurso, instável
 *
 * O método getprop é usado como técnica de leitura primária porque:
 *  - Não requer permissões especiais
 *  - Não é afetado pelas restrições non-SDK do Android 11+
 *  - Lê diretamente do bootloader/partição de hardware
 *  - Funciona em Android 6 até 14 em todas as marcas
 */
internal class HardwareIdResolver(private val context: Context) {

    fun resolve(): Resolution {
        val now = System.currentTimeMillis()
        val log = JSONArray()

        fun record(source: String, value: String?, stable: Boolean, reason: String? = null): Resolution? {
            val v = value?.trim().orEmpty()
            val ok = DeviceIdentityManager.validateId(v)
            log.put(
                JSONObject()
                    .put("source", source)
                    .put("ok", ok)
                    .put("stable", stable)
                    .put("value", if (ok) v else "")
                    .put("reason", reason.orEmpty())
            )
            return if (ok) Resolution(v, source, stable, now, log.toString()) else null
        }

        // 0. Dispositivos com serial genérico conhecido (TV boxes X96/Amlogic etc.) —
        //    esses chipsets gravam "123456789" ou strings similares em ro.serialno e
        //    em Build.SERIAL, tornando o serial completamente não-único. Para eles o
        //    endereço MAC do wlan0 é o identificador estável mais confiável disponível
        //    (não sofre randomização em TV boxes, pois eles não usam Wi-Fi gerenciado
        //    pelo framework Android da mesma forma que phones).
        if (isKnownGenericSerialDevice()) {
            tryMacAddress().let { a ->
                record("mac_generic_serial_device", a.value?.let { "MFR:$it" }, stable = true, reason = "x96_or_generic_tvbox_mac")
                    ?.let { return it }
            }
        }

        // 1. Build.getSerial() — o S/N REAL do hardware (o da etiqueta / adb devices / painel).
        //    Deve vencer QUALQUER id específico de marca: em Zebra/Honeywell etc. o serial de
        //    fábrica (ro.serialno) sobrevive ao factory reset igual ao UUID do vendor, mas é o
        //    número que o operador reconhece. Funciona em Android 6-9 e, no 10+, com
        //    READ_PHONE_STATE (Device Owner auto-concede) — senão cai para ro.serialno abaixo.
        tryBuildSerial().let { a ->
            val filtered = a.value?.takeUnless { KNOWN_GENERIC_SERIALS.any { g -> it.equals(g, ignoreCase = true) } }
            record("build_serial", filtered?.let { "SN:$it" }, stable = true, reason = if (filtered == null && a.value != null) "known_generic_serial" else a.reason)
                ?.let { return it }
        }

        // 2. ro.serialno / ro.boot.serialno via getprop — o mesmo S/N real, sem exigir permissão
        //    (lido direto do bootloader). Prefixo SN: porque É o número de série, não um UUID.
        tryManufacturerSerial().let { a ->
            val filtered = a.value?.takeUnless { KNOWN_GENERIC_SERIALS.any { g -> it.equals(g, ignoreCase = true) } }
            record("manufacturer_serial:${a.reason}", filtered?.let { "SN:$it" }, stable = true, reason = if (filtered == null && a.value != null) "known_generic_serial" else a.reason)
                ?.let { return it }
        }

        // 3. /proc/cmdline — androidboot.serialno, também o S/N real, sempre legível sem permissão
        tryKernelCmdlineSerial().let { a ->
            record("kernel_cmdline", a.value?.let { "SN:$it" }, stable = true, reason = a.reason)
                ?.let { return it }
        }

        // 4. /sys/class/android_usb — iSerial (mesmo S/N exibido pelo adb), world-readable
        tryUsbSerial().let { a ->
            record("usb_serial", a.value?.let { "SN:$it" }, stable = true, reason = a.reason)
                ?.let { return it }
        }

        // 5. Propriedades específicas da marca (UUID do vendor) — só quando NENHUM S/N real é
        //    legível. Sobrevivem ao factory reset, mas não são o número da etiqueta, por isso
        //    ficam abaixo das fontes de serial acima.
        tryBrandSpecific().let { a ->
            record("brand_specific:${a.reason}", a.value?.let { "MFR:$it" }, stable = true, reason = a.reason)
                ?.let { return it }
        }

        // 7. IMEI — só depois de esgotar as fontes de serial de hardware: o requisito do
        //    produto é que o device ID seja SEMPRE o S/N do equipamento (é o que aparece na
        //    etiqueta, no adb devices e no painel web). IMEI fica como fallback para
        //    dispositivos onde nenhum serial é legível.
        tryImei().let { a ->
            record("imei", a.value?.let { "IMEI:$it" }, stable = true, reason = a.reason)
                ?.let { return it }
        }

        // 8. ANDROID_ID — instável no factory reset mas melhor que UUID
        tryAndroidId().let { a ->
            record("android_id", a.value?.let { "GEN:${it}" }, stable = false, reason = a.reason)
                ?.let { return it }
        }

        // 6. UUID gerado — último recurso
        val uuid = getOrCreateUuid()
        log.put(JSONObject().put("source", "random_uuid").put("ok", true)
            .put("stable", false).put("value", "GEN:$uuid").put("reason", "no_stable_hw_id"))
        return Resolution("GEN:$uuid", "random_uuid", false, now, log.toString())
    }

    // ── Detecção de serial genérico ───────────────────────────────────────────
    //
    // TV boxes baseados em Amlogic (X96, X88, X98, HK1, H96, etc.), Rockchip
    // (MXQ, R-BOX, T9 etc.) e Allwinner gravam seriais de fábrica genéricos
    // idênticos em todos os dispositivos da mesma linha de produção. Detectamos
    // pelo nome do modelo E/OU pela leitura direta do serial do bootloader.

    private fun isKnownGenericSerialDevice(): Boolean {
        val model = Build.MODEL.orEmpty().trim().lowercase()
        val mfr   = Build.MANUFACTURER.orEmpty().trim().lowercase()

        // Detecta pelo modelo (prefixos comuns de TV boxes genéricos)
        val knownModels = listOf("x96", "x88", "x98", "h96", "hk1", "mxq", "r-box", "t9", "tx3", "tx6", "tx9", "a95x", "b860h", "m96")
        if (knownModels.any { model.startsWith(it) || model.contains(it) }) return true

        // Detecta pelo fabricante quando é claramente um chipset genérico
        val knownMfrs = listOf("amlogic", "rockchip", "allwinner", "hk_x96", "x96", "droidlogic")
        if (knownMfrs.any { mfr.contains(it) }) return true

        // Detecta pelo valor do serial: se já retornar um dos valores genéricos conhecidos,
        // independente do modelo, descarta e usa MAC.
        val rawSerial = runCatching {
            if (Build.VERSION.SDK_INT >= 26) Build.getSerial() else @Suppress("DEPRECATION") Build.SERIAL
        }.getOrNull()?.trim().orEmpty()
        if (rawSerial.isNotBlank() && KNOWN_GENERIC_SERIALS.any { rawSerial.equals(it, ignoreCase = true) }) return true

        // Verifica ro.serialno via getprop também
        val propSerial = getprop("ro.serialno")?.trim().orEmpty()
        if (propSerial.isNotBlank() && KNOWN_GENERIC_SERIALS.any { propSerial.equals(it, ignoreCase = true) }) return true

        return false
    }

    // ── Estratégia 1: IMEI ────────────────────────────────────────────────────

    fun tryImei(): Attempt {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return Attempt(null, "no_telephony_service")

        val res = runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 26) tm.getImei(0) else tm.deviceId
        }
        val raw = res.getOrNull()?.trim().orEmpty()
        if (raw.isBlank()) return Attempt(null, res.exceptionOrNull()?.javaClass?.simpleName ?: "empty")
        if (!raw.all { it.isDigit() }) return Attempt(null, "not_digits")
        if (raw.all { it == '0' }) return Attempt(null, "all_zeros")
        if (raw.length < 14) return Attempt(null, "too_short")
        return Attempt(raw, null)
    }

    // ── Estratégia 2: Propriedades específicas por marca ─────────────────────
    //
    // Cada marca industrial tem propriedades no seu firmware que ficam em
    // partições separadas (/persist, /vendor, /oem) e NÃO são apagadas
    // pelo factory reset padrão do Android.

    fun tryBrandSpecific(): Attempt {
        val mfr = Build.MANUFACTURER?.lowercase().orEmpty()
        val props = when {
            "zebra" in mfr || "motorola solutions" in mfr -> ZEBRA_PROPS
            "bluebird" in mfr -> BLUEBIRD_PROPS
            "urovo" in mfr -> UROVO_PROPS
            "gertec" in mfr -> GERTEC_PROPS
            "meferi" in mfr -> MEFERI_PROPS
            "elgin" in mfr -> ELGIN_PROPS
            "honeywell" in mfr -> HONEYWELL_PROPS
            "datalogic" in mfr -> DATALOGIC_PROPS
            "point mobile" in mfr || "pointmobile" in mfr -> POINT_MOBILE_PROPS
            else -> emptyList()
        }

        for (key in props) {
            val v = getprop(key) ?: readReflection(key) ?: continue
            if (v.isBlank() || v.equals("unknown", ignoreCase = true)) continue
            if (v.length < 4) continue
            return Attempt(v, key)
        }
        return Attempt(null, "brand_props_not_found:$mfr")
    }

    // ── Estratégia 3: Build.getSerial() ──────────────────────────────────────

    fun tryBuildSerial(): Attempt {
        // "legacy" build flavor only — Build.getSerial()/READ_PHONE_STATE-based serial
        // resolution is unreliable on some cheap Android 9-and-below collectors (returns
        // "unknown" or requires a permission grant flow operators forget to do); their WiFi
        // MAC address is consistently readable instead. Not used on the standard flavor:
        // MAC-address randomization (Android 10+) makes this unreliable on newer devices.
        if (BuildConfig.USE_MAC_AS_SERIAL) {
            tryMacAddress().let { if (it.value != null) return it }
        }

        val res = runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        }
        val v = res.getOrNull()?.trim().orEmpty()
        if (v.isBlank()) return Attempt(null, res.exceptionOrNull()?.javaClass?.simpleName ?: "empty")
        if (v.equals(Build.UNKNOWN, ignoreCase = true)) return Attempt(null, "unknown")
        if (v.length < 4) return Attempt(null, "too_short")
        return Attempt(v, null)
    }

    /**
     * Reads the WiFi adapter's hardware MAC address directly via NetworkInterface — works
     * without ACCESS_WIFI_STATE and without an active WiFi connection (unlike
     * WifiManager.connectionInfo.macAddress), and isn't subject to the per-app MAC
     * randomization Android 10+ applies to WifiManager's API for non-privileged callers.
     * Formatted with no separators (e.g. "AABBCCDDEEFF") so it slots into the same
     * "bare serial string" convention used everywhere else (Firebase keys, devices.serial).
     */
    private fun tryMacAddress(): Attempt {
        val mac = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            for (iface in interfaces) {
                if (!iface.name.equals("wlan0", ignoreCase = true)) continue
                val bytes = iface.hardwareAddress ?: continue
                val formatted = bytes.joinToString("") { "%02X".format(it) }
                if (formatted.isNotBlank() && formatted != "000000000000") return@runCatching formatted
            }
            null
        }.getOrNull()
        return if (mac.isNullOrBlank()) Attempt(null, "mac_unavailable") else Attempt(mac, null)
    }

    // ── Estratégia 4: ro.serialno via getprop (universal) ────────────────────
    //
    // getprop lê diretamente do bootloader — não requer permissões,
    // não é afetado pelas restrições non-SDK do Android 11+ e
    // funciona em Android 6 até 14 em todas as marcas.

    fun tryManufacturerSerial(): Attempt {
        for (key in UNIVERSAL_SERIAL_PROPS) {
            val v = getprop(key) ?: readReflection(key) ?: continue
            if (v.isBlank() || v.equals("unknown", ignoreCase = true)) continue
            if (v.length < 4) continue
            return Attempt(v, key)
        }
        return Attempt(null, "not_found")
    }

    // ── Estratégia 5: /proc/cmdline ───────────────────────────────────────────
    //
    // O kernel Linux expõe sua linha de comando em /proc/cmdline.
    // Virtualmente todos os dispositivos Android incluem o parâmetro
    // androidboot.serialno=XXXX nesse arquivo — é world-readable, sem
    // permissões e não é afetado por nenhuma restrição do Android.
    // O valor vem diretamente do bootloader e sobrevive ao factory reset.

    private fun tryKernelCmdlineSerial(): Attempt {
        return runCatching {
            val cmdline = java.io.File("/proc/cmdline").readText()
            val match = Regex("androidboot\\.serialno=([^\\s]+)").find(cmdline)
            val serial = match?.groupValues?.getOrNull(1)?.trim()
            if (serial.isNullOrBlank() || serial.equals("unknown", ignoreCase = true)) {
                Attempt(null, "not_in_cmdline")
            } else {
                Attempt(serial, "proc_cmdline")
            }
        }.getOrElse { Attempt(null, "cmdline_error:${it.javaClass.simpleName}") }
    }

    // ── Estratégia 6: /sys/class/android_usb ─────────────────────────────────
    //
    // O subsistema USB do kernel expõe o serial do dispositivo em
    // /sys/class/android_usb/android0/iSerial — é o mesmo serial
    // exibido pelo adb devices e geralmente é o serial de fábrica.
    // World-readable em todos os dispositivos que usam o driver USB do Android.

    private fun tryUsbSerial(): Attempt {
        val paths = listOf(
            "/sys/class/android_usb/android0/iSerial",
            "/sys/devices/platform/android_usb/iSerial",
            "/sys/bus/usb/devices/usb1/serial",
        )
        for (path in paths) {
            val v = runCatching {
                java.io.File(path).readText().trim()
            }.getOrNull() ?: continue
            if (v.isBlank() || v.equals("unknown", ignoreCase = true)) continue
            if (v.length < 4) continue
            return Attempt(v, path)
        }
        return Attempt(null, "usb_serial_not_found")
    }

    // ── Estratégia 7: ANDROID_ID ──────────────────────────────────────────────

    private fun tryAndroidId(): Attempt {
        val id = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.trim()
        }.getOrNull().orEmpty()
        if (id.isBlank()) return Attempt(null, "empty")
        if (id == ANDROID_ID_KNOWN_BUG) return Attempt(null, "known_bug_value")
        if (id.length < 8) return Attempt(null, "too_short")
        return Attempt(id, null)
    }

    // ── Helpers de leitura ────────────────────────────────────────────────────

    // Executa getprop via shell — não sofre restrições de reflection nem permissões
    private fun getprop(key: String): String? = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("getprop", key))
        val result = proc.inputStream.bufferedReader().readLine()?.trim()
        proc.destroy()
        result?.ifBlank { null }
    }.getOrNull()

    // Reflection como fallback para versões mais antigas
    private fun readReflection(key: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java, String::class.java)
        (m.invoke(null, key, "") as? String)?.trim()?.ifBlank { null }
    }.getOrNull()

    private fun getOrCreateUuid(): String {
        val prefs = context.getSharedPreferences("mupa_device_identity_legacy", Context.MODE_PRIVATE)
        val existing = prefs.getString("persistent_id", null)?.trim().orEmpty()
        if (existing.isNotBlank()) return existing
        val uuid = UUID.randomUUID().toString()
        prefs.edit().putString("persistent_id", uuid).apply()
        return uuid
    }

    // ── Propriedades por marca ────────────────────────────────────────────────

    companion object {
        // Zebra / Motorola Solutions (TC22, TC52, TC72, MC33, etc.)
        // persist.vendor.zebra.sys.uuid fica na partição /persist — sobrevive ao factory reset
        private val ZEBRA_PROPS = listOf(
            "persist.vendor.zebra.sys.uuid",
            "ro.vendor.zebra.hardware.uuid",
            "persist.sys.zebra.uuid",
            "ro.boot.serialno",
            "ro.serialno",
        )

        // Bluebird (BP30, EF500, etc.)
        private val BLUEBIRD_PROPS = listOf(
            "ro.serialno",
            "ro.boot.serialno",
            "persist.sys.bb.serialno",
            "ro.bluebird.sn",
        )

        // Urovo (DT40, i6310, MC9000S, etc.)
        private val UROVO_PROPS = listOf(
            "persist.sys.urovo.serialno",
            "ro.urovo.sn",
            "ro.serialno",
            "ro.boot.serialno",
            "persist.urovo.device.sn",
        )

        // Gertec (G800, PX300, etc.) — terminais brasileiros
        private val GERTEC_PROPS = listOf(
            "ro.serialno",
            "ro.boot.serialno",
            "ro.product.gertec.serialno",
            "persist.gertec.sn",
            "ro.gertec.sn",
        )

        // Meferi (MF700, etc.)
        private val MEFERI_PROPS = listOf(
            "ro.serialno",
            "ro.boot.serialno",
            "persist.meferi.serialno",
            "ro.meferi.sn",
        )

        // Elgin (L10, M10, etc.) — frequentemente baseados em hardware de terceiros
        private val ELGIN_PROPS = listOf(
            "ro.serialno",
            "ro.boot.serialno",
            "persist.elgin.serialno",
            "ro.elgin.sn",
        )

        // Honeywell (CT40, CT60, ScanPal EDA, etc.)
        private val HONEYWELL_PROPS = listOf(
            "ro.serialno",
            "ro.boot.serialno",
            "persist.sys.honeywell.uuid",
            "ro.hw.serialno",
        )

        // Datalogic (Memor, Skorpio, etc.)
        private val DATALOGIC_PROPS = listOf(
            "ro.serialno",
            "ro.boot.serialno",
            "persist.datalogic.serialno",
            "ro.datalogic.sn",
        )

        // Point Mobile (PM85, PM90, PM550, etc.) — marca sul-coreana
        private val POINT_MOBILE_PROPS = listOf(
            "ro.serialno",
            "ro.boot.serialno",
            "persist.pm.serialno",
            "ro.pointmobile.sn",
        )

        // Propriedades universais — presentes em praticamente todos os dispositivos
        // Android independente da marca. Lidas via getprop (não reflection).
        private val UNIVERSAL_SERIAL_PROPS = listOf(
            "ro.serialno",           // hardware serial, universal
            "ro.boot.serialno",      // serial lido do kernel cmdline
            "ro.product.serial",     // adicionado no Android 10
            "sys.serialnumber",
            "ro.ril.oem.sno",
            "gsm.serial",
            "ro.boot.hardware.serial",
        )

        private const val ANDROID_ID_KNOWN_BUG = "9774d56d682e549c"

        // Seriais genéricos conhecidos — produzidos em massa por TV boxes e chipsets
        // Amlogic/Rockchip/Allwinner que não gravam serial único por unidade.
        internal val KNOWN_GENERIC_SERIALS = setOf(
            "123456789",
            "0123456789",
            "1234567890",
            "0000000000",
            "000000000000",
            "AMLOGIC",
            "ROCKCHIP",
            "ALLWINNER",
            "unknown",
            "null",
        )
    }
}

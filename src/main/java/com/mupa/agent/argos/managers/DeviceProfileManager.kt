package com.mupa.agent.argos.managers

import android.os.Build
import com.mupa.agent.argos.models.AgentDeviceFamily
import com.mupa.agent.argos.models.AgentDeviceProfile
import java.util.Locale

class DeviceProfileManager {
    fun resolve(): AgentDeviceProfile {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val brand = Build.BRAND.orEmpty().trim()
        val fingerprint = Build.FINGERPRINT.orEmpty().trim()
        val normalizedManufacturer = manufacturer.lowercase(Locale.ROOT)
        val normalizedModel = model.lowercase(Locale.ROOT)
        val normalizedBrand = brand.lowercase(Locale.ROOT)
        val normalizedFingerprint = fingerprint.lowercase(Locale.ROOT)

        val family = when {
            normalizedModel.contains("mc45") -> AgentDeviceFamily.meferi_mc45
            normalizedManufacturer.contains("zebra") && (normalizedModel.contains("tc21") || normalizedModel.contains("tc22") || normalizedModel.contains("tc26")) -> AgentDeviceFamily.zebra_tc2x
            normalizedManufacturer.contains("zebra") && (normalizedModel.contains("et40") || normalizedModel.contains("et45")) -> AgentDeviceFamily.zebra_et4x
            normalizedManufacturer.contains("zebra") -> AgentDeviceFamily.zebra_generic
            normalizedManufacturer.contains("gertec") && normalizedModel.contains("sk100") -> AgentDeviceFamily.gertec_sk100
            normalizedManufacturer.contains("gertec") && normalizedModel.contains("sk210") -> AgentDeviceFamily.gertec_sk210
            normalizedManufacturer.contains("gertec") && normalizedModel.contains("gbot") -> AgentDeviceFamily.gertec_gbot
            normalizedManufacturer.contains("gertec") -> AgentDeviceFamily.gertec_generic
            normalizedModel.contains("x96") ||
                normalizedModel.contains("x96max") ||
                normalizedBrand.contains("droidlogic") ||
                normalizedManufacturer.contains("droidlogic") ||
                normalizedFingerprint.contains("droidlogic") -> AgentDeviceFamily.x96_tv_box
            normalizedManufacturer.contains("bluebird") && normalizedModel.contains("st103") -> AgentDeviceFamily.bluebird_st103
            normalizedManufacturer.contains("bluebird") -> AgentDeviceFamily.bluebird_generic
            normalizedManufacturer.isNotBlank() || normalizedModel.isNotBlank() -> AgentDeviceFamily.generic_android
            else -> AgentDeviceFamily.unknown
        }

        val enterpriseDevice = family in setOf(
            AgentDeviceFamily.meferi_mc45,
            AgentDeviceFamily.zebra_tc2x,
            AgentDeviceFamily.zebra_et4x,
            AgentDeviceFamily.zebra_generic,
            AgentDeviceFamily.gertec_sk100,
            AgentDeviceFamily.gertec_sk210,
            AgentDeviceFamily.gertec_gbot,
            AgentDeviceFamily.gertec_generic,
            AgentDeviceFamily.bluebird_st103,
            AgentDeviceFamily.bluebird_generic,
        )

        val tags = buildSet {
            add(family.name)
            manufacturer.takeIf { it.isNotBlank() }?.let { add("manufacturer:${it.lowercase(Locale.ROOT)}") }
            model.takeIf { it.isNotBlank() }?.let { add("model:${it.lowercase(Locale.ROOT)}") }
            if (enterpriseDevice) add("enterprise")
        }

        return AgentDeviceProfile(
            family = family,
            manufacturer = manufacturer,
            model = model,
            brand = brand,
            sdkInt = Build.VERSION.SDK_INT,
            enterpriseDevice = enterpriseDevice,
            tags = tags,
        )
    }
}

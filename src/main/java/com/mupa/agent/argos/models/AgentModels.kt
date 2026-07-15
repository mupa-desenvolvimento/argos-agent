package com.mupa.agent.argos.models

enum class AgentCommandState {
    queued,
    received,
    executing,
    success,
    failed,
    timeout,
    retrying,
    cancelled,
    rollback,
    expired,
}

enum class AgentLogCategory {
    INFO,
    WARNING,
    ERROR,
    CRITICAL,
    AUTO_HEALING,
    OTA,
    COMMANDS,
    REMOTE,
    LOCATION,
    KIOSK,
    APP_MANAGER,
}

enum class AgentDeviceFamily {
    generic_android,
    meferi_mc45,
    zebra_tc2x,
    zebra_et4x,
    zebra_generic,
    gertec_sk100,
    gertec_sk210,
    gertec_gbot,
    gertec_generic,
    x96_tv_box,
    bluebird_st103,
    bluebird_generic,
    unknown,
}

data class AgentDeviceProfile(
    val family: AgentDeviceFamily,
    val manufacturer: String,
    val model: String,
    val brand: String,
    val sdkInt: Int,
    val enterpriseDevice: Boolean,
    val tags: Set<String> = emptySet(),
)

data class AgentCommandEnvelope(
    val commandId: String,
    val command: String,
    val paramsJson: String,
    val priority: Int,
    val state: AgentCommandState,
    val attempts: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val timeoutAt: Long?,
    val executionToken: String,
)

data class AgentCommandAck(
    val commandId: String,
    val state: AgentCommandState,
    val message: String,
    val executedAt: Long,
)

data class AgentConfigurationSnapshot(
    val scope: String,
    val configurationHash: String,
    val payloadJson: String,
    val updatedAt: Long,
)

data class AgentHeartbeatPayload(
    val battery: Int,
    val wifi: Boolean,
    val ram: Int,
    val storage: Int,
    val ipAddress: String?,
    val foregroundPackage: String?,
    val uptime: Long,
    val agentVersion: String,
    val agentVersionCode: Long = 0L,
    val mplayerVersion: String,
    val remoteVersion: String,
)

data class AgentHealthSnapshot(
    val ramPercent: Int,
    val cpuPercent: Int,
    val storagePercent: Int,
    val batteryPercent: Int,
    val temperatureCelsius: Double?,
    val wifiConnected: Boolean,
    val ipAddress: String?,
    val foregroundPackage: String?,
    val uptimeSeconds: Long,
    val agentVersion: String,
    val agentVersionCode: Long = 0L,
    val mplayerVersion: String?,
    val remoteVersion: String?,
)

data class AgentAppInventoryItem(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val lastUsedAt: Long?,
)

data class AgentLocationPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val capturedAt: Long,
)

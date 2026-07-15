package com.mupa.agent.argos.firebase

object FirebaseRuntimeMode {
    // Firebase RTDB is only an event bus. No commands, logs, status, configuration,
    // inventory or heartbeat data should be persisted there.
    const val EVENT_BUS_ONLY_MODE = true

    val allowAgentPublish: Boolean
        get() = false

    val allowPresencePush: Boolean
        get() = false

    val allowDeviceConfigPull: Boolean
        get() = false

    val allowPoller: Boolean
        get() = false

    val allowFirebaseConnectivityTracking: Boolean
        get() = false
}
